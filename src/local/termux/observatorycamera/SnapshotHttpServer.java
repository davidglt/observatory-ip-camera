package local.termux.observatorycamera;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Bounded concurrent development server serving only the private snapshot, a read-only viewer page and an MJPEG stream.
 * It never opens the camera and never triggers capture: it only reads the atomically published snapshot file.
 * At most maxConnections sockets (one thread each) exist at once; at most maxStreams of them may be streams, so
 * streams can never occupy all slots needed by snapshot requests. Start and stop are idempotent.
 */
public final class SnapshotHttpServer {
    public static final int DEFAULT_PORT = 8080;
    public static final String BOUNDARY = "observatoryframe";
    private static final int MAX_HEADER = 8192;
    private static final int MAX_IMAGE = 16 * 1024 * 1024;
    private static final int MAX_STREAM_FRAME = 8 * 1024 * 1024;
    private static final long STOP_JOIN_MILLIS = 2000;
    private static final long POLL_MILLIS = 200;
    private static final String VIEWER = "<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">"
        + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>Observatory camera</title>"
        + "<style>body{margin:0;background:#111;color:#ccc;font-family:sans-serif}img{display:block;max-width:100%;height:auto;margin:0 auto}"
        + "p{margin:8px;font-size:small}</style></head><body><img src=\"/stream.mjpg\" alt=\"Live camera stream\">"
        + "<p>Read-only view. Frames appear only when the camera publishes a new image; if the stream ends, reload this page.</p></body></html>\n";
    private final File snapshot;
    private final int port;
    private final Consumer<String> log;
    private final Object lock = new Object();
    private Session session;
    private volatile Runnable onTerminated;
    private volatile int maxConnections = 8;
    private volatile int maxStreams = 2;
    private volatile long streamIdleMillis = 60000;
    private volatile long writeTimeoutMillis = 10000;

    public SnapshotHttpServer(File snapshot, Consumer<String> log) {
        this(snapshot, DEFAULT_PORT, log);
    }

    public SnapshotHttpServer(File snapshot, int port, Consumer<String> log) {
        this.snapshot = snapshot;
        this.port = port;
        this.log = log;
    }

    /** Test hook; applies to the next start(). */
    void setLimits(int connections, int streams, long idleMillis, long writeMillis) {
        maxConnections = connections;
        maxStreams = streams;
        streamIdleMillis = idleMillis;
        writeTimeoutMillis = writeMillis;
    }

    /** Called from the accept thread if it ends without stop() having been requested. */
    public void setOnTerminated(Runnable callback) { onTerminated = callback; }

    /** Binds synchronously so failures (such as an occupied port) are reported. Does nothing if already running. */
    public void start() throws IOException {
        synchronized (lock) {
            if (session != null && session.acceptor.isAlive()) return;
            ServerSocket socket = new ServerSocket();
            try {
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), port), 8);
            } catch (IOException | RuntimeException e) {
                closeQuietly(socket);
                throw e;
            }
            log.accept("Listening on 0.0.0.0:" + socket.getLocalPort() + "; endpoints: /, /snapshot.jpg, /stream.mjpg");
            Session created = new Session(socket, maxConnections, maxStreams, streamIdleMillis, writeTimeoutMillis);
            session = created;
            created.begin();
        }
    }

    /** Closes the listener and every client socket, then waits briefly for all threads to end. */
    public void stop() {
        Session current;
        synchronized (lock) {
            current = session;
            session = null;
        }
        if (current == null) return;
        current.shutdown();
        log.accept("HTTP server stopped");
    }

    public boolean isRunning() {
        synchronized (lock) { return session != null && session.acceptor.isAlive(); }
    }

    public int getLocalPort() {
        synchronized (lock) { return session == null ? -1 : session.listener.getLocalPort(); }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try { closeable.close(); } catch (IOException ignored) { }
    }

    /** One start()..stop() generation: its own listener, threads, connection set and counters. */
    private final class Session {
        final ServerSocket listener;
        final Thread acceptor;
        final Thread watchdog;
        final Set<Conn> conns = ConcurrentHashMap.newKeySet();
        final AtomicInteger streams = new AtomicInteger();
        final int connLimit, streamLimit;
        final long idleMillis, writeMillis;
        volatile boolean closing;

        Session(ServerSocket listener, int connLimit, int streamLimit, long idleMillis, long writeMillis) {
            this.listener = listener;
            this.connLimit = connLimit;
            this.streamLimit = streamLimit;
            this.idleMillis = idleMillis;
            this.writeMillis = writeMillis;
            acceptor = new Thread(this::acceptLoop, "snapshot-http");
            acceptor.setUncaughtExceptionHandler((t, error) -> log.accept("HTTP thread crashed: " + error));
            acceptor.setDaemon(true);
            watchdog = new Thread(this::watchLoop, "snapshot-http-watchdog");
            watchdog.setDaemon(true);
        }

        void begin() {
            watchdog.start();
            acceptor.start();
        }

        void shutdown() {
            closing = true;
            closeQuietly(listener);
            List<Conn> open = new ArrayList<>(conns);
            for (Conn c : open) closeQuietly(c.socket);
            if (acceptor != Thread.currentThread()) {
                long deadline = System.nanoTime() + STOP_JOIN_MILLIS * 1000000L;
                join(acceptor, deadline);
                join(watchdog, deadline);
                for (Conn c : open) join(c.thread, deadline);
            }
            watchdog.interrupt();
        }

        void join(Thread thread, long deadline) {
            if (thread == null) return;
            long millis = Math.max(1, (deadline - System.nanoTime()) / 1000000L);
            try { thread.join(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        void acceptLoop() {
            try {
                while (!closing && !listener.isClosed()) {
                    Socket client;
                    try {
                        client = listener.accept();
                    } catch (IOException e) {
                        if (closing || listener.isClosed()) break;
                        log.accept("Accept error: " + e);
                        continue;
                    }
                    if (closing) { closeQuietly(client); break; }
                    if (conns.size() >= connLimit) {
                        try { rejectBusy(client); } catch (IOException | RuntimeException ignored) { }
                        closeQuietly(client);
                        continue;
                    }
                    Conn conn = new Conn(client);
                    conns.add(conn);
                    try {
                        conn.thread.start();
                    } catch (Throwable t) {
                        conns.remove(conn);
                        closeQuietly(client);
                    }
                    if (closing) closeQuietly(client);
                }
            } finally {
                closeQuietly(listener);
                boolean deliberate = closing;
                Runnable callback = onTerminated;
                if (!deliberate && callback != null) callback.run();
            }
        }

        void rejectBusy(Socket client) throws IOException {
            byte[] body = bytes("Too many connections.\n");
            String header = "HTTP/1.1 503 Service Unavailable\r\nServer: ObservatoryNative/0.3\r\nContent-Type: text/plain\r\nContent-Length: "
                + body.length + "\r\nCache-Control: no-store\r\nRetry-After: 2\r\nConnection: close\r\n\r\n";
            OutputStream out = client.getOutputStream();
            out.write(header.getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
        }

        /** Closes sockets whose current write has not completed in time (slow or stalled readers). */
        void watchLoop() {
            try {
                while (!closing) {
                    Thread.sleep(100);
                    long now = System.nanoTime();
                    for (Conn c : conns) {
                        long deadline = c.writeDeadline;
                        if (deadline != 0 && now - deadline > 0) {
                            log.accept("Closing slow client: " + c.socket.getRemoteSocketAddress());
                            closeQuietly(c.socket);
                        }
                    }
                }
            } catch (InterruptedException ignored) { }
        }

        final class Conn {
            final Socket socket;
            final Thread thread;
            volatile long writeDeadline;

            Conn(Socket socket) {
                this.socket = socket;
                this.thread = new Thread(this::run, "snapshot-http-client");
                this.thread.setDaemon(true);
            }

            void run() {
                try {
                    handle(this);
                } catch (IOException | RuntimeException e) {
                    if (!closing) log.accept("Client error: " + e);
                } finally {
                    closeQuietly(socket);
                    conns.remove(this);
                }
            }

            void write(OutputStream out, byte[] data) throws IOException {
                writeDeadline = System.nanoTime() + writeMillis * 1000000L;
                try {
                    out.write(data);
                    out.flush();
                } finally {
                    writeDeadline = 0;
                }
            }
        }

        void handle(Conn conn) throws IOException {
            Socket client = conn.socket;
            InputStream input = client.getInputStream();
            OutputStream output = client.getOutputStream();
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            long deadline = System.nanoTime() + 5000000000L;
            int suffix = 0;
            boolean complete = false;
            for (int i = 0; i < MAX_HEADER; i++) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new SocketTimeoutException("Header deadline exceeded.");
                client.setSoTimeout((int) Math.max(1, remaining / 1000000L));
                int value = input.read();
                if (value == -1) return;
                header.write(value);
                suffix = (suffix << 8) | value;
                if (suffix == 0x0d0a0d0a) { complete = true; break; }
            }
            if (!complete) {
                reply(conn, output, 431, "Request Header Fields Too Large", "text/plain", bytes("Header too large.\n"), false);
                return;
            }
            String first = new String(header.toByteArray(), StandardCharsets.US_ASCII).split("\r\n", 2)[0];
            String[] parts = first.split(" ");
            if (parts.length != 3 || !(parts[2].equals("HTTP/1.0") || parts[2].equals("HTTP/1.1"))) {
                reply(conn, output, 400, "Bad Request", "text/plain", bytes("Invalid request.\n"), false);
                return;
            }
            boolean head = parts[0].equals("HEAD");
            if (!head && !parts[0].equals("GET")) {
                reply(conn, output, 405, "Method Not Allowed", "text/plain", bytes("Use GET or HEAD.\n"), false);
                return;
            }
            String path = parts[1].split("\\?", 2)[0];
            if (path.equals("/snapshot.jpg")) {
                serveSnapshot(conn, output, head, parts[0]);
            } else if (path.equals("/")) {
                reply(conn, output, 200, "OK", "text/html; charset=utf-8", bytes(VIEWER), head,
                    "Content-Security-Policy: default-src 'none'; img-src 'self'; style-src 'unsafe-inline'\r\n");
            } else if (path.equals("/stream.mjpg")) {
                serveStream(conn, input, output, head);
            } else {
                reply(conn, output, 404, "Not Found", "text/plain", bytes("Available: /, /snapshot.jpg, /stream.mjpg.\n"), head);
            }
        }

        void serveSnapshot(Conn conn, OutputStream output, boolean head, String method) throws IOException {
            byte[] image;
            try {
                image = readImage(MAX_IMAGE);
            } catch (FileNotFoundException e) {
                reply(conn, output, 404, "Not Found", "text/plain", bytes("No snapshot available. Capture an image first.\n"), head);
                return;
            } catch (IOException e) {
                reply(conn, output, 503, "Service Unavailable", "text/plain", bytes("Snapshot unavailable.\n"), head);
                return;
            }
            reply(conn, output, 200, "OK", "image/jpeg", image, head);
            log.accept("Snapshot response: 200, " + image.length + " bytes, method=" + method);
        }

        /**
         * Behavior: headers are sent at once; a frame is sent only when the snapshot file was republished after the previous frame
         * (or, for the first frame, after the client connected). Nothing is repeated. If no new publication arrives within
         * the idle limit (capture stopped, failed or slower than the limit) the stream ends with the closing boundary.
         */
        void serveStream(Conn conn, InputStream input, OutputStream output, boolean head) throws IOException {
            String headers = "HTTP/1.1 200 OK\r\nServer: ObservatoryNative/0.3\r\nContent-Type: multipart/x-mixed-replace; boundary=" + BOUNDARY
                + "\r\nCache-Control: no-store, no-cache, must-revalidate, max-age=0\r\nPragma: no-cache\r\nExpires: 0\r\nX-Content-Type-Options: nosniff\r\nAllow: GET, HEAD\r\nConnection: close\r\n\r\n";
            if (head) {
                conn.write(output, headers.getBytes(StandardCharsets.US_ASCII));
                return;
            }
            if (streams.incrementAndGet() > streamLimit) {
                streams.decrementAndGet();
                rejectBusy(conn.socket);
                return;
            }
            try {
                log.accept("Stream client connected: " + conn.socket.getRemoteSocketAddress());
                conn.write(output, headers.getBytes(StandardCharsets.US_ASCII));
                String seen = signature();
                long lastNew = System.nanoTime();
                while (!closing) {
                    String current = signature();
                    if (current != null && !current.equals(seen)) {
                        byte[] frame = null;
                        try { frame = readImage(MAX_STREAM_FRAME); } catch (IOException ignored) { }
                        String after = signature();
                        if (after != null && !current.equals(after)) continue; // republished while reading; take the newer file
                        seen = current;
                        if (frame != null) {
                            String part = "--" + BOUNDARY + "\r\nContent-Type: image/jpeg\r\nContent-Length: " + frame.length + "\r\n\r\n";
                            conn.write(output, part.getBytes(StandardCharsets.US_ASCII));
                            conn.write(output, frame);
                            conn.write(output, new byte[] {'\r', '\n'});
                            lastNew = System.nanoTime();
                        }
                    }
                    if ((System.nanoTime() - lastNew) / 1000000L >= idleMillis) {
                        conn.write(output, ("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII));
                        log.accept("Stream ended: no new frame for " + idleMillis + " ms");
                        return;
                    }
                    try {
                        conn.socket.setSoTimeout((int) POLL_MILLIS);
                        if (input.read() == -1) return; // client disconnected
                    } catch (SocketTimeoutException poll) { }
                }
            } finally {
                streams.decrementAndGet();
                log.accept("Stream client finished");
            }
        }

        /** Identity of the currently published file: a rename publication changes the file key even if the size and time match. */
        String signature() {
            try {
                BasicFileAttributes a = Files.readAttributes(snapshot.toPath(), BasicFileAttributes.class);
                return a.fileKey() + "/" + a.lastModifiedTime().toMillis() + "/" + a.size();
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }

        byte[] readImage(int limit) throws IOException {
            try (FileInputStream source = new FileInputStream(snapshot)) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] block = new byte[8192];
                int count;
                while ((count = source.read(block)) != -1) {
                    if (buffer.size() + count > limit) throw new IOException("Snapshot exceeds size limit.");
                    buffer.write(block, 0, count);
                }
                byte[] image = buffer.toByteArray();
                if (image.length < 4 || image[0] != (byte) 0xff || image[1] != (byte) 0xd8 || image[image.length - 2] != (byte) 0xff || image[image.length - 1] != (byte) 0xd9) throw new IOException("Snapshot is incomplete.");
                return image;
            }
        }
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private void reply(Session.Conn conn, OutputStream output, int code, String reason, String type, byte[] body, boolean head) throws IOException {
        reply(conn, output, code, reason, type, body, head, "");
    }

    private void reply(Session.Conn conn, OutputStream output, int code, String reason, String type, byte[] body, boolean head, String extra) throws IOException {
        String header = "HTTP/1.1 " + code + " " + reason + "\r\nServer: ObservatoryNative/0.3\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length + "\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n" + extra + "Allow: GET, HEAD\r\nConnection: close\r\n\r\n";
        conn.write(output, header.getBytes(StandardCharsets.US_ASCII));
        if (!head) conn.write(output, body);
    }
}
