package local.termux.observatorycamera;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public final class MainActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView text = new TextView(this);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        text.setPadding(padding, padding, padding, padding);
        text.setTextSize(18);
        text.setText("Observatory IP Camera\n\nVersion 0.1.0\n\nTermux build bootstrap.\nCamera capture and network streaming are not implemented yet.");
        setContentView(text);
    }
}
