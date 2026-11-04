/*
 * Copyright (C) 2026 David González López-Tercero
 * SPDX-License-Identifier: GPL-3.0-or-later
 * See LICENSE in the repository root for licensing details.
 */

package local.termux.observatorycamera;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/** In-memory-only change detector for the bounded private MQTT credential file. */
final class MqttCredentialFingerprint {
    private static final int MAX_BYTES = 2048;
    private MqttCredentialFingerprint() {}

    static byte[] read(File file) throws IOException {
        byte[] content;
        if (!file.exists()) {
            content = new byte[]{0};
        } else {
            try (InputStream input = new FileInputStream(file);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[512];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > MAX_BYTES)
                        throw new IOException("credentials-file-too-large");
                    output.write(buffer, 0, count);
                }
                content = output.toByteArray();
            }
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        } finally {
            Arrays.fill(content, (byte) 0);
        }
    }

    static boolean matches(byte[] first, byte[] second) {
        return first != null && second != null && MessageDigest.isEqual(first, second);
    }
}
