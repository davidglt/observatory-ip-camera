package local.termux.observatorycamera;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

/** Persists one installation-specific MQTT client ID distinct from the BME280 producer identity. */
final class MqttClientIdStore {
    private static final String FILE_NAME = "mqtt-client.id";
    private static final String PREFIX = "observatory-camera-";
    private MqttClientIdStore() {}

    static synchronized String get(File filesDir) throws IOException {
        File idFile = new File(filesDir, FILE_NAME);
        if (idFile.isFile()) {
            String stored = new String(Files.readAllBytes(idFile.toPath()), StandardCharsets.US_ASCII).trim();
            if (stored.matches("observatory-camera-[0-9a-f]{20}")) return stored;
            throw new IOException("invalid-mqtt-client-id");
        }
        String id = PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        File temp = new File(filesDir, FILE_NAME + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temp)) {
            output.write(id.getBytes(StandardCharsets.US_ASCII));
            output.getFD().sync();
        }
        if (!temp.renameTo(idFile)) {
            temp.delete();
            throw new IOException("mqtt-client-id-write-failed");
        }
        return id;
    }
}
