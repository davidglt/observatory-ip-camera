/*
 * Copyright (C) 2026 David González López-Tercero
 * SPDX-License-Identifier: GPL-3.0-or-later
 * See LICENSE in the repository root for licensing details.
 */

package local.termux.observatorycamera;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.function.BooleanSupplier;

/**
 * Atomic private snapshot publication: bytes are written and synced to a temporary file and only
 * then renamed over the final name, so HTTP readers see either the previous complete file or the
 * new complete file. The temporary file is never the published name. Pure Java.
 */
final class SnapshotPublisher {
    private static final Object LOCK = new Object();

    private SnapshotPublisher() {}

    /**
     * Publishes data as dir/finalName. stillCurrent is checked under the lock right before the rename;
     * when it returns false (session stopped meanwhile) nothing is published and false is returned.
     */
    static boolean publish(File dir, String finalName, byte[] data, BooleanSupplier stillCurrent) throws IOException {
        if (data == null || data.length < 4 || (data[0] & 0xFF) != 0xFF || (data[1] & 0xFF) != 0xD8) throw new IOException("Not a JPEG; refusing to publish.");
        File pending = new File(dir, "snapshot.tmp");
        File destination = new File(dir, finalName);
        synchronized (LOCK) {
            try (FileOutputStream out = new FileOutputStream(pending)) {
                out.write(data);
                out.getFD().sync();
            } catch (IOException e) {
                pending.delete();
                throw e;
            }
            if (!stillCurrent.getAsBoolean()) {
                pending.delete();
                return false;
            }
            if (!pending.renameTo(destination)) {
                pending.delete();
                throw new IOException("Could not publish snapshot.");
            }
            destination.setLastModified(System.currentTimeMillis());
            return true;
        }
    }
}
