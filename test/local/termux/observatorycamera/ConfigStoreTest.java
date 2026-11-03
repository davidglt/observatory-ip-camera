package local.termux.observatorycamera;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Dependency-free test of the camera.properties store. Run: bash test/run.sh */
public final class ConfigStoreTest {
    private static void check(boolean ok, String name) { if (!ok) throw new AssertionError(name); }
    private static void write(File f, String text) throws Exception { Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8)); }
    private static String read(File f) throws Exception { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("cfg").toFile();
        ConfigStore store = new ConfigStore(dir);
        File live = new File(dir, "camera.properties");

        // Missing file: defaults, nothing written by a read.
        ConfigStore.Loaded l = store.load();
        check(l.fileState.equals("missing") && l.config.describe().equals(CaptureConfig.defaults().describe()) && !live.exists(), "missing file uses defaults");

        // Update, persistence across a new store instance (service restart / app update keeps filesDir).
        check(store.update("overlay=off target_fps=2 position=top-right").config != null, "update ok");
        ConfigStore again = new ConfigStore(dir);
        check(again.load().config.describe().equals("overlay=off location=NYX position=top-right text_size=24 fields=location,datetime,fps,frames target_fps=2.0 text_color=green"), again.load().config.describe());
        String saved = read(live);
        check(saved.startsWith("#") && saved.contains("\ntarget_fps=2.0\n") && !new File(dir, "camera.properties.tmp").exists(), "readable commented file, no temp left");

        // Invalid update changes nothing.
        ConfigStore.Outcome bad = store.update("overlay=on text_size=5");
        check(bad.config == null && bad.error.startsWith("invalid-text_size") && read(live).equals(saved), "invalid update preserves file");

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

        // Invalid imports: whole file rejected, live file untouched, import file removed.
        String before = read(live);
        String[] badImports = {"location=ok\nbogus=1\n", "text_size=500\n", "target_fps=9\n", "overlay=maybe\n", "fields=gps\n", "position=middle\n",
            "text_color=red\n", "location=a\\u00zz\n", "location=" + new String(new char[30]).replace('\0', 'x') + "\n"};
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
        File other = Files.createTempDirectory("cfg2").toFile();
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
        File fresh = Files.createTempDirectory("cfg3").toFile();
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
    }
}
