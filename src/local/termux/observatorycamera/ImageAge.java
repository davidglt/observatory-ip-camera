/*
 * Copyright (C) 2026 David González López-Tercero
 * SPDX-License-Identifier: GPL-3.0-or-later
 * See LICENSE in the repository root for licensing details.
 */

package local.termux.observatorycamera;

/** Converts a real file publication timestamp to a non-negative age, or unavailable when absent/future. */
final class ImageAge {
    private ImageAge() {}

    static Double seconds(long publishedAtMillis, long nowMillis) {
        if (publishedAtMillis <= 0 || publishedAtMillis > nowMillis) return null;
        return (nowMillis - publishedAtMillis) / 1000.0;
    }
}
