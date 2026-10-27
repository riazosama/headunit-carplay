package app.carplayscreen;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Toast;
import com.autochips.carplay.CarplayManager;
import java.util.List;

/** Full-screen surface the unit's CarPlay service renders the iPhone's video into. */
public class MainActivity extends Activity implements SurfaceHolder.Callback, View.OnTouchListener {
    private static final int URL_ID_EMPTY = 1; // CarplayCommon.URL_ID_EMPTY: bring up CarPlay's last screen

    private CarplayManager cp;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        startForegroundService(new Intent(this, Watcher.class));
        cp = CarplayManager.getDefaultManager();
        SurfaceView view = new SurfaceView(this);
        view.getHolder().addCallback(this);
        view.setOnTouchListener(this);
        setContentView(view);
    }

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        List<?> devices = cp == null ? null : cp.getConnectedDevices();
        if (devices == null || devices.isEmpty()) {
            Toast.makeText(this, "Plug in your iPhone", Toast.LENGTH_LONG).show();
            return;
        }
        cp.requestDeviceUI(URL_ID_EMPTY, ""); // ask the iPhone to take the screen
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (cp != null) cp.startVideo(holder.getSurface());
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (cp != null) cp.stopVideo(true); // true = user left CarPlay, screen goes back to the unit
    }

    // ponytail: single finger only; the service API takes one point, add pinch if Maps needs it
    @Override
    public boolean onTouch(View v, MotionEvent e) {
        int action = e.getActionMasked();
        if (cp == null || action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) return true;
        boolean pressed = action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL;
        cp.updateTouchScreen(pressed, (int) e.getX(), (int) e.getY());
        return true;
    }
}
