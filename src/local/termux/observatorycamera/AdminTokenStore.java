/*
 * Copyright (C) 2026 David González López-Tercero
 * SPDX-License-Identifier: GPL-3.0-or-later
 * See LICENSE in the repository root for licensing details.
 */

package local.termux.observatorycamera;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/** Persistent, app-private bearer token. Provisioning and rotation are explicit local controls only. */
public final class AdminTokenStore {
    public static final String FILE_NAME = "http-admin.token";
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Object LOCK = new Object();
    private static final SecureRandom RANDOM = new SecureRandom();
    private final File tokenFile;
    private final File temporaryFile;

    public AdminTokenStore(File filesDir) {
        tokenFile = new File(filesDir, FILE_NAME);
        temporaryFile = new File(filesDir, FILE_NAME + ".tmp");
    }

    /** Returns the current token or null if it is absent or invalid; never provisions implicitly. */
    public String read() {
        synchronized (LOCK) {
            try {
                if (!tokenFile.isFile() || tokenFile.length() > 64) return null;
                byte[] data = new byte[(int) tokenFile.length()];
                try (FileInputStream in = new FileInputStream(tokenFile)) {
                    int offset = 0;
                    while (offset < data.length) {
                        int count = in.read(data, offset, data.length - offset);
                        if (count < 0) return null;
                        offset += count;
                    }
                    if (in.read() != -1) return null;
                }
                String value = new String(data, StandardCharsets.US_ASCII);
                return TOKEN.matcher(value).matches() ? value : null;
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }
    }

    /** Creates the token only when explicitly requested and no token file exists. */
    public String provision() throws IOException {
        synchronized (LOCK) {
            if (tokenFile.exists()) {
                String existing = read();
                if (existing == null) throw new IOException("token-file-invalid");
                return existing;
            }
            return writeNew();
        }
    }

    /** Replaces any existing token atomically; the previous value immediately stops authenticating. */
    public String rotate() throws IOException {
        synchronized (LOCK) { return writeNew(); }
    }

    public boolean matches(String supplied) {
        if (supplied == null || !TOKEN.matcher(supplied).matches()) return false;
        String current = read();
        return current != null && MessageDigest.isEqual(current.getBytes(StandardCharsets.US_ASCII),
            supplied.getBytes(StandardCharsets.US_ASCII));
    }

    private String writeNew() throws IOException {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        if (temporaryFile.exists() && !temporaryFile.delete()) throw new IOException("token-temp-delete-failed");
        try (FileOutputStream out = new FileOutputStream(temporaryFile)) {
            if (!temporaryFile.setReadable(false, false) || !temporaryFile.setWritable(false, false)
                || !temporaryFile.setReadable(true, true) || !temporaryFile.setWritable(true, true))
                throw new IOException("token-permissions-failed");
            out.write(value.getBytes(StandardCharsets.US_ASCII));
            out.getFD().sync();
        } catch (IOException e) {
            temporaryFile.delete();
            throw e;
        }
        if (!temporaryFile.renameTo(tokenFile)) {
            temporaryFile.delete();
            throw new IOException("token-rename-failed");
        }
        tokenFile.setReadable(false, false);
        tokenFile.setWritable(false, false);
        tokenFile.setReadable(true, true);
        tokenFile.setWritable(true, true);
        return value;
    }
}
