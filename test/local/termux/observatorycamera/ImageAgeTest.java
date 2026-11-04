/*
 * Copyright (C) 2026 David González López-Tercero
 * SPDX-License-Identifier: GPL-3.0-or-later
 * See LICENSE in the repository root for licensing details.
 */

package local.termux.observatorycamera;

public final class ImageAgeTest {
    public static void main(String[] args) {
        check(ImageAge.seconds(1_000, 3_500) == 2.5, "published image age");
        check(ImageAge.seconds(0, 3_500) == null, "missing publication is unavailable");
        check(ImageAge.seconds(4_000, 3_500) == null, "future file timestamp is unavailable");
        System.out.println("ImageAgeTest passed");
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
