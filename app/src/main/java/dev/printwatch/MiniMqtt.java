package dev.printwatch;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * A minimal MQTT 3.1.1 client over TLS: just enough to talk to a Bambu Lab printer
 * (connect, subscribe, publish QoS 0, ping). No external libraries.
 */
public final class MiniMqtt {

    public interface Listener {
        void onMessage(String topic, byte[] payload);
    }

    private final SSLSocket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private int nextPacketId = 1;

    private MiniMqtt(SSLSocket socket) throws IOException {
        this.socket = socket;
        this.in = new DataInputStream(socket.getInputStream());
        this.out = socket.getOutputStream();
    }

    private static SSLContext context;

    /**
     * Bambu printers use a self-signed certificate, so we accept any certificate.
     * This only ever connects to the IP addresses you enter, on your own network.
     * One shared factory, so TLS sessions can be resumed (the printer's FTP server requires that).
     */
    static SSLSocketFactory lanSocketFactory() {
        return lanContext().getSocketFactory();
    }

    static synchronized SSLContext lanContext() {
        if (context != null) return context;
        try {
            TrustManager[] trustAll = new TrustManager[]{new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] c, String a) { }
                @Override public void checkServerTrusted(X509Certificate[] c, String a) { }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, trustAll, new SecureRandom());
            context = ctx;
            return context;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static SSLSocket openTls(String host, int port, int connectTimeoutMs, int readTimeoutMs) throws IOException {
        Socket raw = new Socket();
        try {
            raw.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            raw.setSoTimeout(readTimeoutMs);
            raw.setTcpNoDelay(true);
            SSLSocket s = (SSLSocket) lanSocketFactory().createSocket(raw, host, port, true);
            s.setSoTimeout(readTimeoutMs);
            s.startHandshake();
            return s;
        } catch (IOException e) {
            try { raw.close(); } catch (IOException ignored) { }
            throw e;
        }
    }

    public static MiniMqtt connect(String host, int port, String clientId, String user, String password,
                                   int keepAliveSec) throws IOException {
        SSLSocket s = openTls(host, port, 8000, keepAliveSec * 3 * 1000);
        MiniMqtt m = new MiniMqtt(s);
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeString(body, "MQTT");
            body.write(4);            // protocol level 3.1.1
            body.write(0xC2);         // username + password + clean session
            body.write(keepAliveSec >> 8);
            body.write(keepAliveSec & 0xFF);
            writeString(body, clientId);
            writeString(body, user);
            writeString(body, password);
            m.send(0x10, body.toByteArray());

            int type = m.in.readUnsignedByte();
            int len = m.readRemainingLength();
            byte[] ack = new byte[len];
            m.in.readFully(ack);
            if ((type >> 4) != 2 || len < 2) throw new IOException("Unexpected reply from printer");
            int rc = ack[1] & 0xFF;
            if (rc == 4 || rc == 5) throw new IOException("Wrong access code");
            if (rc != 0) throw new IOException("Printer refused connection (code " + rc + ")");
            return m;
        } catch (IOException e) {
            m.close();
            throw e;
        }
    }

    public synchronized void subscribe(String topic) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int id = nextId();
        body.write(id >> 8);
        body.write(id & 0xFF);
        writeString(body, topic);
        body.write(0); // QoS 0
        send(0x82, body.toByteArray());
    }

    public synchronized void publish(String topic, String payload) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeString(body, topic);
        byte[] p = payload.getBytes(StandardCharsets.UTF_8);
        body.write(p, 0, p.length);
        send(0x30, body.toByteArray());
    }

    public synchronized void ping() throws IOException {
        send(0xC0, new byte[0]);
    }

    /** Blocks, delivering messages until the connection fails or is closed. */
    public void readLoop(Listener listener) throws IOException {
        while (true) {
            int header = in.readUnsignedByte();
            int len = readRemainingLength();
            byte[] body = new byte[len];
            in.readFully(body);
            int type = header >> 4;
            if (type == 3) { // PUBLISH
                int qos = (header >> 1) & 3;
                int topicLen = ((body[0] & 0xFF) << 8) | (body[1] & 0xFF);
                String topic = new String(body, 2, topicLen, StandardCharsets.UTF_8);
                int pos = 2 + topicLen;
                if (qos > 0) {
                    int pid = ((body[pos] & 0xFF) << 8) | (body[pos + 1] & 0xFF);
                    pos += 2;
                    if (qos == 1) {
                        synchronized (this) { send(0x40, new byte[]{(byte) (pid >> 8), (byte) pid}); }
                    }
                }
                byte[] payload = new byte[len - pos];
                System.arraycopy(body, pos, payload, 0, payload.length);
                listener.onMessage(topic, payload);
            }
            // SUBACK (9), PINGRESP (13) etc. need no handling.
        }
    }

    public void close() {
        try { socket.close(); } catch (IOException ignored) { }
    }

    private int nextId() {
        int id = nextPacketId;
        nextPacketId = nextPacketId >= 65535 ? 1 : nextPacketId + 1;
        return id;
    }

    private void send(int header, byte[] body) throws IOException {
        ByteArrayOutputStream pkt = new ByteArrayOutputStream(body.length + 5);
        pkt.write(header);
        int len = body.length;
        do {
            int b = len % 128;
            len /= 128;
            if (len > 0) b |= 0x80;
            pkt.write(b);
        } while (len > 0);
        pkt.write(body, 0, body.length);
        out.write(pkt.toByteArray());
        out.flush();
    }

    private int readRemainingLength() throws IOException {
        int value = 0, multiplier = 1, b;
        do {
            b = in.read();
            if (b < 0) throw new EOFException();
            value += (b & 0x7F) * multiplier;
            multiplier *= 128;
            if (multiplier > 128 * 128 * 128 * 128) throw new IOException("Malformed packet");
        } while ((b & 0x80) != 0);
        return value;
    }

    private static void writeString(ByteArrayOutputStream o, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        o.write(b.length >> 8);
        o.write(b.length & 0xFF);
        o.write(b, 0, b.length);
    }
}
