package dev.printwatch;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;

/** Reads TLS-protected data from a plain socket using an SSLEngine (client side, read only). */
final class TlsInputStream extends InputStream {

    private static final ByteBuffer EMPTY = ByteBuffer.allocate(0);

    private final InputStream rawIn;
    private final OutputStream rawOut;
    private final SSLEngine engine;
    private ByteBuffer netIn;   // write mode: bytes received, not yet decrypted
    private ByteBuffer app;     // read mode: decrypted bytes waiting to be read
    private ByteBuffer netOut;
    private boolean closed;

    TlsInputStream(Socket raw, SSLEngine engine) throws IOException {
        this.rawIn = raw.getInputStream();
        this.rawOut = raw.getOutputStream();
        this.engine = engine;
        netIn = ByteBuffer.allocate(engine.getSession().getPacketBufferSize());
        netOut = ByteBuffer.allocate(engine.getSession().getPacketBufferSize());
        app = ByteBuffer.allocate(engine.getSession().getApplicationBufferSize());
        app.flip();
        engine.beginHandshake();
        handshake();
    }

    private void handshake() throws IOException {
        while (true) {
            switch (engine.getHandshakeStatus()) {
                case NEED_WRAP:
                    wrap();
                    break;
                case NEED_UNWRAP:
                    if (unwrap().getStatus() == SSLEngineResult.Status.CLOSED) throw new EOFException("TLS closed");
                    break;
                case NEED_TASK:
                    runTasks();
                    break;
                default: // FINISHED, NOT_HANDSHAKING
                    return;
            }
        }
    }

    private void wrap() throws IOException {
        while (true) {
            netOut.clear();
            SSLEngineResult r = engine.wrap(EMPTY, netOut);
            if (r.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                netOut = ByteBuffer.allocate(netOut.capacity() * 2);
                continue;
            }
            netOut.flip();
            rawOut.write(netOut.array(), 0, netOut.limit());
            rawOut.flush();
            if (r.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_TASK) runTasks();
            return;
        }
    }

    /** Decrypts at least one TLS record into {@link #app}, reading from the socket as needed. */
    private SSLEngineResult unwrap() throws IOException {
        while (true) {
            netIn.flip();
            app.compact();
            SSLEngineResult r;
            try {
                r = engine.unwrap(netIn, app);
            } finally {
                app.flip();
                netIn.compact();
            }
            switch (r.getStatus()) {
                case BUFFER_UNDERFLOW:
                    if (!netIn.hasRemaining()) netIn = grow(netIn);
                    int n = rawIn.read(netIn.array(), netIn.position(), netIn.remaining());
                    if (n < 0) {
                        closed = true;
                        return new SSLEngineResult(SSLEngineResult.Status.CLOSED,
                                SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING, 0, 0);
                    }
                    netIn.position(netIn.position() + n);
                    continue;
                case BUFFER_OVERFLOW:
                    ByteBuffer bigger = ByteBuffer.allocate(app.capacity() * 2);
                    bigger.put(app);
                    bigger.flip();
                    app = bigger;
                    continue;
                case CLOSED:
                    closed = true;
                    return r;
                default:
                    if (r.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_TASK) runTasks();
                    return r;
            }
        }
    }

    private static ByteBuffer grow(ByteBuffer b) {
        ByteBuffer bigger = ByteBuffer.allocate(b.capacity() * 2);
        b.flip();
        bigger.put(b);
        return bigger;
    }

    private void runTasks() {
        Runnable t;
        while ((t = engine.getDelegatedTask()) != null) t.run();
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) return 0;
        while (!app.hasRemaining()) {
            if (closed) return -1;
            try {
                unwrap();
                // TLS 1.3 can ask us to answer post-handshake messages
                if (engine.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_WRAP) wrap();
            } catch (SSLException e) {
                if (closed) return -1;
                throw e;
            }
        }
        int n = Math.min(len, app.remaining());
        app.get(b, off, n);
        return n;
    }

    @Override
    public void close() {
        closed = true;
        engine.closeOutbound();
    }
}
