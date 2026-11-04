/*
 * Copyright (C) 2026 David González López-Tercero
 * SPDX-License-Identifier: GPL-3.0-or-later
 * See LICENSE in the repository root for licensing details.
 */

package local.termux.observatorycamera;

import android.content.Context;

/** Android glue: the authoritative settings live in files/camera.properties (see ConfigStore). */
final class CaptureSettings {
    private CaptureSettings() {}

    static ConfigStore store(Context context) { return new ConfigStore(context.getFilesDir()); }

    /** Effective configuration; re-read from the file on every call so changes apply to the next frame. */
    static CaptureConfig load(Context context) { return store(context).load().config; }
}
