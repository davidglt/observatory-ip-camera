package local.termux.observatorycamera;

import java.nio.file.Files;

public final class MqttClientIdStoreTest {
    public static void main(String[] args) throws Exception {
        java.io.File dir = Files.createTempDirectory("mqtt-id").toFile();
        try {
            String first = MqttClientIdStore.get(dir);
            check(first.equals(MqttClientIdStore.get(dir)), "client ID remains stable across starts");
            check(first.startsWith("observatory-camera-") && !first.equals("bme280-nyx"),
                "subscriber identity differs from sensor producer");
            System.out.println("MqttClientIdStoreTest passed");
        } finally {
            for (java.io.File file : dir.listFiles()) file.delete();
            dir.delete();
        }
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
