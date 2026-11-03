package local.termux.observatorycamera;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Authoritative persistent configuration: the human-readable UTF-8 file camera.properties in
 * app-private storage (see config/camera.properties.example for the schema). Pure Java, unit-tested.
 *
 * Reads: the file is read on every call to load(), so a saved or imported change applies to the next
 * frame without restarting anything. A missing file yields the defaults; a corrupt or invalid file
 * yields the last valid configuration seen by this process (or the defaults) and is reported, never
 * silently "repaired" on disk until an explicit update/reset/export rewrites it.
 * Writes: serialized by one process-wide lock; the complete new file is written to a temporary file,
 * synced and renamed over the live file, so a crash or concurrent update cannot leave a truncated file.
 * An invalid update or import changes nothing.
 */
final class ConfigStore {
    static final String FILE_NAME = "camera.properties";
    static final String IMPORT_NAME = "camera.import";
    static final int MAX_FILE_BYTES = 8192;
    private static final Object LOCK = new Object();
    private static final Map<String, CaptureConfig> LAST_VALID = new HashMap<>();
    interface Validator { String validate(CaptureConfig config); }
    private static final Validator NO_VALIDATION = config -> null;

    /** Effective configuration plus the state of the file it came from: ok, missing or corrupt. */
    static final class Loaded {
        final CaptureConfig config;
        final String fileState;
        final String error;
        Loaded(CaptureConfig config, String fileState, String error) { this.config = config; this.fileState = fileState; this.error = error; }
    }

    /** Outcome of a mutation: exactly one of config and error is non-null. */
    static final class Outcome {
        final CaptureConfig config;
        final String error;
        Outcome(CaptureConfig config, String error) { this.config = config; this.error = error; }
    }

    private final File live;
    private final File temp;
    private final File importFile;

    ConfigStore(File dir) {
        live = new File(dir, FILE_NAME);
        temp = new File(dir, FILE_NAME + ".tmp");
        importFile = new File(dir, IMPORT_NAME);
    }

    File importFile() { return importFile; }

    Loaded load() {
        synchronized (LOCK) { return loadLocked(); }
    }

    private Loaded loadLocked() {
        String key = live.getAbsolutePath();
        if (!live.exists()) return new Loaded(CaptureConfig.defaults(), "missing", null);
        try {
            CaptureConfig parsed = parseStrict(readBounded(live));
            LAST_VALID.put(key, parsed);
            return new Loaded(parsed, "ok", null);
        } catch (IOException | RuntimeException e) {
            CaptureConfig fallback = LAST_VALID.get(key);
            return new Loaded(fallback != null ? fallback : CaptureConfig.defaults(), "corrupt", String.valueOf(e.getMessage()));
        }
    }

    /** Applies SSH-style assignments to the effective configuration and saves it; invalid input changes nothing. */
    Outcome update(String assignments) {
        return update(assignments, NO_VALIDATION);
    }

    Outcome update(String assignments, Validator validator) {
        synchronized (LOCK) {
            CaptureConfig.Parsed parsed = CaptureConfig.parse(assignments, loadLocked().config);
            if (parsed.config == null) return new Outcome(null, parsed.error);
            String error = validator.validate(parsed.config);
            if (error != null) return new Outcome(null, error);
            return save(parsed.config);
        }
    }

    Outcome updateEntries(Map<String, String> entries, Validator validator) {
        synchronized (LOCK) {
            CaptureConfig.Parsed parsed = CaptureConfig.updateEntries(entries, loadLocked().config);
            if (parsed.config == null) return new Outcome(null, parsed.error);
            String error = validator.validate(parsed.config);
            if (error != null) return new Outcome(null, error);
            return save(parsed.config);
        }
    }

    Outcome reset() {
        return reset(NO_VALIDATION);
    }

    Outcome reset(Validator validator) {
        synchronized (LOCK) {
            CaptureConfig defaults = CaptureConfig.defaults();
            String error = validator.validate(defaults);
            return error == null ? save(defaults) : new Outcome(null, error);
        }
    }

    /** Writes the effective configuration (normalized) so the live file exists and is valid, e.g. before an export. */
    Outcome materialize() {
        synchronized (LOCK) { return save(loadLocked().config); }
    }

    /**
     * Validates the complete import file first; only a fully valid file replaces the live configuration.
     * The import file is always removed afterwards so a stale upload can never be applied later.
     */
    Outcome importPending() {
        return importPending(NO_VALIDATION);
    }

    Outcome importPending(Validator validator) {
        synchronized (LOCK) {
            try {
                if (!importFile.exists()) return new Outcome(null, "no-import-file-uploaded");
                CaptureConfig parsed;
                try {
                    parsed = parseStrict(readBounded(importFile));
                } catch (IOException | RuntimeException e) {
                    return new Outcome(null, "invalid-import:" + sanitize(e.getMessage()));
                }
                String error = validator.validate(parsed);
                if (error != null) return new Outcome(null, error);
                return save(parsed);
            } finally {
                importFile.delete();
            }
        }
    }

    private Outcome save(CaptureConfig config) {
        try {
            writeAtomic(config);
            LAST_VALID.put(live.getAbsolutePath(), config);
            return new Outcome(config, null);
        } catch (IOException e) {
            return new Outcome(null, "write-failed:" + sanitize(e.getMessage()));
        }
    }

    private void writeAtomic(CaptureConfig config) throws IOException {
        byte[] bytes = serialize(config);
        try (FileOutputStream out = new FileOutputStream(temp)) {
            Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
            w.write(new String(bytes, StandardCharsets.UTF_8));
            w.flush();
            out.getFD().sync();
        } catch (IOException e) {
            temp.delete();
            throw e;
        }
        if (!temp.renameTo(live)) {
            temp.delete();
            throw new IOException("rename failed");
        }
    }

    /** Deterministic, commented, human-readable properties text (UTF-8 bytes). */
    static byte[] serialize(CaptureConfig config) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Observatory IP Camera configuration (java.util.Properties, UTF-8).\n");
        sb.append("# Edit over SSH with camera-control.sh config-set / config-import; schema: config/camera.properties.example.\n");
        for (Map.Entry<String, String> e : config.toMap().entrySet()) sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Strict parse of a complete properties file: bounded size, strict UTF-8, known keys, valid values. Missing keys use defaults. */
    static CaptureConfig parseStrict(byte[] bytes) throws IOException {
        if (bytes.length > MAX_FILE_BYTES) throw new IOException("file-too-large:max-" + MAX_FILE_BYTES + "-bytes");
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException("not-valid-utf-8");
        }
        Properties props = new Properties();
        try (Reader reader = new InputStreamReader(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IllegalArgumentException e) {
            throw new IOException("malformed-properties");
        }
        Map<String, String> entries = new HashMap<>();
        for (String name : props.stringPropertyNames()) entries.put(name, props.getProperty(name).trim());
        CaptureConfig.Parsed parsed = CaptureConfig.fromEntries(entries);
        if (parsed.config == null) throw new IOException(parsed.error);
        return parsed.config;
    }

    private static byte[] readBounded(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_FILE_BYTES) throw new IOException("file-too-large:max-" + MAX_FILE_BYTES + "-bytes");
            }
            return out.toByteArray();
        }
    }

    private static String sanitize(String s) {
        return s == null ? "unknown" : s.replaceAll("[^A-Za-z0-9_.:|,=-]+", "-");
    }
}
