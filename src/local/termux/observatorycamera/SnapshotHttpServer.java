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
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/** A bounded, sequential development server serving only the private snapshot. Start and stop are idempotent. */
public final class SnapshotHttpServer {
    public static final int DEFAULT_PORT = 8080;
    private static final int MAX_HEADER = 8192;
    private static final int MAX_IMAGE = 16 * 1024 * 1024;
    private static final long STOP_JOIN_MILLIS = 2000;
    private final File snapshot;
    private final int port;
    private final Consumer<String> log;
    private final Object lock = new Object();
    private ServerSocket listener;
    private Thread worker;
    private volatile Socket active;
    private volatile Runnable onTerminated;

    public SnapshotHttpServer(File snapshot, Consumer<String> log) {
        this(snapshot, DEFAULT_PORT, log);
    }

    public SnapshotHttpServer(File snapshot, int port, Consumer<String> log) {
        this.snapshot = snapshot;
        this.port = port;
        this.log = log;
    }

    /** Called from the worker thread if it ends without stop() having been requested. */
    public void setOnTerminated(Runnable callback) { onTerminated = callback; }

    /** Binds synchronously so failures (such as an occupied port) are reported. Does nothing if already running. */
    public void start() throws IOException {
        synchronized (lock) {
            if (worker != null && worker.isAlive()) return;
            ServerSocket socket = new ServerSocket();
            try {
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), port), 8);
            } catch (IOException | RuntimeException e) {
                closeQuietly(socket);
                throw e;
            }
            log.accept("Listening on 0.0.0.0:" + socket.getLocalPort() + "; endpoint: /snapshot.jpg");
            Thread thread = new Thread(() -> serve(socket), "snapshot-http");
            thread.setUncaughtExceptionHandler((t, error) -> log.accept("HTTP thread crashed: " + error));
            thread.setDaemon(true);
            listener = socket;
            worker = thread;
            thread.start();
        }
    }

    /** Closes the listener and any active client socket, then waits briefly for the worker to end. */
    public void stop() {
        ServerSocket socket;
        Thread thread;
        synchronized (lock) {
            socket = listener;
            thread = worker;
            listener = null;
            worker = null;
        }
        if (socket == null) return;
        closeQuietly(socket);
        closeQuietly(active);
        thread.interrupt();
        if (thread != Thread.currentThread()) {
            try { thread.join(STOP_JOIN_MILLIS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        log.accept("HTTP server stopped");
    }

    public boolean isRunning() {
        synchronized (lock) { return worker != null && worker.isAlive(); }
    }

    public int getLocalPort() {
        synchronized (lock) { return listener == null ? -1 : listener.getLocalPort(); }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try { closeable.close(); } catch (IOException ignored) { }
    }

    private void serve(ServerSocket socket) {
        try {
            while (!socket.isClosed() && !Thread.currentThread().isInterrupted()) {
                log.accept("Waiting for client");
                try (Socket client = socket.accept()) {
                    active = client;
                    if (socket.isClosed()) break;
                    log.accept("Accepted: " + client.getRemoteSocketAddress());
                    client.setSoTimeout(5000);
                    handle(client);
                    log.accept("Handler finished; closing client");
                } catch (IOException | RuntimeException e) {
                    if (socket.isClosed()) break;
                    log.accept("Client error: " + e);
                } finally {
                    active = null;
                }
            }
        } finally {
            closeQuietly(socket);
            boolean deliberate;
            synchronized (lock) { deliberate = listener != socket; }
            Runnable callback = onTerminated;
            if (!deliberate && callback != null) callback.run();
        }
    }

    private void handle(Socket client) throws IOException {
        InputStream input = client.getInputStream();
        OutputStream output = client.getOutputStream();
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        long deadline = System.nanoTime() + 5000000000L;
        int suffix = 0;
        boolean complete = false;
        for (int i = 0; i < MAX_HEADER; i++) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new java.net.SocketTimeoutException("Header deadline exceeded.");
            client.setSoTimeout((int) Math.max(1, remaining / 1000000L));
            int value = input.read();
            if (value == -1) return;
            header.write(value);
            suffix = (suffix << 8) | value;
            if (suffix == 0x0d0a0d0a) { complete = true; break; }
        }
        if (!complete) {
            reply(output, 431, "Request Header Fields Too Large", "text/plain", bytes("Header too large.\n"), false);
            return;
        }
        String first = new String(header.toByteArray(), StandardCharsets.US_ASCII).split("\r\n", 2)[0];
        String[] parts = first.split(" ");
        if (parts.length != 3 || !(parts[2].equals("HTTP/1.0") || parts[2].equals("HTTP/1.1"))) {
            reply(output, 400, "Bad Request", "text/plain", bytes("Invalid request.\n"), false);
            return;
        }
        boolean head = parts[0].equals("HEAD");
        if (!head && !parts[0].equals("GET")) {
            reply(output, 405, "Method Not Allowed", "text/plain", bytes("Use GET or HEAD.\n"), false);
            return;
        }
        if (!parts[1].split("\\?", 2)[0].equals("/snapshot.jpg")) {
            reply(output, 404, "Not Found", "text/plain", bytes("Only /snapshot.jpg is available.\n"), head);
            return;
        }
        byte[] image;
        try (FileInputStream source = new FileInputStream(snapshot)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] block = new byte[8192];
            int count;
            while ((count = source.read(block)) != -1) {
                if (buffer.size() + count > MAX_IMAGE) throw new IOException("Snapshot exceeds size limit.");
                buffer.write(block, 0, count);
            }
            image = buffer.toByteArray();
            if (image.length < 4 || image[0] != (byte) 0xff || image[1] != (byte) 0xd8 || image[image.length - 2] != (byte) 0xff || image[image.length - 1] != (byte) 0xd9) throw new IOException("Snapshot is incomplete.");
        } catch (FileNotFoundException e) {
            reply(output, 404, "Not Found", "text/plain", bytes("No snapshot available. Capture an image first.\n"), head);
            return;
        } catch (IOException e) {
            reply(output, 503, "Service Unavailable", "text/plain", bytes("Snapshot unavailable.\n"), head);
            return;
        }
        reply(output, 200, "OK", "image/jpeg", image, head);
        log.accept("Snapshot response: 200, " + image.length + " bytes, method=" + parts[0]);
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private static void reply(OutputStream output, int code, String reason, String type, byte[] body, boolean head) throws IOException {
        String header = "HTTP/1.1 " + code + " " + reason + "\r\nServer: ObservatoryNative/0.3\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length + "\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nAllow: GET, HEAD\r\nConnection: close\r\n\r\n";
        output.write(header.getBytes(StandardCharsets.US_ASCII));
        if (!head) output.write(body);
        output.flush();
    }
}
