package local.termux.observatorycamera;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Dependency-free lifecycle test for the HTTP server. Run: bash test/run.sh */
public final class SnapshotHttpServerTest {
    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }

    private static byte[] request(int port, String method, String path) throws IOException {
        return request(port, method, path, "", new byte[0]);
    }

    private static byte[] request(int port, String method, String path, String headers, byte[] body) throws IOException {
        try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), port)) {
            s.setSoTimeout(5000);
            String hostHeader = headers.toLowerCase(java.util.Locale.ROOT).contains("\r\nhost:") || headers.startsWith("Host:")
                ? "" : "Host: 127.0.0.1:" + port + "\r\n";
            s.getOutputStream().write((method + " " + path + " HTTP/1.1\r\n" + hostHeader + headers + "\r\n").getBytes("US-ASCII"));
            if (body.length > 0) s.getOutputStream().write(body);
            InputStream in = s.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) != -1) out.write(b, 0, n);
            return out.toByteArray();
        }
    }

    private static int indexOf(byte[] data, String marker) {
        byte[] m = marker.getBytes();
        for (int i = 0; i + m.length <= data.length; i++) if (Arrays.equals(Arrays.copyOfRange(data, i, i + m.length), m)) return i;
        return -1;
    }

    private static boolean refused(int port) {
        try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), port)) { return false; }
        catch (IOException e) { return true; }
    }

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("snapshot-test").toFile();
        File image = new File(dir, "snapshot.jpg");
        List<SnapshotHttpServer> servers = new ArrayList<>();
        List<Socket> sockets = new ArrayList<>();
        byte[] jpeg = new byte[20000];
        for (int i = 0; i < jpeg.length; i++) jpeg[i] = (byte) (i * 31);
        jpeg[0] = (byte) 0xff; jpeg[1] = (byte) 0xd8; jpeg[jpeg.length - 2] = (byte) 0xff; jpeg[jpeg.length - 1] = (byte) 0xd9;
        try {
            AdminTokenStore tokenStore = new AdminTokenStore(dir);
            String token = tokenStore.provision();
            SnapshotHttpServer server = new SnapshotHttpServer(image, 0, m -> { }, tokenStore,
                (method, path, body) -> new SnapshotHttpServer.ApiResult(200, "OK", "{\"ok\":true}"),
                () -> new SnapshotHttpServer.ApiResult(200, "OK",
                    "{\"environment_state\":\"disabled\",\"telemetry_position\":\"left\"}"));
            servers.add(server);
            check(!server.isRunning(), "not running before start");
            server.stop(); // stop before start is safe

            server.start();
            int port = server.getLocalPort();
            check(server.isRunning() && port > 0, "running after start");
            server.start(); // repeated start: no second listener
            check(server.getLocalPort() == port, "repeated start keeps one listener");

            check(indexOf(request(port, "GET", "/snapshot.jpg"), "404 Not Found") > 0, "404 before first snapshot");
            Files.write(image.toPath(), jpeg);
            byte[] response = request(port, "GET", "/snapshot.jpg?x=1");
            int body = indexOf(response, "\r\n\r\n") + 4;
            check(indexOf(response, "200 OK") > 0 && indexOf(response, "Content-Length: 20000") > 0, "200 headers");
            check(Arrays.equals(Arrays.copyOfRange(response, body, response.length), jpeg), "byte-identical body");
            byte[] head = request(port, "HEAD", "/snapshot.jpg");
            check(head.length == indexOf(head, "\r\n\r\n") + 4, "HEAD has no body");
            check(indexOf(request(port, "GET", "/other"), "404 Not Found") > 0, "other path 404");
            byte[] telemetry = request(port, "GET", "/api/telemetry");
            check(indexOf(telemetry, "200 OK") > 0 && indexOf(telemetry, "\"environment_state\":\"disabled\"") > 0
                && indexOf(telemetry, "\"telemetry_position\":\"left\"") > 0,
                "read-only telemetry matches public viewer access");
            check(indexOf(request(port, "PUT", "/api/telemetry"), "405 Method Not Allowed") > 0,
                "public telemetry is read-only");
            check(indexOf(request(port, "POST", "/snapshot.jpg"), "405") > 0, "POST rejected");
            check(indexOf(request(port, "GET", "/", "Host: 127.0.0.1:18080\r\n", new byte[0]), "200 OK") > 0,
                "numeric forwarded Host supports SSH tunnels");
            byte[] viewer = request(port, "GET", "/");
            String viewerText = new String(viewer, "UTF-8");
            check(viewerText.contains("Settings") && viewerText.contains("Authorization")
                && viewerText.contains("script-src 'nonce-") && viewerText.contains("connect-src 'self'")
                && viewerText.contains("X-Frame-Options: DENY")
                && viewerText.indexOf("Content-Security-Policy:") == viewerText.lastIndexOf("Content-Security-Policy:"),
                "settings viewer has restrictive security headers");
            check(!viewerText.contains(token) && viewerText.contains("Forget token")
                && viewerText.contains("stream.mjpg"), "viewer has no embedded token and preserves stream");
            check(viewerText.contains("<p>Settings require the device-admin token.</p>")
                && !viewerText.contains("Read-only viewer.") && !viewerText.contains("Frames appear only"),
                "viewer shows only the requested token reminder");
            check(viewerText.contains("name=\"telemetry_position\"")
                && viewerText.contains("<option value=\"left\">Left</option><option value=\"right\">Right</option>")
                && viewerText.contains("#viewer[data-telemetry-position=left]")
                && viewerText.contains("@media(max-width:800px){#viewer,#viewer[data-telemetry-position]")
                && viewerText.contains("$('viewer').dataset.telemetryPosition=d.telemetry_position"),
                "panel position is saved, live-updated, and stacked below on narrow screens");
            check(viewerText.contains("name=\"text_color\" type=\"text\"")
                && viewerText.contains("name=\"panel_text_color\" type=\"text\"")
                && viewerText.contains("name=\"panel_background_color\" type=\"text\"")
                && viewerText.contains("data-color-picker=\"text_color\"")
                && viewerText.contains("data-color-picker=\"panel_text_color\"")
                && viewerText.contains("data-color-picker=\"panel_background_color\"")
                && viewerText.contains("p.oninput=()=>{f.value=p.value}")
                && viewerText.contains("f.oninput=()=>{if(/^#[0-9a-fA-F]{6}$/.test(f.value))p.value=f.value}"),
                "independent editable RGB fields and visual pickers synchronize in both directions");
            check(indexOf(request(port, "GET", "/api/config"), "401 Unauthorized") > 0, "admin API requires bearer token");
            String auth = "Authorization: Bearer " + token + "\r\n";
            check(indexOf(request(port, "GET", "/api/config", auth, new byte[0]), "200 OK") > 0, "authenticated GET");
            check(indexOf(request(port, "GET", "/api/config", "Cookie: admin=" + token + "\r\n", new byte[0]),
                "401 Unauthorized") > 0, "cookies do not authenticate");
            check(indexOf(request(port, "GET", "/api/config?token=" + token, "", new byte[0]),
                "401 Unauthorized") > 0, "URL token does not authenticate");
            check(indexOf(request(port, "PUT", "/api/config", auth
                + "Origin: http://127.0.0.1:" + port + "\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: 3\r\n",
                "a=b".getBytes("US-ASCII")), "200 OK") > 0, "same-origin authenticated PUT");
            check(indexOf(request(port, "PUT", "/api/config", auth
                + "Content-Type: application/x-www-form-urlencoded\r\nContent-Length: 3\r\n",
                "a=b".getBytes("US-ASCII")), "403 Forbidden") > 0, "mutation requires Origin");
            check(indexOf(request(port, "PUT", "/api/config", auth
                + "Origin: http://evil.example:" + port + "\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: 3\r\n",
                "a=b".getBytes("US-ASCII")), "403 Forbidden") > 0, "cross-origin mutation rejected");
            check(indexOf(request(port, "PUT", "/api/config", auth
                + "Origin: http://127.0.0.1:" + port + "\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: 4097\r\n",
                new byte[0]), "413 Content Too Large") > 0, "API body limit");
            check(indexOf(request(port, "GET", "/api/config", "Host: evil.example\r\n" + auth, new byte[0]),
                "400 Bad Request") > 0, "DNS host rejected");
            tokenStore.rotate();
            check(indexOf(request(port, "GET", "/api/config", auth, new byte[0]), "401 Unauthorized") > 0,
                "rotation invalidates the prior bearer token");
            check(indexOf(request(port, "GET", "/snapshot.jpg"), "200 OK") > 0, "snapshot remains unauthenticated");
            Socket slowBody = new Socket(InetAddress.getByName("127.0.0.1"), port);
            sockets.add(slowBody);
            slowBody.setSoTimeout(7000);
            String slowHeaders = "PUT /api/config HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\n"
                + "Authorization: Bearer " + tokenStore.read() + "\r\nOrigin: http://127.0.0.1:" + port + "\r\n"
                + "Content-Type: application/x-www-form-urlencoded\r\nContent-Length: 3\r\n\r\n";
            slowBody.getOutputStream().write((slowHeaders + "a").getBytes("US-ASCII"));
            Thread.sleep(5200);
            check(slowBody.getInputStream().read() == -1, "incomplete body closes at request deadline");

            // Active, stalled client must be closed by stop().
            Socket stalled = new Socket(InetAddress.getByName("127.0.0.1"), port);
            sockets.add(stalled);
            stalled.setSoTimeout(3000);
            stalled.getOutputStream().write("GET /snap".getBytes());
            Thread.sleep(200);
            long begin = System.nanoTime();
            server.stop();
            check((System.nanoTime() - begin) < 1500000000L, "stop returns promptly with an active client");
            check(!server.isRunning(), "not running after stop");
            int eof;
            try { eof = stalled.getInputStream().read(); } catch (IOException reset) { eof = -1; }
            check(eof == -1, "active client closed by stop");
            stalled.close();
            check(refused(port), "listener closed after stop");
            server.stop(); // repeated stop is safe

            // Restart repeatedly.
            for (int i = 0; i < 3; i++) {
                server.start();
                check(indexOf(request(server.getLocalPort(), "GET", "/snapshot.jpg"), "200 OK") > 0, "request " + i);
                server.stop();
                check(!server.isRunning(), "stopped " + i);
            }

            // Occupied port: start fails, state stays stopped, port stays owned by the other listener.
            SnapshotHttpServer first = new SnapshotHttpServer(image, 0, m -> { });
            servers.add(first);
            first.start();
            SnapshotHttpServer second = new SnapshotHttpServer(image, first.getLocalPort(), m -> { });
            servers.add(second);
            boolean failed = false;
            try { second.start(); } catch (IOException expected) { failed = true; }
            check(failed && !second.isRunning(), "occupied port fails without running state");
            check(indexOf(request(first.getLocalPort(), "GET", "/snapshot.jpg"), "200 OK") > 0, "first server unaffected");
            first.stop();

            // Deliberate stop must not invoke the unexpected-termination callback.
            AtomicInteger terminated = new AtomicInteger();
            SnapshotHttpServer watched = new SnapshotHttpServer(image, 0, m -> { });
            servers.add(watched);
            watched.setOnTerminated(terminated::incrementAndGet);
            watched.start();
            watched.stop();
            check(terminated.get() == 0, "no termination callback on stop");
        } finally {
            for (SnapshotHttpServer server : servers) server.stop();
            for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) { }
            image.delete();
            dir.delete();
        }
        System.out.println("SnapshotHttpServerTest passed");
    }
}
