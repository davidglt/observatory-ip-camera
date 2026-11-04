/*
 * Copyright (C) 2026 David González López-Tercero
 * SPDX-License-Identifier: GPL-3.0-or-later
 * See LICENSE in the repository root for licensing details.
 */

package local.termux.observatorycamera;

/**
 * EXIF orientation tag (1..8) decomposed into the transform needed to make pixels upright: rotate
 * clockwise by rotationDegrees, then mirror. The overlay path applies it exactly once and the
 * re-encoded JPEG carries no orientation tag, so the result is never rotated twice. Pure Java.
 */
final class ExifOrientation {
    private ExifOrientation() {}

    static int rotationDegrees(int tag) {
        switch (tag) {
            case 3: return 180;
            case 5: case 6: return 90;
            case 7: case 8: return 270;
            default: return 0;
        }
    }

    /** Mirror left-right after the rotation (tags 2, 5, 7). */
    static boolean flipHorizontal(int tag) { return tag == 2 || tag == 5 || tag == 7; }

    /** Mirror top-bottom after the rotation (tag 4). */
    static boolean flipVertical(int tag) { return tag == 4; }

    static boolean isNormal(int tag) { return rotationDegrees(tag) == 0 && !flipHorizontal(tag) && !flipVertical(tag); }

    static boolean swapsDimensions(int tag) { return rotationDegrees(tag) % 180 != 0; }
}
