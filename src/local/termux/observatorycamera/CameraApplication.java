package local.termux.observatorycamera;

import android.app.Application;
import android.util.Log;
import java.io.File;

public final class CameraApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Log.w("SnapshotHttp", "HTTP is exposed on all IPv4 interfaces without authentication or TLS. Trusted networks only.");
        SnapshotHttpServer server = new SnapshotHttpServer(new File(getFilesDir(), "snapshot.jpg"), message -> Log.i("SnapshotHttp", message));
        Thread worker = new Thread(server, "snapshot-http");
        worker.setDaemon(true);
        worker.start();
    }
}
