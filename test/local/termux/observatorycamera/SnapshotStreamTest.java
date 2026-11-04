/*
 * Copyright (C) 2026 David González López-Tercero
 * SPDX-License-Identifier: GPL-3.0-or-later
 * See LICENSE in the repository root for licensing details.
 */

package local.termux.observatorycamera;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;

/** Dependency-free tests for /stream.mjpg, the viewer page, limits and lifecycle. Run: bash test/run.sh */
public final class SnapshotStreamTest {
    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }

    private static byte[] jpeg(int shade) throws IOException {
        BufferedImage image = new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 32; x++) for (int y = 0; y < 24; y++) image.setRGB(x, y, (shade * 40 % 256) << 16 | (x * 8) << 8 | y * 10);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    private static void publish(File dir, byte[] data) throws IOException {
        check(SnapshotPublisher.publish(dir, "snapshot.jpg", data, () -> true), "publish");
    }

    private static Socket open(int port, String method, String path) throws IOException {
        Socket s = new Socket(InetAddress.getByName("127.0.0.1"), port);
        s.setSoTimeout(5000);
        s.getOutputStream().write((method + " " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        return s;
    }

    private static byte[] readAll(Socket s) throws IOException {
        try {
            InputStream in = s.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) != -1) out.write(b, 0, n);
            return out.toByteArray();
        } finally { s.close(); }
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int prev = -1, c;
        while ((c = in.read()) != -1) {
            if (prev == '\r' && c == '\n') return new String(out.toByteArray(), 0, out.size() - 1, StandardCharsets.US_ASCII);
            out.write(c);
            prev = c;
        }
        return null;
    }

    private static String readHeaders(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        String l;
        while ((l = line(in)) != null && !l.isEmpty()) sb.append(l).append('\n');
        return sb.toString();
    }

    /** Reads one multipart part and validates framing; returns the JPEG, or null on the closing boundary / EOF. */
    private static byte[] readFrame(InputStream in) throws IOException {
        String delimiter = line(in);
        if (delimiter == null) return null;
        if (delimiter.equals("--" + SnapshotHttpServer.BOUNDARY + "--")) return null;
        check(delimiter.equals("--" + SnapshotHttpServer.BOUNDARY), "boundary line");
        String type = line(in), length = line(in);
        check("Content-Type: image/jpeg".equals(type), "part content type");
        check(length != null && length.startsWith("Content-Length: "), "part content length");
        check(line(in).isEmpty(), "blank line after part headers");
        byte[] data = new byte[Integer.parseInt(length.substring(16))];
        int got = 0;
        while (got < data.length) {
            int n = in.read(data, got, data.length - got);
            check(n > 0, "frame body complete");
            got += n;
        }
        check(in.read() == '\r' && in.read() == '\n', "CRLF after frame body");
        return data;
    }

    private static boolean decodes(byte[] data) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(data)) != null;
    }

    private static boolean noData(Socket s, int millis) throws IOException {
        s.setSoTimeout(millis);
        try { return s.getInputStream().read() == -2; } catch (SocketTimeoutException expected) { return true; }
    }

    private static boolean closed(Socket s) {
        try { s.setSoTimeout(3000); return s.getInputStream().read() == -1; } catch (IOException e) { return true; }
    }

    private static String status(byte[] response) {
        return new String(response, 0, Math.min(response.length, 40), StandardCharsets.US_ASCII);
    }

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("stream-test").toFile();
        File image = new File(dir, "snapshot.jpg");
        SnapshotHttpServer server = null;
        List<Socket> sockets = new ArrayList<>();
        try {
            server = new SnapshotHttpServer(image, 0, m -> { });
            server.setLimits(6, 2, 1500, 1000);
            server.start();
            int port = server.getLocalPort();
            byte[] first = jpeg(1), second = jpeg(2), third = jpeg(3);
            publish(dir, first); // existing snapshot before the client connects: must not be replayed

            // Headers, no-cache, first-frame behavior and framing/JPEG integrity.
            Socket stream = open(port, "GET", "/stream.mjpg");
            sockets.add(stream);
            InputStream in = stream.getInputStream();
            String headers = readHeaders(in);
            check(headers.startsWith("HTTP/1.1 200 OK"), "stream 200");
            check(headers.contains("Content-Type: multipart/x-mixed-replace; boundary=" + SnapshotHttpServer.BOUNDARY), "multipart content type");
            check(headers.contains("Cache-Control: no-store, no-cache"), "no-cache header");
            check(headers.contains("Pragma: no-cache"), "pragma header");
            check(noData(stream, 500), "no frame before first new publication");
            stream.setSoTimeout(5000);
            publish(dir, second);
            byte[] f = readFrame(in);
            check(Arrays.equals(f, second) && decodes(f), "first frame is the newly published complete JPEG");
            check(noData(stream, 600), "unchanged snapshot is not repeated");
            publish(dir, third);
            stream.setSoTimeout(5000);
            f = readFrame(in);
            check(Arrays.equals(f, third) && decodes(f), "next frame");
            // Idle: stream ends cleanly with the closing boundary, no stale frame.
            stream.setSoTimeout(5000);
            check(readFrame(in) == null, "closing boundary after idle");
            check(closed(stream), "socket closed after idle end");
            stream.close();

            // Concurrent snapshot clients while streams and stalled readers occupy slots.
            Socket s1 = open(port, "GET", "/stream.mjpg"), s2 = open(port, "GET", "/stream.mjpg");
            sockets.add(s1); sockets.add(s2);
            readHeaders(s1.getInputStream());
            readHeaders(s2.getInputStream());
            Socket s3 = open(port, "GET", "/stream.mjpg");
            sockets.add(s3);
            check(status(readAll(s3)).contains("503"), "third stream rejected at the stream limit");
            s3.close();
            for (int i = 0; i < 4; i++) {
                byte[] resp = readAll(open(port, "GET", "/snapshot.jpg"));
                int body = new String(resp, StandardCharsets.ISO_8859_1).indexOf("\r\n\r\n") + 4;
                check(status(resp).contains("200") && Arrays.equals(Arrays.copyOfRange(resp, body, resp.length), third), "snapshot beside streams " + i);
            }
            byte[] viewer = readAll(open(port, "GET", "/"));
            String page = new String(viewer, StandardCharsets.UTF_8);
            check(page.contains("200 OK") && page.contains("text/html") && page.contains("src=\"/stream.mjpg\""), "viewer page");
            check(page.contains("Settings") && page.contains("Authorization") && page.contains("<script nonce=")
                && !page.contains("localStorage") && !page.contains("sessionStorage"), "viewer has in-memory authenticated settings only");
            check(page.contains("grid-template-columns:repeat(2,minmax(0,1fr))")
                && page.contains("#viewer[data-telemetry-position=left]{grid-template-columns:260px minmax(0,1fr)")
                && page.contains("@media(max-width:800px){#viewer,#viewer[data-telemetry-position]{grid-template-columns:minmax(0,1fr);grid-template-areas:'image' 'telemetry'}}")
                && page.contains("@media(max-width:600px)") && page.contains("grid-template-columns:minmax(0,1fr)")
                && page.contains("id=\"telemetry\"") && page.contains("setInterval(updateTelemetry,2000)"),
                "telemetry panel and settings adapt without refreshing the image");
            check(page.contains("name=\"visible_field\" value=\"location\"")
                && page.contains("name=\"visible_field\" value=\"datetime\"")
                && page.contains("name=\"visible_field\" value=\"fps\"")
                && page.contains("name=\"visible_field\" value=\"temperature\"")
                && page.contains("name=\"visible_field\" value=\"humidity\"")
                && page.contains("name=\"visible_field\" value=\"pressure\"")
                && page.contains("name=\"visible_field\" value=\"battery\"")
                && page.contains("name=\"visible_field\" value=\"battery_temperature\"")
                && page.contains("name=\"visible_field\" value=\"charging\"")
                && page.contains("name=\"visible_field\" value=\"thermal\"")
                && !page.contains("type=\"checkbox\" name=\"visible_field\" value=\"frames\""),
                "visible overlay fields are individually selectable without a frame counter");
            check(page.contains("name=\"panel_environment\"") && page.contains("name=\"panel_device\"")
                && page.contains("name=\"panel_capture\"") && page.contains("name=\"mqtt_host\"")
                && page.contains("name=\"mqtt_tls\"") && !page.contains("name=\"mqtt_password\""),
                "panel groups and non-secret MQTT settings are configurable");
            check(page.contains("result.textContent='Saved.'") && !page.contains("Saved atomically.")
                && page.contains("p.set('fields',visible.length?visible.join(','):'none')"),
                "save acknowledges briefly and explicitly supports no fields");
            check(page.indexOf("id=\"settings-button\"") < page.indexOf("<img src=\"/stream.mjpg\"")
                && !page.contains("src=\"http") && !page.contains("https://"), "settings button is outside pixels and assets are self-contained");
            check(status(readAll(open(port, "POST", "/stream.mjpg"))).contains("405"), "POST stream rejected");
            // Two streams receive the same publication independently (no per-client camera/capture).
            publish(dir, first);
            check(Arrays.equals(readFrame(s1.getInputStream()), first) && Arrays.equals(readFrame(s2.getInputStream()), first), "both streams get the frame");
            // Client disconnect frees its slot.
            s1.close();
            Thread.sleep(300);
            publish(dir, second);
            check(Arrays.equals(readFrame(s2.getInputStream()), second), "remaining stream continues");
            Socket again = open(port, "GET", "/stream.mjpg");
            sockets.add(again);
            check(readHeaders(again.getInputStream()).startsWith("HTTP/1.1 200"), "slot reusable after disconnect");
            again.close();
            s2.close();

            // Connection cap: stalled header-less sockets fill all slots; extra connection gets 503 or is closed.
            List<Socket> idle = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                Socket socket = new Socket(InetAddress.getByName("127.0.0.1"), port);
                idle.add(socket); sockets.add(socket);
            }
            Thread.sleep(300);
            Socket extra = new Socket(InetAddress.getByName("127.0.0.1"), port);
            sockets.add(extra);
            extra.setSoTimeout(3000);
            check(status(readAll(extra)).contains("503"), "connection limit returns 503");
            extra.close();
            for (Socket s : idle) s.close();
            Thread.sleep(300);
            check(status(readAll(open(port, "GET", "/snapshot.jpg"))).contains("200"), "capacity recovers after clients leave");

            // Slow writer: a stream client that never reads is dropped and does not block a snapshot client.
            server.stop();
            byte[] big = new byte[6 * 1024 * 1024];
            Arrays.fill(big, (byte) 7);
            big[0] = (byte) 0xff; big[1] = (byte) 0xd8; big[big.length - 2] = (byte) 0xff; big[big.length - 1] = (byte) 0xd9;
            server.setLimits(6, 2, 60000, 700);
            server.start();
            port = server.getLocalPort();
            Socket slow = open(port, "GET", "/stream.mjpg");
            sockets.add(slow);
            slow.setReceiveBufferSize(1024);
            readHeaders(slow.getInputStream());
            publish(dir, big);
            Thread.sleep(300);
            check(status(readAll(open(port, "GET", "/snapshot.jpg"))).contains("200"), "snapshot served while a slow stream is blocked");
            Thread.sleep(1500);
            // After the write timeout the server closed the slow socket: draining ends in EOF/reset without a complete frame.
            long drained = 0;
            long drainDeadline = System.nanoTime() + 8_000_000_000L;
            try {
                InputStream si = slow.getInputStream();
                byte[] b = new byte[65536];
                int n;
                while (System.nanoTime() < drainDeadline) {
                    slow.setSoTimeout((int) Math.max(1, (drainDeadline - System.nanoTime()) / 1_000_000L));
                    n = si.read(b);
                    if (n == -1) break;
                    drained += n;
                }
            } catch (IOException reset) { }
            check(drained < big.length, "slow client was cut off before the whole frame");
            slow.close();

            // Stop with open streams and restart leaves no listener, threads or stale state.
            Socket live = open(port, "GET", "/stream.mjpg");
            sockets.add(live);
            readHeaders(live.getInputStream());
            long begin = System.nanoTime();
            server.stop();
            check((System.nanoTime() - begin) < 2500000000L, "stop returns promptly with an open stream");
            check(closed(live), "stream closed by stop");
            live.close();
            publish(dir, first);
            for (int i = 0; i < 3; i++) {
                server.start();
                int p = server.getLocalPort();
                Socket st = open(p, "GET", "/stream.mjpg");
                sockets.add(st);
                readHeaders(st.getInputStream());
                publish(dir, i % 2 == 0 ? second : third);
                check(decodes(readFrame(st.getInputStream())), "frame after restart " + i);
                server.stop();
                check(closed(st), "stream closed on stop " + i);
                st.close();
            }
            int threads = 0;
            for (Thread t : Thread.getAllStackTraces().keySet()) if (t.getName().startsWith("snapshot-http") && t.isAlive()) threads++;
            check(threads == 0, "no server threads left after stop: " + threads);
        } finally {
            if (server != null) server.stop();
            for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) { }
            image.delete();
            new File(dir, "snapshot.tmp").delete();
            dir.delete();
        }
        System.out.println("SnapshotStreamTest passed");
    }
}
