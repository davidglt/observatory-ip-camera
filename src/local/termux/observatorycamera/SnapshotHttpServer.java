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

/** A bounded, sequential development server serving only the private snapshot. */
public final class SnapshotHttpServer implements Runnable {
    private static final int MAX_HEADER = 8192;
    private static final int MAX_IMAGE = 16 * 1024 * 1024;
    private final File snapshot;
    private final Consumer<String> log;

    public SnapshotHttpServer(File snapshot, Consumer<String> log) {
        this.snapshot = snapshot;
        this.log = log;
    }

    @Override public void run() {
        try (ServerSocket listener = new ServerSocket()) {
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), 8080), 8);
            log.accept("Listening on 0.0.0.0:8080; endpoint: /snapshot.jpg");
            while (!Thread.currentThread().isInterrupted()) {
                try (Socket client = listener.accept()) {
                    client.setSoTimeout(5000);
                    handle(client);
                } catch (IOException e) {
                    log.accept("Client error: " + e.getClass().getSimpleName());
                }
            }
        } catch (IOException e) {
            log.accept("HTTP server stopped: " + e);
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
