package dev.printwatch;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;

/**
 * A tiny web server (port 8080) for phones and computers:
 *   /         the dashboard (cameras, progress, AMS), when sharing the dashboard is switched on
 *   /library  the filament library, when sharing the library is switched on
 * Reads and changes go through the tablet, which stays the only place that talks to the printers
 * and changes the library. Printer controls need the PIN set on the tablet.
 * Only accepts connections from the home network and from Tailscale (100.64.0.0/10, fd7a:115c:a1e0::/48).
 */
final class LibraryServer {

    static final int PORT = 8080;

    interface Handler {
        /** Returns the asset bytes for a path like "app.js", or null. */
        byte[] asset(String path) throws IOException;
        /** GET /api/library: the library as JSON (from the dashboard). */
        String getLibrary() throws Exception;
        /** POST /api/op: applies one change on the tablet; returns JSON. */
        String applyOp(String json) throws Exception;
        /** GET /api/state: printer names and their latest status (no addresses or access codes). */
        String state() throws Exception;
        /** Latest camera picture / job preview of a printer, or null. */
        byte[] camera(int index);
        byte[] model(int index);
        /** Runs a printer control (already checked against the PIN). */
        void command(int index, String action, String value);
        boolean shareLibrary();
        boolean shareDashboard();
        /** PIN for printer controls; empty means controls are not allowed from other devices. */
        String pin();
    }

    private static final java.util.Set<String> REMOTE_ACTIONS = new java.util.HashSet<>(java.util.Arrays.asList(
            "light", "pause", "resume", "stop", "speed", "nozzle", "bed", "fan_part", "fan_aux", "fan_chamber"));
    private int badPins;
    private long lockedUntil;

    private final Handler handler;
    private volatile ServerSocket server;

    LibraryServer(Handler handler) {
        this.handler = handler;
    }

    private volatile boolean wanted;

    /** Starts listening (in the background: network calls are not allowed on the main thread). */
    synchronized void start() {
        if (wanted) return;
        wanted = true;
        Thread t = new Thread(() -> {
            try {
                ServerSocket s = new ServerSocket(PORT);
                synchronized (LibraryServer.this) {
                    if (!wanted) { s.close(); return; }
                    server = s;
                }
                acceptLoop(s);
            } catch (IOException e) {
                synchronized (LibraryServer.this) { wanted = false; server = null; }
            }
        }, "library-server");
        t.setDaemon(true);
        t.start();
    }

    synchronized void stop() {
        wanted = false;
        final ServerSocket s = server;
        server = null;
        if (s != null) new Thread(() -> { try { s.close(); } catch (IOException ignored) { } }).start();
    }

    boolean running() { return server != null; }

    /** All addresses to reach the tablet: home network first, then Tailscale (100.x). */
    static java.util.List<String> addresses() {
        java.util.List<String> home = new java.util.ArrayList<>(), vpn = new java.util.ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address)) continue;
                    String url = "http://" + a.getHostAddress() + ":" + PORT;
                    if (a.isSiteLocalAddress()) home.add(url); else if (allowed(a) && !a.isLinkLocalAddress()) vpn.add(url);
                }
            }
        } catch (Exception ignored) { }
        home.addAll(vpn);
        return home;
    }

    /** e.g. "http://192.168.1.23:8080", or "" when the tablet has no network address. */
    static String address() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        return "http://" + a.getHostAddress() + ":" + PORT;
                    }
                }
            }
        } catch (Exception ignored) { }
        return "";
    }

    private void acceptLoop(ServerSocket s) {
        while (server == s) {
            try {
                final Socket c = s.accept();
                Thread t = new Thread(() -> handle(c), "library-client");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (server != s) return;
            }
        }
    }

    private void handle(Socket c) {
        try (Socket sock = c) {
            InetAddress from = sock.getInetAddress();
            if (!allowed(from)) return;
            sock.setSoTimeout(15000);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            String requestLine = readLine(in);
            if (requestLine == null) return;
            int contentLength = 0;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("content-length")) {
                    contentLength = Integer.parseInt(line.substring(colon + 1).trim());
                }
            }
            if (contentLength > 4 * 1024 * 1024) { respond(sock, 413, "text/plain", bytes("Too large")); return; }
            byte[] body = new byte[contentLength];
            int off = 0;
            while (off < contentLength) {
                int n = in.read(body, off, contentLength - off);
                if (n < 0) break;
                off += n;
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String path = parts[1];
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            route(sock, method, path, new String(body, 0, off, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // connection problems are not interesting
        }
    }

    /** Home network (private ranges), Tailscale, link-local and the tablet itself. */
    static boolean allowed(InetAddress a) {
        if (a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()) return true;
        byte[] b = a.getAddress();
        if (b.length == 4) return (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64;      // 100.64.0.0/10
        return b.length == 16 && (b[0] & 0xFE) == 0xFC;                             // fc00::/7 (incl. Tailscale)
    }

    private void route(Socket sock, String method, String path, String body) throws IOException {
        try {
            boolean dash = handler.shareDashboard(), lib = handler.shareLibrary();
            if (method.equals("GET") && path.equals("/api/library") && (lib || dash)) {
                respond(sock, 200, "application/json", bytes(handler.getLibrary()));
            } else if (method.equals("POST") && path.equals("/api/op") && lib) {
                respond(sock, 200, "application/json", bytes(handler.applyOp(body)));
            } else if (method.equals("GET") && path.equals("/api/state") && dash) {
                respond(sock, 200, "application/json", bytes(handler.state()));
            } else if (method.equals("POST") && path.equals("/api/command") && dash) {
                respond(sock, 200, "application/json", bytes(remoteCommand(body)));
            } else if (method.equals("GET") && dash && (path.startsWith("/cam/") || path.startsWith("/model/"))) {
                int idx = -1;
                try { idx = Integer.parseInt(path.replaceAll("^/(cam|model)/(\\d+)\\..*$", "$2")); } catch (NumberFormatException ignored) { }
                byte[] img = path.startsWith("/cam/") ? handler.camera(idx) : handler.model(idx);
                if (img == null) respond(sock, 404, "text/plain", bytes("No picture"));
                else respond(sock, 200, path.startsWith("/cam/") ? "image/jpeg" : "image/png", img);
            } else if (method.equals("GET")) {
                String flag = null;
                if (path.equals("/") && dash) flag = "window.PW_REMOTE_DASH=true";
                else if (path.equals("/") || path.equals("/library")) flag = lib ? "window.PW_REMOTE=true" : null;
                if (path.equals("/") || path.equals("/library")) {
                    if (flag == null) {
                        respond(sock, 404, "text/html", bytes("<p style='font:18px sans-serif'>Sharing is switched off in PrintWatch on the tablet.</p>"));
                        return;
                    }
                    String html = new String(handler.asset("index.html"), StandardCharsets.UTF_8)
                            .replace("<script src=\"filament.js\">", "<script>" + flag + "</script><script src=\"filament.js\">");
                    respond(sock, 200, "text/html", bytes(html));
                    return;
                }
                String name = path.substring(1);
                if (name.contains("..") || name.contains("/") || name.startsWith("data-") || name.equals("index.html")) {
                    respond(sock, 404, "text/plain", bytes("Not found"));
                    return;
                }
                byte[] data = handler.asset(name);
                if (data == null) { respond(sock, 404, "text/plain", bytes("Not found")); return; }
                respond(sock, 200, mime(name), data);
            } else {
                respond(sock, 405, "text/plain", bytes("Method not allowed"));
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            respond(sock, 503, "text/plain", bytes("PrintWatch on the tablet is not ready: " + e.getMessage()));
        }
    }

    /** {"pin":"1234","index":0,"action":"light","value":"on"} */
    private String remoteCommand(String body) throws Exception {
        String pin = handler.pin();
        if (pin == null || pin.isEmpty()) return "{\"ok\":false,\"error\":\"Controls from other devices are switched off on the tablet\"}";
        synchronized (this) {
            if (System.currentTimeMillis() < lockedUntil) return "{\"ok\":false,\"error\":\"Too many wrong PINs, try again in a few minutes\"}";
            org.json.JSONObject j = new org.json.JSONObject(body);
            if (!pin.equals(j.optString("pin"))) {
                if (++badPins >= 5) { lockedUntil = System.currentTimeMillis() + 5 * 60000; badPins = 0; }
                return "{\"ok\":false,\"error\":\"Wrong PIN\",\"badPin\":true}";
            }
            badPins = 0;
            String action = j.optString("action");
            if (!REMOTE_ACTIONS.contains(action)) return "{\"ok\":false,\"error\":\"Not allowed\"}";
            handler.command(j.optInt("index", -1), action, j.optString("value", ""));
            return "{\"ok\":true}";
        }
    }

    private static void respond(Socket sock, int code, String type, byte[] body) throws IOException {
        String status = code == 200 ? "OK" : code == 404 ? "Not Found" : code == 503 ? "Service Unavailable" : "Error";
        String head = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: " + type + (type.startsWith("text/") || type.contains("json") || type.contains("javascript") ? "; charset=utf-8" : "") + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream out = sock.getOutputStream();
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static String mime(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".html")) return "text/html";
        if (n.endsWith(".js")) return "application/javascript";
        if (n.endsWith(".css")) return "text/css";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".svg")) return "image/svg+xml";
        return "text/plain";
    }

    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') break;
            if (b != '\r') bo.write(b);
            if (bo.size() > 8192) throw new IOException("Line too long");
        }
        if (b < 0 && bo.size() == 0) return null;
        return new String(bo.toByteArray(), StandardCharsets.ISO_8859_1);
    }
}
