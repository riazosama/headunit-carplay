package app.carplayscreen;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Toast;
import android.util.Log;
import com.autochips.carplay.CarplayManager;
import java.util.List;

/** Full-screen surface the unit's CarPlay service renders the iPhone's video into. */
public class MainActivity extends Activity implements SurfaceHolder.Callback, View.OnTouchListener {

    private static final String TAG = "CarPlayRemap";

    private static final int URL_ID_EMPTY = 1; // CarplayCommon.URL_ID_EMPTY: bring up CarPlay's last screen

    // Values discovered in the head unit's CarplayCommon class.
    private static final int MEDIA_NEXT_TRACK = 4;
    private static final int MEDIA_PRE_TRACK = 5;
    // "No button pressed" - sent on key release so CarPlay never sees a held button.
    private static final int MEDIA_NONE = 0;

    private CarplayManager cp;

    // True when a CarPlay device was connected at the last onResume().
    private boolean carPlayConnected = false;

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

        carPlayConnected = isCarPlayConnected();

        if (!carPlayConnected) {
            Toast.makeText(
                    this,
                    "Plug in your iPhone",
                    Toast.LENGTH_LONG
            ).show();

            return;
        }

        cp.requestDeviceUI(URL_ID_EMPTY, ""); // ask the iPhone to take the screen
    }

    /** Live check, so the answer is never stale. */
    private boolean isCarPlayConnected() {
        List<?> devices = cp == null ? null : cp.getConnectedDevices();
        return devices != null && !devices.isEmpty();
    }

    /** Send a CarPlay media button value, never letting an error crash the app. */
    private int sendMedia(int button) {
        try {
            return cp.updateMedia(button);
        } catch (Throwable t) {
            Log.w(TAG, "updateMedia(" + button + ") failed", t);
            return -1;
        }
    }

    /**
     * On the first DOWN: press the button then immediately release it.
     * This forces CarPlay to see a tap (skip track) rather than a hold
     * (seek/scrub), regardless of how long the user physically holds
     * the steering-wheel button.
     * ACTION_UP is intentionally ignored — we already released on DOWN.
     */
    private void handleMediaKey(KeyEvent event, int button, String name) {
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0) {
            int r = sendMedia(button);
            Log.d(TAG, "press " + name + " -> updateMedia returned " + r);
            // Immediately release so CarPlay never sees a held button.
            sendMedia(MEDIA_NONE);
            Log.d(TAG, "auto-release " + name);
        }
        // ACTION_UP: nothing to do — already released above.
    }

    /** Never leave a button "held" if the screen goes away mid-press. */
    @Override
    protected void onPause() {
        super.onPause();
        if (cp != null) {
            sendMedia(MEDIA_NONE);
        }
    }

    /**
     * Intercept steering-wheel media buttons.
     *
     * While CarPlay is connected and this screen is focused:
     * Next     -> CarPlay next track
     * Previous -> CarPlay previous track
     *
     * Otherwise Android handles the original button behavior.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {

        int keyCode = event.getKeyCode();

        if (cp != null && isCarPlayConnected()) {

            if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) {
                handleMediaKey(event, MEDIA_NEXT_TRACK, "next");
                // Consume both DOWN and UP events.
                return true;
            }

            if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
                handleMediaKey(event, MEDIA_PRE_TRACK, "previous");
                // Consume both DOWN and UP events.
                return true;
            }
        }

        // Preserve the original behavior for all other keys,
        // and when CarPlay is not connected.
        return super.dispatchKeyEvent(event);
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
