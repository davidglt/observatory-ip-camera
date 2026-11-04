/*
 * Copyright (C) 2026 David González López-Tercero
 * SPDX-License-Identifier: GPL-3.0-or-later
 * See LICENSE in the repository root for licensing details.
 */

package local.termux.observatorycamera;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

public final class NetworkListenerTest {
    public static void main(String[] args) throws Exception {
        File directory = Files.createTempDirectory("network-listener-test").toFile();
        File image = new File(directory, "snapshot.jpg");
        try {
            testMultipleAddressesAndRollback(directory, image);
            testTlsAndOriginProtection(directory, image);
            System.out.println("NetworkListenerTest passed");
        } finally {
            deleteTree(directory);
        }
    }

    private static void testMultipleAddressesAndRollback(File directory, File image) throws Exception {
        java.util.List<NetworkInterfaceDiscovery.Address> interfaces = NetworkInterfaceDiscovery.discover();
        if (interfaces.isEmpty()) {
            System.out.println("NetworkListenerTest: multi-address loopback portion skipped (no non-loopback IPv4 address)");
        }
        String secondAddress = interfaces.isEmpty() ? "127.0.0.2" : interfaces.get(0).value;
        int port = availablePort();
        CaptureConfig config = accepted("http_port=" + port + " listen_addresses=" + secondAddress + ",127.0.0.1");
        SnapshotHttpServer server = new SnapshotHttpServer(image, config, null, null, message -> {},
            null, null, null);
        try {
            server.start();
            check(server.getBoundEndpoints().size() == 2, "both requested IPv4 addresses bound");
            check(response("127.0.0.1", port, "127.0.0.1", port).contains("200 OK"), "first concrete address serves viewer");
            if (!interfaces.isEmpty())
                check(response(secondAddress, port, secondAddress, port).contains("200 OK"), "second concrete address serves viewer");
        } finally {
            server.stop();
        }

        int rollbackPort = availablePort();
        CaptureConfig unavailable = accepted("http_port=" + rollbackPort
            + " listen_addresses=127.0.0.1,203.0.113.1");
        SnapshotHttpServer rejected = new SnapshotHttpServer(image, unavailable, null, null, message -> {},
            null, null, null);
        try {
            rejected.start();
            throw new AssertionError("unavailable address was silently accepted");
        } catch (java.io.IOException expected) {
            check(!rejected.isRunning(), "failed bind reports no running listener");
        }
        try (ServerSocket probe = new ServerSocket(rollbackPort, 1, InetAddress.getByName("127.0.0.1"))) {
            check(probe.isBound(), "earlier staged address was closed after a later bind failed");
        }
    }

    private static void testTlsAndOriginProtection(File directory, File image) throws Exception {
        File keyStoreFile = new File(directory, TlsCertificateStore.KEYSTORE_FILE);
        File passwordFile = new File(directory, TlsCertificateStore.PASSWORD_FILE);
        Files.write(passwordFile.toPath(), "tls-test-only\n".getBytes(StandardCharsets.UTF_8));
        File keytoolLog = new File(directory, "keytool.log");
        Process keytool = new ProcessBuilder(new File(new File(System.getProperty("java.home"), "bin"), "keytool").getAbsolutePath(),
            "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-validity", "365",
            "-dname", "CN=camera.example", "-ext", "SAN=IP:127.0.0.1,DNS:camera.example",
            "-keystore", keyStoreFile.getAbsolutePath(), "-storetype", "PKCS12",
            "-storepass:file", passwordFile.getAbsolutePath(), "-keypass:file", passwordFile.getAbsolutePath(),
            "-noprompt").redirectErrorStream(true).redirectOutput(keytoolLog).start();
        check(keytool.waitFor() == 0, "test PKCS#12 generated");
        TlsCertificateStore.Identity identity = TlsCertificateStore.load(directory);
        int port = availablePort();
        CaptureConfig config = accepted("http_enabled=off https_enabled=on https_port=" + port
            + " listen_addresses=127.0.0.1");
        AdminTokenStore tokens = new AdminTokenStore(directory);
        String token = tokens.provision();
        SnapshotHttpServer server = new SnapshotHttpServer(image, config, identity.context, identity.publicPem(),
            message -> {}, tokens, (method, path, body) -> new SnapshotHttpServer.ApiResult(200, "OK", "{\"ok\":true}"), null);
        SSLContext client = trustedContext(identity.certificate);
        try {
            server.start();
            check(server.getBoundEndpoints().get(0).startsWith("https://127.0.0.1:"), "HTTPS-only binding status");
            String host = "camera.example:" + port;
            check(tlsRequest(client, port, host, "GET", "/", "", new byte[0]).contains("200 OK"),
                "TLS handshake validates DNS SAN and serves viewer");
            String auth = "Authorization: Bearer " + token + "\r\n";
            check(tlsRequest(client, port, host, "GET", "/api/config", auth, new byte[0]).contains("200 OK"),
                "authenticated admin GET over HTTPS");
            String form = auth + "Origin: https://" + host + "\r\n"
                + "Content-Type: application/x-www-form-urlencoded\r\nContent-Length: 3\r\n";
            check(tlsRequest(client, port, host, "PUT", "/api/config", form, "a=b".getBytes(StandardCharsets.US_ASCII))
                .contains("200 OK"), "same-origin HTTPS mutation accepted");
            check(tlsRequest(client, port, host, "PUT", "/api/config",
                auth + "Origin: http://" + host + "\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: 3\r\n",
                "a=b".getBytes(StandardCharsets.US_ASCII)).contains("403 Forbidden"),
                "HTTP Origin cannot mutate over HTTPS");
            check(tlsRequest(client, port, host, "PUT", "/api/config",
                auth + "Origin: https://attacker.example:" + port
                    + "\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: 3\r\n",
                "a=b".getBytes(StandardCharsets.US_ASCII)).contains("403 Forbidden"),
                "cross-origin HTTPS mutation rejected");
            String publicCert = tlsRequest(client, port, host, "GET", "/server-certificate.pem", "", new byte[0]);
            check(publicCert.contains("BEGIN CERTIFICATE") && !publicCert.contains("PRIVATE KEY"),
                "public certificate endpoint never returns private key material");
            check(tlsRequest(client, port, "camera.example", "GET", "/", "", new byte[0]).contains("400 Bad Request"),
                "non-default HTTPS port must be present in Host authority");
        } finally {
            server.stop();
        }
    }

    private static SSLContext trustedContext(X509Certificate certificate) throws Exception {
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        trust.setCertificateEntry("explicit-test-server", certificate);
        TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        managers.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, managers.getTrustManagers(), null);
        return context;
    }

    private static String tlsRequest(SSLContext context, int port, String host, String method, String path,
                                     String headers, byte[] body) throws Exception {
        Socket plain = new Socket();
        plain.connect(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 3000);
        try (Socket connection = plain;
             SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket(connection, "camera.example", port, true)) {
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(parameters);
            socket.setSoTimeout(5000);
            socket.startHandshake();
            socket.getOutputStream().write((method + " " + path + " HTTP/1.1\r\nHost: " + host + "\r\n" + headers + "\r\n")
                .getBytes(StandardCharsets.US_ASCII));
            if (body.length > 0) socket.getOutputStream().write(body);
            return read(socket.getInputStream());
        }
    }

    private static String response(String address, int port, String host, int hostPort) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress(InetAddress.getByName(address), port), 3000);
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(("GET / HTTP/1.1\r\nHost: " + host + ":" + hostPort + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
            return read(socket.getInputStream());
        }
    }

    private static String read(InputStream input) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) out.write(buffer, 0, count);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static CaptureConfig accepted(String assignments) {
        CaptureConfig.Parsed parsed = CaptureConfig.parse(assignments, CaptureConfig.defaults());
        if (parsed.config == null) throw new AssertionError(parsed.error);
        return parsed.config;
    }

    private static int availablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
