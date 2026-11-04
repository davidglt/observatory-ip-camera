/*
 * Copyright (C) 2026 David González López-Tercero
 * SPDX-License-Identifier: GPL-3.0-or-later
 * See LICENSE in the repository root for licensing details.
 */

package local.termux.observatorycamera;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class MqttCredentialFingerprintTest {
    public static void main(String[] args) throws Exception {
        File directory = Files.createTempDirectory("mqtt-fingerprint").toFile();
        File credentials = new File(directory, "credentials.properties");
        try {
            byte[] missing = MqttCredentialFingerprint.read(credentials);
            Files.write(credentials.toPath(), "username=first\npassword=secret-one\n".getBytes(StandardCharsets.UTF_8));
            long modified = credentials.lastModified();
            byte[] first = MqttCredentialFingerprint.read(credentials);
            check(!MqttCredentialFingerprint.matches(missing, first), "addition detected");
            Files.write(credentials.toPath(), "username=second\npassword=secret-two\n".getBytes(StandardCharsets.UTF_8));
            credentials.setLastModified(modified);
            byte[] replacement = MqttCredentialFingerprint.read(credentials);
            check(!MqttCredentialFingerprint.matches(first, replacement), "same-mtime replacement detected");
            credentials.delete();
            check(!MqttCredentialFingerprint.matches(replacement, MqttCredentialFingerprint.read(credentials)),
                "removal detected");
            Files.write(credentials.toPath(), new byte[2049]);
            try {
                MqttCredentialFingerprint.read(credentials);
                throw new AssertionError("oversized credentials accepted");
            } catch (java.io.IOException expected) {
                check(expected.getMessage().equals("credentials-file-too-large"), "bounded error");
            }
            System.out.println("MqttCredentialFingerprintTest passed");
        } finally {
            credentials.delete();
            directory.delete();
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
