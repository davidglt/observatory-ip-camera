/*
 * Copyright (C) 2026 David González López-Tercero
 * SPDX-License-Identifier: GPL-3.0-or-later
 * See LICENSE in the repository root for licensing details.
 */

package local.termux.observatorycamera;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

public final class TlsCertificateStoreTest {
    public static void main(String[] args) throws Exception {
        File directory = Files.createTempDirectory("tls-store-test").toFile();
        File keyStore = new File(directory, TlsCertificateStore.KEYSTORE_FILE);
        File password = new File(directory, TlsCertificateStore.PASSWORD_FILE);
        try {
            Files.write(password.toPath(), "store-passphrase\n".getBytes(StandardCharsets.UTF_8));
            String keytool = new File(new File(System.getProperty("java.home"), "bin"), "keytool").getAbsolutePath();
            File outputFile = new File(directory, "keytool.log");
            Process process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "server", "-keyalg", "RSA",
                "-keysize", "2048", "-validity", "365", "-dname", "CN=camera.example",
                "-ext", "SAN=IP:127.0.0.1,DNS:camera.example", "-keystore", keyStore.getAbsolutePath(),
                "-storetype", "PKCS12", "-storepass:file", password.getAbsolutePath(),
                "-keypass:file", password.getAbsolutePath(), "-noprompt").redirectErrorStream(true)
                .redirectOutput(outputFile).start();
            check(process.waitFor() == 0, "keytool created test identity: "
                + new String(Files.readAllBytes(outputFile.toPath()), StandardCharsets.UTF_8));

            TlsCertificateStore.Identity identity = TlsCertificateStore.load(directory);
            check(identity.context != null && identity.fingerprint.matches("([0-9A-F]{2}:){31}[0-9A-F]{2}"),
                "valid PKCS#12 identity provides TLS context and SHA-256 fingerprint");
            check(identity.expiresAt.endsWith("Z"), "expiry is displayed in UTC");
            String pem = identity.publicPem();
            check(pem.startsWith("-----BEGIN CERTIFICATE-----") && !pem.contains("PRIVATE KEY"),
                "public export contains no private key");
            X509Certificate exported = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
            check(exported.getSubjectAlternativeNames().size() == 2, "IP and DNS SANs survive the public certificate export");

            Files.write(new File(directory, TlsCertificateStore.IMPORT_KEYSTORE_FILE).toPath(), "invalid".getBytes(StandardCharsets.US_ASCII));
            Files.write(new File(directory, TlsCertificateStore.IMPORT_PASSWORD_FILE).toPath(),
                "store-passphrase\n".getBytes(StandardCharsets.UTF_8));
            try {
                TlsCertificateStore.importPending(directory);
                throw new AssertionError("invalid staged identity accepted");
            } catch (Exception expected) {
                check(TlsCertificateStore.load(directory).fingerprint.equals(identity.fingerprint),
                    "failed certificate import retains the prior valid identity");
            }

            Files.write(password.toPath(), "wrong-passphrase\n".getBytes(StandardCharsets.UTF_8));
            try {
                TlsCertificateStore.load(directory);
                throw new AssertionError("wrong PKCS#12 password accepted");
            } catch (Exception expected) {
                check(expected.getMessage() != null && !expected.getMessage().contains("passphrase"),
                    "password details are not included in validation errors");
            }
            System.out.println("TlsCertificateStoreTest passed");
        } finally {
            keyStore.delete();
            password.delete();
            new File(directory, "keytool.log").delete();
            directory.delete();
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
