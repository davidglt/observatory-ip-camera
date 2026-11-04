/*
 * Copyright (C) 2026 David González López-Tercero
 * SPDX-License-Identifier: GPL-3.0-or-later
 * See LICENSE in the repository root for licensing details.
 */

package local.termux.observatorycamera;

import java.nio.charset.StandardCharsets;

public final class EnvironmentalTelemetryTest {
    public static void main(String[] args) {
        long now = 1_800_000_000_000L;
        EnvironmentalTelemetry sensor = new EnvironmentalTelemetry();
        sensor.setConnectionState("waiting", null);
        check(sensor.state(now, 120_000) .equals("waiting"), "waiting without sample");
        check(sensor.accept(payload(now / 1000, 20.25, 45, 1013.2).getBytes(StandardCharsets.UTF_8), true, now),
            "retained fresh sample accepted by source time");
        check(sensor.state(now, 120_000).equals("fresh"), "fresh state");
        EnvironmentalTelemetry.Reading first = sensor.latest();
        check(sensor.accept(payload(now / 1000, 18, 30, 980).getBytes(StandardCharsets.UTF_8), true, now),
            "same retained sample is ignored successfully");
        check(sensor.latest().temperatureC == first.temperatureC, "same old retained timestamp cannot replace data");
        check(sensor.accept(payload(now / 1000 - 600, 19, 40, 1000).getBytes(StandardCharsets.UTF_8), true, now),
            "old retained sample does not fail connection");
        check(sensor.latest().timestampEpochMillis == first.timestampEpochMillis, "old retained sample cannot replace newer reading");
        check(sensor.state(now, 120_000).equals("fresh"), "old retained sample cannot refresh reading");
        check(sensor.state(now + 120_001, 120_000).equals("stale"), "source timestamp controls stale state");
        sensor.setConnectionState("disconnected", null);
        check(sensor.latest() != null, "same-source reconnect keeps the last reading");
        sensor.reset("connecting");
        check(sensor.latest() == null && sensor.state(now, 120_000).equals("connecting"),
            "switching MQTT source clears old readings and ordering state");

        for (String invalid : new String[] {
            "[]", "{}", "{\"timestamp_epoch\":1}", "{\"timestamp_epoch\":1,\"timestamp_epoch\":1,\"temperature_c\":1,\"humidity_pct\":1,\"pressure_hpa\":300}",
            payload(now / 1000, -40.1, 50, 900), payload(now / 1000, 85.1, 50, 900),
            payload(now / 1000, 20, -0.1, 900), payload(now / 1000, 20, 100.1, 900),
            payload(now / 1000, 20, 50, 299.9), payload(now / 1000, 20, 50, 1100.1),
            payload(now / 1000 + 100, 20, 50, 900),
            "{\"timestamp_epoch\":1e309,\"temperature_c\":1,\"humidity_pct\":1,\"pressure_hpa\":300}",
            "{\"timestamp_epoch\":1,\"temperature_c\":\"1\",\"humidity_pct\":1,\"pressure_hpa\":300}",
            "{\"timestamp_epoch\":1,\"temperature_c\":1,\"humidity_pct\":1,\"pressure_hpa\":300} trailing"
        }) check(!sensor.accept(invalid.getBytes(StandardCharsets.UTF_8), false, now), "reject malformed or out-of-range JSON");
        check(!sensor.accept(new byte[EnvironmentalTelemetry.MAX_PAYLOAD_BYTES + 1], false, now), "reject oversized payload");

        EnvironmentalTelemetry disabled = new EnvironmentalTelemetry();
        check(disabled.state(now, 120_000).equals("disabled"), "disabled distinction");
        disabled.setConnectionState("connecting", null);
        check(disabled.state(now, 120_000).equals("connecting"), "connecting distinction");
        disabled.setConnectionState("disconnected", null);
        check(disabled.state(now, 120_000).equals("disconnected"), "disconnected distinction");
        disabled.setConnectionState("error", "bad-config");
        check(disabled.state(now, 120_000).equals("error"), "error distinction");
        System.out.println("EnvironmentalTelemetryTest passed");
    }

    private static String payload(long timestamp, double temperature, double humidity, double pressure) {
        return "{\"timestamp_epoch\":" + timestamp + ",\"temperature_c\":" + temperature
            + ",\"humidity_pct\":" + humidity + ",\"pressure_hpa\":" + pressure + ",\"pressure_altitude_m\":12}";
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
