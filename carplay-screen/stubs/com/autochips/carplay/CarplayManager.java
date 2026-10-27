package com.autochips.carplay;

import android.os.Handler;
import android.view.Surface;
import java.util.List;

/** Compile-only stub: the real class lives in /system/framework/autochips-framework.jar on the unit. */
public class CarplayManager {
    public interface CarplayCallback {
        void onAppStateChanged(CarplayAppState state);
        void onAudioInfo(int streamId, int audioType, CarplayAudioInfo info);
        void onCallStateChanged(CarplayCall call);
        void onConnectionStateChanged(CarplayDevice device, int state);
        void onEnableStateChanged(int prevState, int newState);
        void onRequestAccessoryUI(int url);
        void onResourceStateChanged(CarplayResourceState state);
        void onStreamStateChanged(int streamId, int audioType, int streamState);
    }

    public static CarplayManager getDefaultManager() { throw new RuntimeException("stub"); }
    public int registerCallback(CarplayCallback callback, Handler handler) { throw new RuntimeException("stub"); }
    public List<CarplayDevice> getConnectedDevices() { throw new RuntimeException("stub"); }
    public int requestDeviceUI(int url, String telNumber) { throw new RuntimeException("stub"); }
    public int startVideo(Surface surface) { throw new RuntimeException("stub"); }
    public int stopVideo(boolean userSelect) { throw new RuntimeException("stub"); }
    public int updateTouchScreen(boolean press, int x, int y) { throw new RuntimeException("stub"); }
}
