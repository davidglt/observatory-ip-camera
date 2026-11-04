/*
 * Copyright (C) 2026 David González López-Tercero
 * SPDX-License-Identifier: GPL-3.0-or-later
 * See LICENSE in the repository root for licensing details.
 */

package local.termux.observatorycamera;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Dependency-free token provisioning, fail-closed and rotation test. */
public final class AdminTokenStoreTest {
    private static void check(boolean ok, String name) { if (!ok) throw new AssertionError(name); }

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("admin-token-test").toFile();
        File file = new File(dir, AdminTokenStore.FILE_NAME);
        try {
            AdminTokenStore store = new AdminTokenStore(dir);
            check(store.read() == null, "absent token is unavailable");
            String first = store.provision();
            check(first.matches("[A-Za-z0-9_-]{43}") && store.matches(first), "secure token generated");
            check(store.provision().equals(first), "provisioning is persistent and idempotent");
            check(!new String(Files.readAllBytes(file.toPath()), StandardCharsets.US_ASCII).contains("\n"),
                "token is not written with log-like line formatting");
            String second = store.rotate();
            check(!first.equals(second) && store.matches(second) && !store.matches(first), "rotation invalidates prior token");
            Files.write(file.toPath(), "invalid".getBytes(StandardCharsets.US_ASCII));
            check(store.read() == null && !store.matches(second), "corruption fails closed");
            boolean refused = false;
            try { store.provision(); } catch (java.io.IOException expected) { refused = true; }
            check(refused, "provision does not silently replace a corrupt token");
            String repaired = store.rotate();
            check(store.matches(repaired) && !store.matches(second), "explicit rotation recovers corrupt token");
        } finally {
            file.delete();
            new File(dir, AdminTokenStore.FILE_NAME + ".tmp").delete();
            dir.delete();
        }
        System.out.println("AdminTokenStoreTest passed");
    }
}
