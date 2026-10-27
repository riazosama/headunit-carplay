package com.cpbridge;


import vendor.autochips.hardware.iap.V1_0.CallState;
import vendor.autochips.hardware.iap.V1_0.ConnectionStatus;
import vendor.autochips.hardware.iap.V1_0.DatabaseInfo;
import vendor.autochips.hardware.iap.V1_0.IAPDev;
import vendor.autochips.hardware.iap.V1_0.IIAPClient;
import vendor.autochips.hardware.iap.V1_0.LocationEvent;
import vendor.autochips.hardware.iap.V1_0.PlaybackStatus;

/**
 * IIAPClient implementation passed to IIAPService.createCarplay().  The HAL
 * invokes these from its own binder threads; they must be fast and must never
 * call back into the HAL synchronously (the bridge posts the Wi-Fi handoff to a
 * worker thread instead, see BridgeService).
 *
 * Equivalent of the reference client's com.autochips.carplay.a.b
 * (<internal notes>).
 */
final class IapClientStub extends IIAPClient.Stub {

    interface Listener {
        void onConnectStatusChanged(IAPDev dev, int status);
    }

    private final Listener mListener;

    IapClientStub(Listener listener) {
        mListener = listener;
    }

    @Override
    public void onConnectStatusChanged(IAPDev dev, int status) {
        String id = (dev == null) ? "?" : dev.id;
        int transportType = (dev == null) ? -1 : dev.transportType;
        Cplog.i(BridgeConfig.TAG_CLIENT, "onConnectStatusChanged status=" + status
                + " (" + ConnectionStatus.toString(status) + ")"
                + " dev.id=" + id + " transportType=" + transportType);
        Listener l = mListener;
        if (l != null) {
            try {
                l.onConnectStatusChanged(dev, status);
            } catch (Throwable t) {
                Cplog.w(BridgeConfig.TAG_CLIENT, "listener threw", t);
            }
        }
    }

    @Override
    public void onCallStateUpdate(IAPDev dev, CallState callState) {
        Cplog.d(BridgeConfig.TAG_CLIENT, "onCallStateUpdate dev.id="
                + (dev == null ? "?" : dev.id));
    }

    @Override
    public void onDatabaseInfoChanged(IAPDev dev, DatabaseInfo info) {
        Cplog.d(BridgeConfig.TAG_CLIENT, "onDatabaseInfoChanged dev.id="
                + (dev == null ? "?" : dev.id));
    }

    @Override
    public void onEapReady(IAPDev dev, boolean ready) {
        Cplog.i(BridgeConfig.TAG_CLIENT, "onEapReady dev.id="
                + (dev == null ? "?" : dev.id) + " ready=" + ready);
    }

    @Override
    public void onLocationStart(IAPDev dev, LocationEvent event) {
        Cplog.d(BridgeConfig.TAG_CLIENT, "onLocationStart dev.id="
                + (dev == null ? "?" : dev.id));
    }

    @Override
    public void onPlaybackStatusChanged(IAPDev dev, PlaybackStatus status) {
        Cplog.d(BridgeConfig.TAG_CLIENT, "onPlaybackStatusChanged dev.id="
                + (dev == null ? "?" : dev.id));
    }

    @Override
    public void onSamplerateChanged(IAPDev dev, int sampleRate) {
        Cplog.d(BridgeConfig.TAG_CLIENT, "onSamplerateChanged dev.id="
                + (dev == null ? "?" : dev.id) + " rate=" + sampleRate);
    }
}
