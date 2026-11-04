/*
 * Copyright (C) 2026 David González López-Tercero
 * SPDX-License-Identifier: GPL-3.0-or-later
 * See LICENSE in the repository root for licensing details.
 */

package local.termux.observatorycamera;

/** Dependency-free test. Run: bash test/run.sh */
public final class JpegOrientationTest {
    private static void check(int expected, int actual, String name) {
        if (expected != actual) throw new AssertionError(name + ": expected " + expected + " but was " + actual);
    }

    public static void main(String[] args) {
        int[] devices = {0, 90, 180, 270};
        int[] rear = {90, 180, 270, 0};
        int[] front = {90, 0, 270, 180};
        for (int i = 0; i < devices.length; i++) {
            check(rear[i], JpegOrientation.calculate(90, devices[i], false), "rear " + devices[i]);
            check(front[i], JpegOrientation.calculate(90, devices[i], true), "front " + devices[i]);
        }
        check(0, JpegOrientation.roundDeviceOrientation(44), "round 44");
        check(90, JpegOrientation.roundDeviceOrientation(45), "round 45");
        check(0, JpegOrientation.roundDeviceOrientation(315), "round 315 wraps to 0");
        check(270, JpegOrientation.roundDeviceOrientation(314), "round 314");
        check(0, JpegOrientation.roundDeviceOrientation(359), "round 359");
        System.out.println("JpegOrientationTest passed");
    }
}
