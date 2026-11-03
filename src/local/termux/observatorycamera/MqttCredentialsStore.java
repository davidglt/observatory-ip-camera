package local.termux.observatorycamera;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/** Reads optional credentials from a private file deliberately excluded from settings APIs and exports. */
final class MqttCredentialsStore {
    static final String FILE_NAME = "mqtt-credentials.properties";
    static final int MAX_BYTES = 2048;

    static final class Credentials {
        final String username;
        final char[] password;
        Credentials(String username, char[] password) { this.username = username; this.password = password; }
    }

    private MqttCredentialsStore() {}

    static Credentials read(Context context) throws Exception {
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (!file.exists()) return new Credentials(null, null);
        if (file.length() > MAX_BYTES) throw new IllegalArgumentException("credentials-file-too-large");
        byte[] bytes;
        try (InputStream input = new FileInputStream(file)) {
            bytes = new byte[MAX_BYTES + 1];
            int count = 0;
            int n;
            while ((n = input.read(bytes, count, bytes.length - count)) > 0) {
                count += n;
                if (count > MAX_BYTES) throw new IllegalArgumentException("credentials-file-too-large");
            }
            bytes = java.util.Arrays.copyOf(bytes, count);
        }
        String text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        Properties properties = new Properties();
        properties.load(new StringReader(text));
        String username = properties.getProperty("username");
        String password = properties.getProperty("password");
        if (properties.size() > 2 || (username != null && username.length() > 128)
            || (password != null && password.length() > 256)
            || (username != null && username.isEmpty()) || (password != null && password.isEmpty())
            || (username == null) != (password == null))
            throw new IllegalArgumentException("invalid-credentials-file");
        if (!file.setReadable(false, false) || !file.setReadable(true, true))
            throw new IllegalStateException("credentials-file-permissions-unavailable");
        return new Credentials(username, password == null ? null : password.toCharArray());
    }
}
