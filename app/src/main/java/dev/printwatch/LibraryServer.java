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
 * A tiny web server on the home network (port 8080) so the filament library can be edited from a
 * computer's browser. It serves the same page in "remote" mode; reads and changes are handed to the
 * dashboard running on the tablet, which stays the only place the library is changed.
 * Only accepts connections from private (home network) addresses.
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
    }

    private final Handler handler;
    private volatile ServerSocket server;

    LibraryServer(Handler handler) {
        this.handler = handler;
    }

    synchronized void start() {
        if (server != null) return;
        try {
            final ServerSocket s = new ServerSocket(PORT);
            server = s;
            Thread t = new Thread(() -> acceptLoop(s), "library-server");
            t.setDaemon(true);
            t.start();
        } catch (IOException e) {
            server = null;
        }
    }

    synchronized void stop() {
        ServerSocket s = server;
        server = null;
        if (s != null) try { s.close(); } catch (IOException ignored) { }
    }

    boolean running() { return server != null; }

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
            if (!(from.isSiteLocalAddress() || from.isLoopbackAddress() || from.isLinkLocalAddress())) return;
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

    private void route(Socket sock, String method, String path, String body) throws IOException {
        try {
            if (method.equals("GET") && path.equals("/api/library")) {
                respond(sock, 200, "application/json", bytes(handler.getLibrary()));
            } else if (method.equals("POST") && path.equals("/api/op")) {
                respond(sock, 200, "application/json", bytes(handler.applyOp(body)));
            } else if (method.equals("GET")) {
                if (path.equals("/")) path = "/index.html";
                String name = path.substring(1);
                if (name.contains("..") || name.contains("/") || name.startsWith("data-")) {
                    respond(sock, 404, "text/plain", bytes("Not found"));
                    return;
                }
                byte[] data = handler.asset(name);
                if (data == null) { respond(sock, 404, "text/plain", bytes("Not found")); return; }
                if (name.equals("index.html")) {
                    String html = new String(data, StandardCharsets.UTF_8)
                            .replace("<script src=\"filament.js\">", "<script>window.PW_REMOTE=true</script><script src=\"filament.js\">");
                    data = bytes(html);
                }
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
