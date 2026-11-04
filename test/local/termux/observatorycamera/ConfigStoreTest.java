/*
 * Copyright (C) 2026 David González López-Tercero
 * SPDX-License-Identifier: GPL-3.0-or-later
 * See LICENSE in the repository root for licensing details.
 */

package local.termux.observatorycamera;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Dependency-free test of the camera.properties store. Run: bash test/run.sh */
public final class ConfigStoreTest {
    private static void check(boolean ok, String name) { if (!ok) throw new AssertionError(name); }
    private static void write(File f, String text) throws Exception { Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8)); }
    private static String read(File f) throws Exception { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }
    private static String legacyDescription(CaptureConfig config) {
        return config.describe().substring(0, config.describe().indexOf(" http_enabled="));
    }
    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("cfg").toFile();
        File other = null;
        File fresh = null;
        File capabilityDir = null;
        File telemetryDir = null;
        File networkDir = null;
        try {
        ConfigStore store = new ConfigStore(dir);
        File live = new File(dir, "camera.properties");

        // Missing file: defaults, nothing written by a read.
        ConfigStore.Loaded l = store.load();
        check(l.fileState.equals("missing") && l.config.describe().equals(CaptureConfig.defaults().describe()) && !live.exists(), "missing file uses defaults");

        check(store.update("fields=").config.fieldsString().equals("none")
            && new ConfigStore(dir).load().config.fieldsString().equals("none"), "empty field selection persists");
        check(store.update("fields=location,datetime,fps,frames").config.fieldsString().equals("location,datetime,fps")
            && new ConfigStore(dir).load().config.fieldsString().equals("location,datetime,fps"),
            "legacy persisted fields migrate without frame counter");

        // Update, persistence across a new store instance (service restart / app update keeps filesDir).
        check(store.update("overlay=off target_fps=15.5 position=top-right").config != null, "fractional update ok");
        ConfigStore again = new ConfigStore(dir);
        check(legacyDescription(again.load().config).equals("overlay=off location=NYX position=top-right text_size=24 fields=location,datetime,fps target_fps=15.5 jpeg_size=auto text_color=green telemetry_position=right panel_enabled=on panel_text_color=#FF0000 panel_background_color=#000000 panel_text_size=16 panel_environment=on panel_device=on panel_capture=on mqtt_host= mqtt_port=1883 mqtt_topic=homeassistant/nyx/reading mqtt_tls=off mqtt_stale_seconds=120 focus_mode=auto focus_distance_diopters=0.0 exposure_mode=auto exposure_compensation=0 ae_lock=off iso=100 exposure_time_ns=10000000 white_balance=auto awb_lock=off jpeg_quality=90 orientation_mode=auto rotation_degrees=0 mirror=off"), again.load().config.describe());
        check(store.update("target_fps=10").config != null
            && new ConfigStore(dir).load().config.targetFpsString().equals("10.0"), "above-9 target persists across store instances");
        String saved = read(live);
        check(saved.startsWith("#") && saved.contains("\ntarget_fps=10.0\n") && !new File(dir, "camera.properties.tmp").exists(), "readable commented file, no temp left");

        // Invalid update changes nothing.
        ConfigStore.Outcome bad = store.update("overlay=on text_size=5");
        check(bad.config == null && bad.error.startsWith("invalid-text_size") && read(live).equals(saved), "invalid update preserves file");

        capabilityDir = Files.createTempDirectory("cfg-capabilities").toFile();
        ConfigStore capabilityStore = new ConfigStore(capabilityDir);
        CameraCapabilities caps = new CameraCapabilities(java.util.Arrays.asList(
            new CameraCapabilities.JpegSize(1280, 720, 1_000_000_000L, 0),
            new CameraCapabilities.JpegSize(1920, 1080, 0, 0)));
        java.util.Map<String, String> unsupported = new java.util.LinkedHashMap<>();
        unsupported.put("jpeg_size", "640x480");
        unsupported.put("target_fps", "15.5");
        ConfigStore.Outcome rejectedCapability = capabilityStore.updateEntries(unsupported, caps::validate);
        check(rejectedCapability.config == null && !new File(capabilityDir, ConfigStore.FILE_NAME).exists(),
            "unsupported web-style update is atomic");
        unsupported.put("jpeg_size", "1280x720");
        check(capabilityStore.updateEntries(unsupported, caps::validate).config != null
            && new ConfigStore(capabilityDir).load().config.targetFpsString().equals("15.5"),
            "metadata estimate below target does not reject persisted configuration");

        // UTF-8 round trip (non-ASCII label written and read back as UTF-8, not platform charset).
        String label = "Obs\u00e9rvatoire \u5929\u6587";
        write(live, "location=" + label + "\n");
        check(store.load().config.location.equals(label), "UTF-8 read");
        write(live, "location=\u00e9\n");
        ConfigStore.Outcome keep = store.importPending();
        check(keep.config == null && keep.error.equals("no-import-file-uploaded"), "no import file");
        File imp = store.importFile();
        write(imp, "location=" + label + "\ntext_size=30\n");
        ConfigStore.Outcome ok = store.importPending();
        check(ok.config != null && ok.config.location.equals(label) && ok.config.textSize == 30 && !imp.exists(), "import valid and consumed");
        check(read(live).contains("location=" + label) && new String(Files.readAllBytes(live.toPath()), StandardCharsets.UTF_8).contains("\u5929"), "UTF-8 write");
        check(new ConfigStore(dir).load().config.location.equals(label), "UTF-8 round trip");
        // Missing keys in an import use defaults.
        check(ok.config.targetFpsTenths == 10 && ok.config.position.equals("bottom-left") && ok.config.overlayEnabled, "defaults for missing keys");
        check(ok.config.jpegSize.equals("auto"), "missing JPEG size preserves the legacy default selection");
        check(ok.config.focusMode.equals("auto") && ok.config.exposureMode.equals("auto")
            && ok.config.whiteBalance.equals("auto") && ok.config.jpegQuality == 90
            && ok.config.orientationMode.equals("auto") && !ok.config.mirror,
            "legacy imports use image-control defaults");

        File controlDir = Files.createTempDirectory("cfg-controls").toFile();
        try {
            ConfigStore controlStore = new ConfigStore(controlDir);
            CameraControlCapabilities fixedControls = new CameraControlCapabilities(java.util.Collections.emptyList(),
                false, 0f, true, false, 0, 0, null, false, null, null, null, null, 0,
                java.util.Collections.singletonList("auto"), false);
            CameraCapabilities fixedCamera = new CameraCapabilities(java.util.Collections.singletonList(
                new CameraCapabilities.JpegSize(640, 480, 0, 0)), fixedControls);
            ConfigStore.Outcome unsupportedFocus = controlStore.update("focus_mode=lock", fixedCamera::validate);
            check(unsupportedFocus.config == null && !new File(controlDir, ConfigStore.FILE_NAME).exists(),
                "unsupported image-control update does not create or corrupt configuration");
            ConfigStore.Outcome imageSaved = controlStore.update(
                "jpeg_quality=55 orientation_mode=manual rotation_degrees=180 mirror=on", fixedCamera::validate);
            CaptureConfig imageReloaded = new ConfigStore(controlDir).load().config;
            check(imageSaved.config != null && imageReloaded.jpegQuality == 55 && imageReloaded.rotationDegrees == 180
                && imageReloaded.mirror, "validated quality and orientation settings persist atomically");
        } finally { deleteTree(controlDir); }

        telemetryDir = Files.createTempDirectory("cfg-telemetry").toFile();
        ConfigStore telemetryStore = new ConfigStore(telemetryDir);
        ConfigStore.Outcome telemetrySaved = telemetryStore.update(
            "fields=temperature,humidity,pressure,battery,battery_temperature,charging,thermal text_color=#aBcDeF telemetry_position=left panel_enabled=off panel_text_color=#123456 panel_background_color=#abcdef panel_text_size=32 panel_environment=off panel_device=on panel_capture=off mqtt_host=broker.example mqtt_port=65535 mqtt_topic=homeassistant/nyx/reading mqtt_tls=on mqtt_stale_seconds=600");
        CaptureConfig telemetryReloaded = new ConfigStore(telemetryDir).load().config;
        check(telemetrySaved.config != null && telemetryReloaded.fieldsString().equals(
            "temperature,humidity,pressure,battery,battery_temperature,charging,thermal")
            && !telemetryReloaded.panelEnabled && !telemetryReloaded.panelEnvironment
            && telemetryReloaded.panelDevice && !telemetryReloaded.panelCapture
            && telemetryReloaded.textColor.equals("#ABCDEF") && telemetryReloaded.telemetryPosition.equals("left")
            && telemetryReloaded.panelTextColor.equals("#123456") && telemetryReloaded.panelBackgroundColor.equals("#ABCDEF")
            && telemetryReloaded.mqttHost.equals("broker.example") && telemetryReloaded.mqttPort == 65535
            && telemetryReloaded.mqttTls && telemetryReloaded.mqttStaleSeconds == 600,
            "telemetry panel, colors, position and MQTT properties persist and reload");
        check(read(new File(telemetryDir, ConfigStore.FILE_NAME)).contains("text_color=#ABCDEF")
            && read(new File(telemetryDir, ConfigStore.FILE_NAME)).contains("telemetry_position=left"),
            "RGB color canonical form and panel position persist in camera.properties");
        check(!telemetryReloaded.toMap().containsKey("mqtt_username")
            && !telemetryReloaded.toMap().containsKey("mqtt_password"),
            "credential values are not part of camera.properties settings responses or exports");

        networkDir = Files.createTempDirectory("cfg-network").toFile();
        ConfigStore networkStore = new ConfigStore(networkDir);
        ConfigStore.Outcome networkSaved = networkStore.update(
            "http_enabled=off https_enabled=on https_port=9443 listen_addresses=192.168.10.2,127.0.0.1");
        CaptureConfig networkReloaded = new ConfigStore(networkDir).load().config;
        check(networkSaved.config != null && !networkReloaded.httpEnabled && networkReloaded.httpsEnabled
            && networkReloaded.httpsPort == 9443 && networkReloaded.listenAddresses.equals("127.0.0.1,192.168.10.2"),
            "network settings survive SSH-style atomic update and properties reload");
        String networkBefore = read(new File(networkDir, ConfigStore.FILE_NAME));
        ConfigStore.Outcome rejectedNetwork = networkStore.update("http_enabled=off https_enabled=off");
        check(rejectedNetwork.config == null && read(new File(networkDir, ConfigStore.FILE_NAME)).equals(networkBefore),
            "invalid network candidate leaves the persisted file unchanged");
        write(new File(networkDir, ConfigStore.FILE_NAME),
            "http_enabled=on\nhttp_port=8080\nhttps_enabled=off\nhttps_port=8443\nlisten_addresses=192.168.10.2,127.0.0.1\n");
        check(new ConfigStore(networkDir).load().config.listenAddresses.equals("127.0.0.1,192.168.10.2"),
            "direct properties edits are parsed and canonicalized");
        write(new File(networkDir, ConfigStore.FILE_NAME),
            "http_enabled=on\nhttp_port=8080\nhttps_enabled=on\nhttps_port=8080\nlisten_addresses=0.0.0.0\n");
        check(new ConfigStore(networkDir).load().fileState.equals("corrupt"),
            "invalid direct network edits retain the last effective valid configuration");

        // Invalid imports: whole file rejected, live file untouched, import file removed.
        String before = read(live);
        String[] badImports = {"location=ok\nbogus=1\n", "text_size=500\n", "target_fps=0\n", "target_fps=1.25\n",
            "target_fps=922337203685477581\n", "jpeg_size=1280x0\n", "overlay=maybe\n", "fields=gps\n", "position=middle\n",
            "text_color=red\n", "text_color=#12345\n", "telemetry_position=middle\n",
            "mqtt_password=not-exportable\n", "mqtt_username=not-exportable\n",
            "location=a\\u00zz\n", "location=" + new String(new char[30]).replace('\0', 'x') + "\n"};
        for (String b : badImports) {
            write(imp, b);
            ConfigStore.Outcome o = store.importPending();
            check(o.config == null && o.error.startsWith("invalid-import:") && read(live).equals(before) && !imp.exists(), "rejected import: " + b + " -> " + o.error);
        }
        Files.write(imp.toPath(), new byte[]{'l', 'o', 'c', '=', (byte) 0xC3});
        check(store.importPending().error.contains("not-valid-utf-8") && read(live).equals(before), "invalid UTF-8 rejected");
        Files.write(imp.toPath(), new byte[ConfigStore.MAX_FILE_BYTES + 1]);
        check(store.importPending().error.contains("file-too-large") && read(live).equals(before), "oversized rejected");

        // Corrupt live file: last valid configuration (this process) keeps being used; the file is not silently rewritten.
        write(live, "text_size=banana\n");
        ConfigStore.Loaded c = store.load();
        check(c.fileState.equals("corrupt") && c.error != null && c.config.location.equals(label) && read(live).equals("text_size=banana\n"), "corrupt keeps last valid, reports");
        // A new process with no memory falls back to defaults.
        other = Files.createTempDirectory("cfg2").toFile();
        write(new File(other, "camera.properties"), "\u0000garbage");
        check(new ConfigStore(other).load().config.describe().equals(CaptureConfig.defaults().describe()) && new ConfigStore(other).load().fileState.equals("corrupt"), "corrupt without history uses defaults");
        // Explicit update repairs the file from the effective configuration.
        check(store.update("text_size=31").config != null && store.load().fileState.equals("ok") && store.load().config.location.equals(label), "update repairs corrupt file");

        // Failed write preserves the previous configuration (temp path blocked by a directory).
        String good = read(live);
        File block = new File(dir, "camera.properties.tmp");
        check(block.mkdir(), "block temp path");
        ConfigStore.Outcome fail = store.update("text_size=40");
        check(fail.config == null && fail.error.startsWith("write-failed") && read(live).equals(good) && store.load().config.textSize == 31, "write failure preserves previous file");
        block.delete();
        check(store.update("text_size=40").config != null && store.load().config.textSize == 40, "recovers after failure");

        // Reset, materialize.
        check(store.reset().config.describe().equals(CaptureConfig.defaults().describe()), "reset");
        fresh = Files.createTempDirectory("cfg3").toFile();
        ConfigStore fs = new ConfigStore(fresh);
        check(fs.materialize().config != null && new File(fresh, "camera.properties").exists() && fs.load().fileState.equals("ok"), "materialize writes defaults");

        // The committed example parses and equals the defaults.
        CaptureConfig example = ConfigStore.parseStrict(Files.readAllBytes(new File("config/camera.properties.example").toPath()));
        check(example.describe().equals(CaptureConfig.defaults().describe()), "example file equals defaults");

        // Concurrent updates never truncate or corrupt the file.
        Thread[] threads = new Thread[8];
        for (int i = 0; i < threads.length; i++) {
            final int n = 10 + i;
            threads[i] = new Thread(() -> { for (int k = 0; k < 30; k++) store.update("text_size=" + n); });
            threads[i].start();
        }
        for (Thread t : threads) t.join();
        ConfigStore.Loaded end = new ConfigStore(dir).load();
        check(end.fileState.equals("ok") && end.config.textSize >= 10 && end.config.textSize <= 17, "concurrent updates leave a valid file");
        System.out.println("ConfigStoreTest passed");
        } finally {
            deleteTree(dir);
            deleteTree(other);
            deleteTree(fresh);
            deleteTree(capabilityDir);
            deleteTree(telemetryDir);
            deleteTree(networkDir);
        }
    }
}
