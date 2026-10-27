package app.carplayscreen;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;
import com.autochips.carplay.CarplayAppState;
import com.autochips.carplay.CarplayAudioInfo;
import com.autochips.carplay.CarplayCall;
import com.autochips.carplay.CarplayDevice;
import com.autochips.carplay.CarplayManager;
import com.autochips.carplay.CarplayResourceState;

/**
 * Stays running so the CarPlay screen opens when the iPhone connects. The unit's service only
 * auto-starts system apps, so a normal app has to listen for itself.
 */
public class Watcher extends Service implements CarplayManager.CarplayCallback {
    private static final String TAG = "CarPlayScreen";
    private static final int CONNECTION_STATE_CONNECTED = 2; // CarplayCommon
    private static final int ENTITY_CONTROLLER = 1; // CarplayCommon: the iPhone owns the screen

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("carplay", "CarPlay", NotificationManager.IMPORTANCE_MIN));
        startForeground(1, new Notification.Builder(this, "carplay")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("CarPlay ready")
                .build());
        CarplayManager cp = CarplayManager.getDefaultManager();
        // the manager re-registers this by itself whenever the CarPlay service restarts
        int result = cp == null ? -1 : cp.registerCallback(this, null);
        Log.i(TAG, "registerCallback -> " + result);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void show() {
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    @Override
    public void onConnectionStateChanged(CarplayDevice device, int state) {
        Log.i(TAG, "connection state " + state);
        if (state == CONNECTION_STATE_CONNECTED) show();
    }

    @Override
    public void onResourceStateChanged(CarplayResourceState state) {
        Log.i(TAG, "main screen owner " + state.getMainScreen());
        if (state.getMainScreen() == ENTITY_CONTROLLER) show();
    }

    @Override
    public void onRequestAccessoryUI(int url) {
        Log.i(TAG, "iPhone asked for the car's own UI");
        // the user tapped the car icon inside CarPlay: go back to the unit's home screen
        startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @Override public void onAppStateChanged(CarplayAppState state) {}
    @Override public void onAudioInfo(int streamId, int audioType, CarplayAudioInfo info) {}
    @Override public void onCallStateChanged(CarplayCall call) {}
    @Override public void onEnableStateChanged(int prevState, int newState) {}
    @Override public void onStreamStateChanged(int streamId, int audioType, int streamState) {}

    public static class Boot extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            context.startForegroundService(new Intent(context, Watcher.class));
        }
    }
}
