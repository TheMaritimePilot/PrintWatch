package dev.printwatch;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;

/**
 * Downloads the job's .3mf from the printer's SD card (implicit FTPS on port 990)
 * and pulls out the plate preview image and the filament usage.
 */
final class ModelFetcher {

    static final class Result {
        byte[] image;                         // PNG, may be null
        JSONArray filaments = new JSONArray(); // [{type, color, grams, meters}]
    }

    private static final long MAX_SIZE = 300L * 1024 * 1024;

    private final String host;
    private final String accessCode;
    private SSLSocket control;
    private BufferedReader reader;
    private OutputStream writer;

    ModelFetcher(String host, String accessCode) {
        this.host = host;
        this.accessCode = accessCode;
    }

    /**
     * @param taskName  print.subtask_name, e.g. "bin-1x1x5"
     * @param gcodeFile print.gcode_file, e.g. "/data/Metadata/plate_2.gcode" or "bin.gcode.3mf"
     */
    Result fetch(String taskName, String gcodeFile, File cacheDir) throws IOException {
        File tmp = new File(cacheDir, "job.3mf");
        try {
            open();
            String path = findFile(taskName, gcodeFile);
            if (path == null) throw new IOException("Job file not found on SD card");
            download(path, tmp);
            quit();
        } finally {
            closeQuietly();
        }
        try {
            return parse3mf(tmp, plateNumber(gcodeFile));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    static int plateNumber(String gcodeFile) {
        Matcher m = Pattern.compile("plate_(\\d+)").matcher(gcodeFile == null ? "" : gcodeFile);
        return m.find() ? Integer.parseInt(m.group(1)) : 1;
    }

    // ---------------------------------------------- files on the SD card

    /** The print files (.3mf) on the SD card: [{path, name, size, date}], newest first where known. */
    JSONArray listPrintFiles() throws IOException {
        JSONArray out = new JSONArray();
        try {
            open();
            for (String dir : new String[]{"/", "/cache"}) {
                for (String line : listLong(dir)) {
                    Matcher m = LIST_LINE.matcher(line);
                    if (!m.matches() || !m.group(1).equals("-")) continue;
                    String name = m.group(4);
                    if (!name.toLowerCase(Locale.ROOT).endsWith(".3mf")) continue;
                    try {
                        JSONObject f = new JSONObject();
                        f.put("path", (dir.equals("/") ? "" : dir.substring(1) + "/") + name);
                        f.put("name", name);
                        f.put("size", Long.parseLong(m.group(2)));
                        f.put("date", m.group(3).replaceAll("\\s+", " "));
                        out.put(f);
                    } catch (Exception ignored) { }
                }
            }
            quit();
        } finally {
            closeQuietly();
        }
        return out;
    }

    // "-rw-r--r--    1 0  0   1234567 Nov 22 10:00 name.3mf"
    private static final Pattern LIST_LINE =
            Pattern.compile("^([-dl])\\S*\\s+\\d+\\s+\\S+\\s+\\S+\\s+(\\d+)\\s+(\\w{3}\\s+\\d+\\s+[\\d:]+)\\s+(.+)$");

    /**
     * Downloads a print file and describes its plates: preview images are written to
     * thumbDir as key_N.png.
     * @return {plates:[{plate, thumb, seconds, grams, filaments:[{id,type,color,grams}]}]}
     */
    JSONObject projectInfo(String path, File cacheDir, File thumbDir, String key) throws Exception {
        if (path.contains("..")) throw new IOException("Bad path");
        File tmp = new File(cacheDir, "project.3mf");
        try {
            open();
            download("/" + path, tmp);
            quit();
        } finally {
            closeQuietly();
        }
        try {
            return describe3mf(tmp, thumbDir, key);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    static JSONObject describe3mf(File file, File thumbDir, String key) throws Exception {
        JSONObject info = new JSONObject();
        JSONArray plates = new JSONArray();
        //noinspection ResultOfMethodCallIgnored
        thumbDir.mkdirs();
        try (ZipFile zip = new ZipFile(file)) {
            String xml = "";
            ZipEntry si = zip.getEntry("Metadata/slice_info.config");
            if (si != null) xml = new String(readAll(zip.getInputStream(si)), StandardCharsets.UTF_8);
            for (int n = 1; n <= 64; n++) {
                if (zip.getEntry("Metadata/plate_" + n + ".gcode") == null) continue; // not sliced
                JSONObject p = new JSONObject();
                p.put("plate", n);
                ZipEntry png = zip.getEntry("Metadata/plate_" + n + ".png");
                if (png != null) {
                    File out = new File(thumbDir, key + "_" + n + ".png");
                    try (FileOutputStream fo = new FileOutputStream(out)) { fo.write(readAll(zip.getInputStream(png))); }
                    p.put("thumb", out.getName());
                }
                String body = plateXml(xml, n);
                p.put("seconds", parseD(meta(body, "prediction")));
                p.put("grams", parseD(meta(body, "weight")));
                p.put("filaments", filamentsForPlate(xml, n));
                plates.put(p);
            }
        }
        info.put("plates", plates);
        return info;
    }

    private static String plateXml(String xml, int plate) {
        Matcher plates = Pattern.compile("<plate>(.*?)</plate>", Pattern.DOTALL).matcher(xml);
        while (plates.find()) {
            String body = plates.group(1);
            if (plate == num(meta(body, "index"), 1)) return body;
        }
        return "";
    }

    private static String meta(String body, String key) {
        Matcher m = Pattern.compile("key=\"" + key + "\"\\s+value=\"([^\"]*)\"").matcher(body);
        return m.find() ? m.group(1) : "";
    }

    private static int num(String s, int d) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return d; }
    }

    // ------------------------------------------------------------ FTP

    private void open() throws IOException {
        control = MiniMqtt.openTls(host, 990, 8000, 30000);
        reader = new BufferedReader(new InputStreamReader(control.getInputStream(), StandardCharsets.UTF_8));
        writer = control.getOutputStream();
        expect(reply(), 220);
        int r = cmd("USER bblp");
        if (r == 331) expect(cmd("PASS " + accessCode), 230);
        else expect(r, 230);
        cmd("PBSZ 0");
        expect(cmd("PROT P"), 200);
        expect(cmd("TYPE I"), 200);
    }

    private void quit() {
        try { cmd("QUIT"); } catch (IOException ignored) { }
    }

    private void closeQuietly() {
        if (control != null) try { control.close(); } catch (IOException ignored) { }
        control = null;
    }

    /** LIST of a folder (long format, one line per entry). */
    private List<String> listLong(String dir) throws IOException {
        List<String> out = new ArrayList<>();
        Socket raw = openData();
        int r = cmd("LIST " + dir);
        if (r != 150 && r != 125) { raw.close(); return out; }
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(secure(raw), StandardCharsets.UTF_8));
            String line;
            while ((line = in.readLine()) != null) if (!line.trim().isEmpty()) out.add(line.trim());
        } finally {
            raw.close();
        }
        reply(); // 226
        return out;
    }

    private String findFile(String taskName, String gcodeFile) throws IOException {
        List<String> candidates = new ArrayList<>();
        if (gcodeFile != null && gcodeFile.toLowerCase(Locale.ROOT).endsWith(".3mf")) {
            String base = gcodeFile.substring(gcodeFile.lastIndexOf('/') + 1);
            candidates.add(gcodeFile.startsWith("/") ? gcodeFile : "/" + gcodeFile);
            candidates.add("/cache/" + base);
            candidates.add("/" + base);
        }
        if (taskName != null && !taskName.isEmpty()) {
            for (String dir : new String[]{"/cache/", "/"}) {
                candidates.add(dir + taskName + ".3mf");
                candidates.add(dir + taskName + ".gcode.3mf");
            }
        }
        for (String c : candidates) {
            if (cmd("SIZE " + c) == 213) return c;
        }
        // Fall back to listing the folders and matching on the job name.
        if (taskName != null && !taskName.isEmpty()) {
            for (String dir : new String[]{"/cache", "/"}) {
                for (String name : list(dir)) {
                    String base = name.substring(name.lastIndexOf('/') + 1);
                    if (base.startsWith(taskName) && base.toLowerCase(Locale.ROOT).endsWith(".3mf")) {
                        return (dir.endsWith("/") ? dir : dir + "/") + base;
                    }
                }
            }
        }
        return null;
    }

    private List<String> list(String dir) throws IOException {
        List<String> out = new ArrayList<>();
        Socket raw = openData();
        int r = cmd("NLST " + dir);
        if (r != 150 && r != 125) { raw.close(); return out; }
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(secure(raw), StandardCharsets.UTF_8));
            String line;
            while ((line = in.readLine()) != null) if (!line.trim().isEmpty()) out.add(line.trim());
        } finally {
            raw.close();
        }
        reply(); // 226
        return out;
    }

    private void download(String path, File dest) throws IOException {
        Socket raw = openData();
        int r = cmd("RETR " + path);
        if (r != 150 && r != 125) { raw.close(); throw new IOException("Download refused (" + r + ")"); }
        long total = 0;
        try (InputStream in = secure(raw); OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
                if (total > MAX_SIZE) throw new IOException("Job file too large");
            }
        } finally {
            raw.close();
        }
        int done = reply();
        if (done != 226 && done != 250) throw new IOException("Download failed (" + done + ")");
    }

    /** Opens the passive-mode data connection (plain TCP until {@link #secure} is called). */
    private Socket openData() throws IOException {
        String line = lastLine(sendLine("PASV"));
        Matcher m = Pattern.compile("(\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)").matcher(line);
        if (!line.startsWith("227") || !m.find()) throw new IOException("Passive mode refused");
        int port = Integer.parseInt(m.group(5)) * 256 + Integer.parseInt(m.group(6));
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(host, port), 8000);
        raw.setSoTimeout(30000);
        return raw;
    }

    /**
     * TLS on the data connection, done after the server accepted the transfer. The printer's
     * FTP server (vsftpd) only accepts a data connection that resumes the control connection's
     * TLS session. An SSLEngine created for host:990 from the same SSLContext looks that cached
     * session up, on desktop Java and on Android alike.
     */
    private InputStream secure(Socket raw) throws IOException {
        SSLEngine engine = MiniMqtt.lanContext().createSSLEngine(host, 990);
        engine.setUseClientMode(true);
        return new TlsInputStream(raw, engine);
    }

    private int cmd(String c) throws IOException {
        return code(lastLine(sendLine(c)));
    }

    private List<String> sendLine(String c) throws IOException {
        writer.write((c + "\r\n").getBytes(StandardCharsets.UTF_8));
        writer.flush();
        return replyLines();
    }

    private int reply() throws IOException {
        return code(lastLine(replyLines()));
    }

    private List<String> replyLines() throws IOException {
        List<String> lines = new ArrayList<>();
        String first = reader.readLine();
        if (first == null) throw new IOException("FTP connection closed");
        lines.add(first);
        if (first.length() > 3 && first.charAt(3) == '-') {
            String end = first.substring(0, 3) + " ";
            String l;
            while ((l = reader.readLine()) != null) {
                lines.add(l);
                if (l.startsWith(end)) break;
            }
        }
        return lines;
    }

    private static String lastLine(List<String> lines) {
        return lines.get(lines.size() - 1);
    }

    private static int code(String line) {
        try { return Integer.parseInt(line.substring(0, 3)); } catch (Exception e) { return 0; }
    }

    private static void expect(int got, int want) throws IOException {
        if (got == 530) throw new IOException("FTP login refused (check access code)");
        if (got != want) throw new IOException("Unexpected FTP reply " + got);
    }

    // ------------------------------------------------------------ 3MF

    static Result parse3mf(File file, int plate) throws IOException {
        Result r = new Result();
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry img = zip.getEntry("Metadata/plate_" + plate + ".png");
            if (img == null) img = zip.getEntry("Metadata/plate_1.png");
            if (img != null) r.image = readAll(zip.getInputStream(img));

            ZipEntry info = zip.getEntry("Metadata/slice_info.config");
            if (info != null) {
                String xml = new String(readAll(zip.getInputStream(info)), StandardCharsets.UTF_8);
                r.filaments = filamentsForPlate(xml, plate);
            }
        }
        return r;
    }

    static JSONArray filamentsForPlate(String xml, int plate) {
        JSONArray out = new JSONArray();
        Matcher plates = Pattern.compile("<plate>(.*?)</plate>", Pattern.DOTALL).matcher(xml);
        String chosen = null;
        while (plates.find()) {
            String body = plates.group(1);
            Matcher idx = Pattern.compile("key=\"index\"\\s+value=\"(\\d+)\"").matcher(body);
            int n = idx.find() ? Integer.parseInt(idx.group(1)) : 1;
            if (chosen == null || n == plate) chosen = body;
            if (n == plate) break;
        }
        if (chosen == null) return out;
        Matcher f = Pattern.compile("<filament\\s([^>]*)/?>").matcher(chosen);
        while (f.find()) {
            String attrs = f.group(1);
            try {
                JSONObject o = new JSONObject();
                o.put("id", attr(attrs, "id"));
                o.put("type", attr(attrs, "type"));
                o.put("color", attr(attrs, "color"));
                o.put("grams", parseD(attr(attrs, "used_g")));
                o.put("meters", parseD(attr(attrs, "used_m")));
                out.put(o);
            } catch (Exception ignored) { }
        }
        return out;
    }

    private static String attr(String attrs, String name) {
        Matcher m = Pattern.compile("(?:^|\\s)" + name + "=\"([^\"]*)\"").matcher(attrs);
        return m.find() ? m.group(1) : "";
    }

    private static double parseD(String s) {
        try { return Double.parseDouble(s); } catch (Exception e) { return 0; }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = i.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }
}
