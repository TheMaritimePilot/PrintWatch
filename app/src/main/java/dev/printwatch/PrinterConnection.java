package dev.printwatch;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Random;

import javax.net.ssl.SSLSocket;

/**
 * Connects to one Bambu Lab printer (P1S / P1P / X1 / A1) in LAN mode:
 *  - MQTT over TLS on port 8883 for live status
 *  - the chamber camera JPEG stream over TLS on port 6000 (P1 / A1 series)
 *  - FTPS on port 990 to fetch the job's preview image and filament usage
 * Reconnects automatically. Pure Java + org.json, no Android classes.
 */
public final class PrinterConnection {

    public interface Callback {
        /** Called on a background thread with the full merged state as JSON. */
        void onState(int index, String json);
        /** Called on a background thread when a new camera frame is available. */
        void onFrame(int index);
    }

    public static final class Config {
        public String name = "";
        public String host = "";
        public String serial = "";
        public String accessCode = "";
        public boolean camera = true;
        /** Light on when a print starts; flash 5 times and off when it ends. */
        public boolean lightAuto = true;

        public static Config fromJson(JSONObject o) {
            Config c = new Config();
            c.name = o.optString("name", "").trim();
            c.host = o.optString("host", "").trim();
            c.serial = o.optString("serial", "").trim().toUpperCase();
            c.accessCode = o.optString("code", "").trim();
            c.camera = o.optBoolean("camera", true);
            return c;
        }
    }

    private static final int KEEPALIVE_SEC = 30;
    private static final long PUSHALL_INTERVAL_MS = 10 * 60 * 1000;

    private final int index;
    private final Config cfg;
    private final Callback callback;
    private final File cacheDir;

    private volatile boolean running;
    private volatile MiniMqtt mqtt;
    private volatile SSLSocket camSocket;
    private Thread mqttThread, pingThread, camThread;

    private final Object stateLock = new Object();
    private final JSONObject print = new JSONObject();
    private String status = "connecting";
    private String error = "";
    private String camStatus = "off";
    private String camError = "";
    private String lastGcode = null; // to notice a print starting or ending

    private volatile byte[] latestFrame;

    // job preview (from the .3mf on the SD card)
    private volatile byte[] modelImage;
    private JSONArray modelFilaments = new JSONArray();
    private String modelKey = "";
    private String modelStatus = "none";
    private int modelAttempts;
    private long modelRetryAt;
    private boolean modelFetching;

    public PrinterConnection(int index, Config cfg, Callback callback, File cacheDir) {
        this.index = index;
        this.cfg = cfg;
        this.callback = callback;
        this.cacheDir = cacheDir;
    }

    public byte[] latestFrame() { return latestFrame; }
    public byte[] modelImage() { return modelImage; }

    public synchronized void start() {
        if (running) return;
        running = true;
        if (cfg.host.isEmpty() || cfg.serial.isEmpty() || cfg.accessCode.isEmpty()) {
            setStatus("offline", "Printer is not configured yet");
            return;
        }
        mqttThread = new Thread(this::mqttLoop, "mqtt-" + index);
        mqttThread.setDaemon(true);
        mqttThread.start();
        pingThread = new Thread(this::pingLoop, "ping-" + index);
        pingThread.setDaemon(true);
        pingThread.start();
        if (cfg.camera) {
            camThread = new Thread(this::cameraLoop, "cam-" + index);
            camThread.setDaemon(true);
            camThread.start();
        }
    }

    public synchronized void stop() {
        running = false;
        final MiniMqtt m = mqtt;
        final SSLSocket s = camSocket;
        for (Thread t : new Thread[]{mqttThread, pingThread, camThread}) if (t != null) t.interrupt();
        // Closing a TLS socket sends data, which Android forbids on the screen's main thread
        // (it kills the app), so close them in the background.
        Thread closer = new Thread(() -> {
            if (m != null) m.close();
            if (s != null) try { s.close(); } catch (IOException ignored) { }
        }, "close-" + index);
        closer.setDaemon(true);
        closer.start();
    }

    /**
     * Printer controls. Note: on recent firmware the printer only accepts commands from
     * third-party apps when "LAN Only" and "Developer Mode" are enabled on the printer.
     */
    public void command(String action, String value) {
        switch (action) {
            case "light": setChamberLight("on".equals(value)); break;
            case "pause": printCommand("pause", null); break;
            case "resume": printCommand("resume", null); break;
            case "stop": printCommand("stop", null); break;
            case "speed": printCommand("print_speed", String.valueOf(clamp(value, 1, 4))); break;
            case "nozzle": gcode("M104 S" + clamp(value, 0, 300)); break;
            case "bed": gcode("M140 S" + clamp(value, 0, 110)); break;
            case "fan_part": gcode("M106 P1 S" + fanPwm(value)); break;
            case "fan_aux": gcode("M106 P2 S" + fanPwm(value)); break;
            case "fan_chamber": gcode("M106 P3 S" + fanPwm(value)); break;
            case "filament": setFilament(value); break;
            case "print_file": printFile(value); break;
            default: break;
        }
    }

    /**
     * Tells the printer which filament is in a slot (what Bambu Studio does when you edit a slot).
     * value: {"ams":0..3 or 255, "tray":0..3 or 254, "type":"PETG", "color":"RRGGBB", "idx":"GFG99", "min":220, "max":260}
     */
    private void setFilament(String value) {
        try {
            JSONObject v = new JSONObject(value);
            int ams = v.getInt("ams"), tray = v.getInt("tray");
            boolean external = ams == 255 && tray == 254;
            if (!external && (ams < 0 || ams > 3 || tray < 0 || tray > 3)) return;
            String color = v.optString("color", "FFFFFF").replaceAll("[^0-9A-Fa-f]", "").toUpperCase();
            if (color.length() == 6) color += "FF";
            if (color.length() != 8) color = "FFFFFFFF";
            String type = v.optString("type", "PLA").replaceAll("[^A-Za-z0-9+ -]", "");
            if (type.isEmpty()) type = "PLA";
            String idx = v.optString("idx", "GFL99").replaceAll("[^A-Za-z0-9]", "");
            int min = Math.max(0, Math.min(350, v.optInt("min", 190)));
            int max = Math.max(min, Math.min(350, v.optInt("max", 230)));
            JSONObject p = new JSONObject();
            p.put("sequence_id", seq());
            p.put("command", "ams_filament_setting");
            p.put("ams_id", ams);
            p.put("tray_id", tray);
            if (external) p.put("slot_id", 0); // Bambu Studio sends this for the external spool
            p.put("tray_info_idx", idx);
            p.put("tray_color", color);
            p.put("nozzle_temp_min", min);
            p.put("nozzle_temp_max", max);
            p.put("tray_type", type);
            send(new JSONObject().put("print", p).toString());
        } catch (JSONException ignored) {
            // malformed request from the page: ignore
        }
    }

    /**
     * Starts printing a sliced .3mf from the SD card.
     * value: {"path":"cache/name.3mf", "plate":1, "useAms":true, "mapping":[0,-1,2],
     *         "bedLevel":true, "timelapse":false, "flowCali":false}
     */
    private void printFile(String value) {
        try {
            JSONObject v = new JSONObject(value);
            String path = v.getString("path");
            if (path.isEmpty() || path.contains("..") || path.startsWith("/") || !path.toLowerCase().endsWith(".3mf")
                    || !path.matches("[^\\x00-\\x1f\"\\\\]+")) return;
            int plate = Math.max(1, Math.min(64, v.optInt("plate", 1)));
            boolean useAms = v.optBoolean("useAms", false);
            JSONArray mapping = new JSONArray();
            JSONArray in = v.optJSONArray("mapping");
            for (int i = 0; in != null && i < in.length() && i < 32; i++) {
                int t = in.optInt(i, -1);
                mapping.put(t >= 0 && t <= 15 ? t : -1);
            }
            if (!useAms || mapping.length() == 0) mapping = new JSONArray().put(0);
            boolean bedLevel = v.optBoolean("bedLevel", true), flow = v.optBoolean("flowCali", false);
            String name = path.substring(path.lastIndexOf('/') + 1).replaceAll("(?i)(\\.gcode)?\\.3mf$", "");
            JSONObject p = new JSONObject();
            p.put("sequence_id", seq());
            p.put("command", "project_file");
            p.put("param", "Metadata/plate_" + plate + ".gcode");
            p.put("url", "file:///sdcard/" + path);
            p.put("bed_type", "auto");
            p.put("timelapse", v.optBoolean("timelapse", false));
            p.put("bed_leveling", bedLevel);
            p.put("auto_bed_leveling", bedLevel ? 1 : 0);
            p.put("flow_cali", flow);
            p.put("extrude_cali_flag", flow ? 1 : 0);
            p.put("vibration_cali", false);
            p.put("layer_inspect", false);
            p.put("use_ams", useAms);
            p.put("ams_mapping", mapping);
            p.put("subtask_name", name);
            p.put("profile_id", "0");
            p.put("project_id", "0");
            p.put("subtask_id", "0");
            p.put("task_id", "0");
            send(new JSONObject().put("print", p).toString());
        } catch (JSONException ignored) {
            // malformed request from the page: ignore
        }
    }

    /** The print files on this printer's SD card (blocking: call from a background thread). */
    public JSONArray listFiles() throws java.io.IOException {
        return new ModelFetcher(cfg.host, cfg.accessCode).listPrintFiles();
    }

    /** Plates, previews and filament use of a print file (blocking). */
    public JSONObject projectInfo(String path, File thumbDir, String key) throws Exception {
        return new ModelFetcher(cfg.host, cfg.accessCode).projectInfo(path, cacheDir, thumbDir, key);
    }

    private void printCommand(String cmd, String param) {
        String p = param == null ? "" : ",\"param\":\"" + param + "\"";
        send("{\"print\":{\"sequence_id\":\"" + seq() + "\",\"command\":\"" + cmd + "\"" + p + "}}");
    }

    private void gcode(String line) {
        printCommand("gcode_line", line + " \\n");
    }

    private static int clamp(String v, int min, int max) {
        int n;
        try { n = Math.round(Float.parseFloat(v)); } catch (Exception e) { n = min; }
        return Math.max(min, Math.min(max, n));
    }

    private static int fanPwm(String percent) {
        return Math.round(clamp(percent, 0, 100) * 255 / 100f);
    }

    private static boolean busy(String g) {
        return "PREPARE".equals(g) || "RUNNING".equals(g) || "PAUSE".equals(g) || "SLICING".equals(g);
    }

    /** Chamber light automation: on when a print starts; 5 flashes and off when it ends. */
    private void onJobChange(String before, String after) {
        if (!cfg.lightAuto) return;
        if (!busy(before) && ("PREPARE".equals(after) || "RUNNING".equals(after))) {
            setChamberLight(true);
        } else if (busy(before) && ("FINISH".equals(after) || "FAILED".equals(after))) {
            Thread t = new Thread(() -> {
                try {
                    for (int i = 0; i < 5 && running; i++) {
                        setChamberLight(false);
                        Thread.sleep(600);
                        setChamberLight(true);
                        Thread.sleep(600);
                    }
                } catch (InterruptedException ignored) {
                    // stopping: still switch the light off below
                }
                setChamberLight(false);
            }, "light-" + index);
            t.setDaemon(true);
            t.start();
        }
    }

    /** Turn the chamber light on or off. */
    public void setChamberLight(boolean on) {
        send("{\"system\":{\"sequence_id\":\"" + seq() + "\",\"command\":\"ledctrl\",\"led_node\":\"chamber_light\","
                + "\"led_mode\":\"" + (on ? "on" : "off") + "\",\"led_on_time\":500,\"led_off_time\":500,"
                + "\"loop_times\":0,\"interval_time\":0}}");
    }

    /** Re-send the latest state (e.g. after the screen was reloaded). */
    public void reemit() { emit(); }

    // ---------------------------------------------------------------- MQTT

    private void mqttLoop() {
        long backoff = 2000;
        String clientId = "printwatch-" + Integer.toHexString(new Random().nextInt());
        while (running) {
            try {
                setStatus("connecting", "");
                MiniMqtt m = MiniMqtt.connect(cfg.host, 8883, clientId, "bblp", cfg.accessCode, KEEPALIVE_SEC);
                mqtt = m;
                if (!running) { m.close(); break; }
                m.subscribe("device/" + cfg.serial + "/report");
                requestFullStatus();
                setStatus("online", "");
                backoff = 2000;
                m.readLoop(this::onMessage);
            } catch (Exception e) {
                if (!running) break;
                setStatus("offline", describe(e));
            } finally {
                MiniMqtt m = mqtt;
                mqtt = null;
                if (m != null) m.close();
            }
            sleep(backoff);
            backoff = Math.min(backoff * 2, 30000);
        }
    }

    private void pingLoop() {
        long lastPushAll = System.currentTimeMillis();
        while (running) {
            sleep(KEEPALIVE_SEC * 1000L);
            MiniMqtt m = mqtt;
            if (m == null) continue;
            try {
                m.ping();
                if (System.currentTimeMillis() - lastPushAll > PUSHALL_INTERVAL_MS) {
                    requestFullStatus();
                    lastPushAll = System.currentTimeMillis();
                }
            } catch (IOException e) {
                m.close(); // the read loop notices and reconnects
            }
        }
    }

    private void requestFullStatus() {
        send("{\"pushing\":{\"sequence_id\":\"" + seq() + "\",\"command\":\"pushall\",\"version\":1,\"push_target\":1}}");
    }

    private void send(String json) {
        MiniMqtt m = mqtt;
        if (m == null) return;
        try {
            m.publish("device/" + cfg.serial + "/request", json);
        } catch (IOException e) {
            m.close();
        }
    }

    private void onMessage(String topic, byte[] payload) {
        try {
            JSONObject msg = new JSONObject(new String(payload, StandardCharsets.UTF_8));
            JSONObject p = msg.optJSONObject("print");
            if (p == null) return;
            String cmd = p.optString("command", "push_status");
            if (!"push_status".equals(cmd)) return;
            String before, after;
            synchronized (stateLock) {
                before = lastGcode;
                merge(print, p);
                after = print.optString("gcode_state", "");
                if (!after.isEmpty()) lastGcode = after;
            }
            if (before != null && !after.isEmpty() && !after.equals(before)) onJobChange(before, after);
            maybeFetchModel();
            emit();
        } catch (JSONException ignored) {
            // ignore malformed messages
        }
    }

    /**
     * P1 printers only send what changed, so we merge every update into one full state.
     * Arrays of objects with an "id" (AMS units, trays) are merged item by item.
     */
    static void merge(JSONObject target, JSONObject src) throws JSONException {
        Iterator<String> keys = src.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            Object v = src.get(k);
            Object old = target.opt(k);
            if (v instanceof JSONObject && old instanceof JSONObject) {
                merge((JSONObject) old, (JSONObject) v);
            } else if (v instanceof JSONArray && old instanceof JSONArray && isIdArray((JSONArray) v)
                    && isIdArray((JSONArray) old)) {
                // Update items by id; items the update doesn't mention are kept (partial updates).
                JSONArray a = (JSONArray) old, b = (JSONArray) v;
                for (int i = 0; i < b.length(); i++) {
                    JSONObject nb = b.getJSONObject(i);
                    JSONObject match = null;
                    for (int j = 0; j < a.length(); j++) {
                        JSONObject oa = a.getJSONObject(j);
                        if (oa.optString("id").equals(nb.optString("id"))) { match = oa; break; }
                    }
                    if (match != null) merge(match, nb); else a.put(nb);
                }
            } else {
                target.put(k, v);
            }
        }
    }

    private static boolean isIdArray(JSONArray a) {
        if (a.length() == 0) return false;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null || !o.has("id")) return false;
        }
        return true;
    }

    // ----------------------------------------------------------- Job preview

    private void maybeFetchModel() {
        final String task, gfile, key;
        synchronized (stateLock) {
            String state = print.optString("gcode_state", "");
            task = print.optString("subtask_name", "");
            gfile = print.optString("gcode_file", "");
            boolean job = state.equals("PREPARE") || state.equals("RUNNING") || state.equals("PAUSE")
                    || state.equals("FINISH") || state.equals("FAILED");
            if (!job || task.isEmpty() || modelFetching) return;
            key = task + "|" + gfile;
            if (key.equals(modelKey)) {
                if (!modelStatus.startsWith("error") || modelAttempts >= 3
                        || System.currentTimeMillis() < modelRetryAt) return;
            } else {
                modelKey = key;
                modelAttempts = 0;
                modelImage = null;
                modelFilaments = new JSONArray();
            }
            modelFetching = true;
            modelAttempts++;
            modelStatus = "loading";
        }
        Thread t = new Thread(() -> {
            String status;
            ModelFetcher.Result r = null;
            try {
                r = new ModelFetcher(cfg.host, cfg.accessCode).fetch(task, gfile, cacheDir);
                status = "ok";
            } catch (Exception e) {
                status = "error: " + describe(e);
            }
            synchronized (stateLock) {
                modelFetching = false;
                if (!key.equals(modelKey)) return; // a new job started meanwhile
                modelStatus = status;
                modelRetryAt = System.currentTimeMillis() + 120000;
                if (r != null) {
                    modelImage = r.image;
                    modelFilaments = r.filaments;
                }
            }
            emit();
        }, "model-" + index);
        t.setDaemon(true);
        t.start();
    }

    // -------------------------------------------------------------- Camera

    private void cameraLoop() {
        long backoff = 2000;
        while (running) {
            SSLSocket s = null;
            String step = "connect";
            boolean gotFrame = false;
            try {
                setCamStatus("connecting");
                s = MiniMqtt.openTls(cfg.host, 6000, 8000, 30000);
                step = "auth";
                camSocket = s;
                OutputStream out = s.getOutputStream();
                out.write(cameraAuthPacket(cfg.accessCode));
                out.flush();
                DataInputStream in = new DataInputStream(s.getInputStream());
                byte[] header = new byte[16];
                while (running) {
                    in.readFully(header);
                    int size = (header[0] & 0xFF) | (header[1] & 0xFF) << 8
                            | (header[2] & 0xFF) << 16 | (header[3] & 0xFF) << 24;
                    if (size <= 0 || size > 8 * 1024 * 1024) throw new IOException("Bad camera frame");
                    byte[] jpeg = new byte[size];
                    in.readFully(jpeg);
                    if ((jpeg[0] & 0xFF) == 0xFF && (jpeg[1] & 0xFF) == 0xD8) {
                        latestFrame = jpeg;
                        gotFrame = true;
                        if (!"online".equals(camStatus)) setCamStatus("online");
                        backoff = 2000;
                        callback.onFrame(index);
                    }
                }
            } catch (Exception e) {
                if (!running) break;
                setCamStatus("offline", describeCamera(e, step, gotFrame));
            } finally {
                camSocket = null;
                if (s != null) try { s.close(); } catch (IOException ignored) { }
            }
            sleep(backoff);
            backoff = Math.min(backoff * 2, 30000);
        }
    }

    static byte[] cameraAuthPacket(String accessCode) {
        byte[] p = new byte[80];
        putIntLE(p, 0, 0x40);
        putIntLE(p, 4, 0x3000);
        byte[] user = "bblp".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(user, 0, p, 16, user.length);
        byte[] code = accessCode.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(code, 0, p, 48, Math.min(code.length, 32));
        return p;
    }

    private static void putIntLE(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16);
        b[off + 3] = (byte) (v >> 24);
    }

    // ---------------------------------------------------------------- State

    private void setStatus(String s, String err) {
        synchronized (stateLock) {
            status = s;
            error = err == null ? "" : err;
        }
        emit();
    }

    private void setCamStatus(String s) {
        setCamStatus(s, "");
    }

    private void setCamStatus(String s, String error) {
        synchronized (stateLock) {
            camStatus = s;
            if (!"connecting".equals(s)) camError = error; // keep the last reason while retrying
        }
        emit();
    }

    /** A human readable reason, so camera problems can be diagnosed from the screen. */
    static String describeCamera(Exception e, String step, boolean gotFrame) {
        String m = String.valueOf(e.getMessage());
        if ("connect".equals(step)) {
            // The P1/A1 camera serves one viewer at a time.
            String busy = " \u2014 is the camera open in Bambu Studio or Handy? Close it there and it reconnects here.";
            if (e instanceof SocketTimeoutException) return "Camera not available" + busy;
            if (m.contains("ECONNREFUSED") || m.contains("refused")) return "Camera not available" + busy;
            if (e instanceof javax.net.ssl.SSLException) return "Camera TLS handshake failed: " + m;
            return "Camera connection failed: " + m;
        }
        if (gotFrame) {
            if (e instanceof SocketTimeoutException) return "Camera stopped sending pictures";
            return "Camera stream interrupted: " + m;
        }
        if (e instanceof SocketTimeoutException) return "Camera connected but sends no picture";
        if (e instanceof java.io.EOFException || m.contains("reset") || m.contains("closed"))
            return "Printer closed the camera connection" + " \u2014 is the camera open in Bambu Studio or Handy?";
        return "Camera error: " + m;
    }

    private void emit() {
        String json;
        synchronized (stateLock) {
            JSONObject o = new JSONObject();
            try {
                o.put("status", status);
                o.put("error", error);
                o.put("camera", cfg.camera ? camStatus : "off");
                o.put("cameraError", cfg.camera ? camError : "");
                o.put("print", print);
                JSONObject model = new JSONObject();
                model.put("key", modelKey);
                model.put("status", modelStatus);
                model.put("image", modelImage != null);
                model.put("filaments", modelFilaments);
                o.put("model", model);
            } catch (JSONException ignored) { }
            json = o.toString();
        }
        callback.onState(index, json);
    }

    private static String describe(Exception e) {
        if (e instanceof UnknownHostException) return "Unknown address";
        if (e instanceof SocketTimeoutException) return "Printer not responding";
        String m = e.getMessage();
        if (m == null) return e.getClass().getSimpleName();
        if (m.contains("ECONNREFUSED") || m.contains("Connection refused")) return "Connection refused (is LAN mode on?)";
        if (m.contains("EHOSTUNREACH") || m.contains("No route")) return "Printer not reachable";
        if (m.contains("ENETUNREACH")) return "No network";
        return m;
    }

    private static int seqCounter = 1;
    private static synchronized String seq() { return String.valueOf(seqCounter++); }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
