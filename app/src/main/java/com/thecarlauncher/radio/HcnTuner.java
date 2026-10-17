package com.thecarlauncher.radio;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.util.Log;

/**
 * FM tuner backend for head units of the "hcn" platform (for example UIS8581A with ZA firmware).
 *
 * These units have no {@code android.qf.mcu} service. The tuner is owned by the factory radio app
 * ({@code com.hcn.autoradio}), which also renders the FM audio (AudioRecord source 1998 to
 * AudioTrack) and handles audio focus. The launcher therefore uses the factory app as a headless
 * engine, the same way {@code QfMediaBridge} does for Bluetooth:
 *
 * <ul>
 *   <li>{@code FMPlugService} ({@code com.hcn.autoradio.IRadioServiceAPI}) is started and bound.
 *       Starting it starts the audio render. Tuning, seeking and the band go through it.</li>
 *   <li>The system service {@code fmradio} ({@code android.radio.IRadioPlayer}) provides live
 *       state (frequency, stereo, RDS name and text) through an {@code IRadioCallback}, and mute.</li>
 * </ul>
 *
 * Everything is plain AIDL over {@link IBinder#transact}, so no hidden-API reflection is needed.
 * Transaction codes and parcel layouts were read from the factory APK (see NOTICE / docs).
 * Frequencies on this platform are in kHz (87500 = 87.5 MHz).
 */
final class HcnTuner {
    interface Listener {
        /** Frequency in kHz, plus tuner flags. Called on the main thread. */
        void onState(int freqKhz, boolean stereo, boolean seeking, boolean local);

        void onPs(String ps);

        void onRt(String rt);
    }

    static final String PKG = "com.hcn.autoradio";
    private static final String SERVICE_CLASS = "com.hcn.autoradio.service.FMPlugService";
    private static final String PLAYER_SERVICE = "fmradio";

    private static final String API = "com.hcn.autoradio.IRadioServiceAPI";
    private static final String PLAYER = "android.radio.IRadioPlayer";
    private static final String CALLBACK = "android.radio.IRadioCallback";

    // IRadioServiceAPI transaction codes
    private static final int A_BAND_EVENT = 5, A_LOCDX_EVENT = 8, A_SEEK_DOWN = 9, A_SEEK_UP = 10,
            A_GOTO_FREQ = 14, A_GET_BAND = 17, A_GET_FREQ = 18, A_IS_STEREO = 23, A_IS_DX_LOCAL = 24,
            A_REQUEST_PLAY = 25;
    // IRadioPlayer transaction codes
    private static final int P_SET_CALLBACK = 1, P_REMOVE_CALLBACK = 2, P_GET_INFO = 11, P_SET_MUTE = 14;
    // IRadioCallback transaction codes
    private static final int CB_EVENT = 1, CB_STRING_EVENT = 2;
    // RadioPlayer events carrying a string
    private static final int EV_PS = 38, EV_RT = 41;
    // RadioInfo.mScanType values that mean "the tuner is moving": scan (2), seek up (3), seek down (4)
    private static final int SCAN_PLAY = 2, SEEK_UP = 3, SEEK_DOWN = 4;

    private static final int FAIL = Integer.MIN_VALUE;
    private static final String TAG = "HcnTuner";

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("HcnTuner");
    private Handler io;

    private Listener listener;
    private Boolean supported;
    private String error;

    private boolean running;       // start() was called and stop() was not
    private boolean binding;
    private ServiceConnection connection;
    private IBinder api;
    private IBinder player;
    private Binder callback;
    private int pendingKhz;
    private boolean lastStereo, lastLocal;
    private int lastKhz;
    private int desiredLocal = -1; // -1 = leave as is

    HcnTuner(Context c) {
        app = c.getApplicationContext();
    }

    String error() { return error; }

    boolean isRunning() { return running && api != null; }

    /** True when this unit has the hcn radio stack. Cheap and cached. Does not start anything. */
    boolean connect(Listener l) {
        listener = l;
        if (supported != null) return supported;
        try {
            app.getPackageManager().getApplicationInfo(PKG, 0);
        } catch (PackageManager.NameNotFoundException e) {
            error = PKG + " not installed";
            supported = Boolean.FALSE;
            return false;
        }
        try {
            IBinder b = service(PLAYER_SERVICE);
            if (b == null) {
                error = PLAYER_SERVICE + " service not found";
                supported = Boolean.FALSE;
                return false;
            }
            String d = b.getInterfaceDescriptor();
            if (!PLAYER.equals(d)) {
                error = PLAYER_SERVICE + " has unexpected interface " + d;
                supported = Boolean.FALSE;
                return false;
            }
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : "");
            supported = Boolean.FALSE;
            return false;
        }
        error = null;
        supported = Boolean.TRUE;
        return true;
    }

    private static IBinder service(String name) throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        return (IBinder) sm.getMethod("getService", String.class).invoke(null, name);
    }

    private synchronized Handler io() {
        if (io == null) {
            thread.start();
            io = new Handler(thread.getLooper());
        }
        return io;
    }

    // ---- lifecycle -----------------------------------------------------------------------------

    /**
     * Starts the factory radio service (which also starts the FM audio) and tunes {@code khz}.
     * Safe to call repeatedly.
     */
    void start(final int khz) {
        pendingKhz = khz;
        running = true;
        if (api != null) {
            io().post(new Runnable() {
                @Override
                public void run() {
                    afterConnected();
                }
            });
            return;
        }
        if (binding) return;
        Intent i = new Intent().setComponent(new ComponentName(PKG, SERVICE_CLASS));
        try {
            app.startService(i); // like the factory app: keeps the audio render alive while bound
        } catch (Throwable t) {
            Log.w(TAG, "startService failed: " + t);
        }
        connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                api = binder;
                binding = false;
                io().post(new Runnable() {
                    @Override
                    public void run() {
                        afterConnected();
                    }
                });
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                api = null;
            }
        };
        try {
            binding = app.bindService(i, connection, Context.BIND_AUTO_CREATE);
            if (!binding) error = "cannot bind " + SERVICE_CLASS;
        } catch (Throwable t) {
            binding = false;
            error = t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : "");
        }
    }

    /** Runs on the io thread once the factory service is bound. */
    private void afterConnected() {
        if (!running) return;
        try {
            if (player == null) player = service(PLAYER_SERVICE);
        } catch (Throwable t) {
            player = null;
        }
        registerCallback();
        callInt(api, API, A_REQUEST_PLAY); // tells the platform that radio is the active source
        ensureFmBand();
        if (pendingKhz > 0) callVoid(api, API, A_GOTO_FREQ, pendingKhz);
        applyLocal();
        // Initial state, in case no event arrives (the callback only fires on changes).
        io().postDelayed(new Runnable() {
            @Override
            public void run() {
                fetchInfo();
            }
        }, 400);
    }

    /** Hands audio back: unregisters, stops the factory service so its render and focus go away. */
    void stop() {
        running = false;
        pendingKhz = 0;
        final IBinder p = player;
        final Binder cb = callback;
        callback = null;
        final ServiceConnection conn = connection;
        connection = null;
        binding = false;
        api = null;
        player = null;
        io().post(new Runnable() {
            @Override
            public void run() {
                if (p != null) {
                    callOneway(p, PLAYER, P_SET_MUTE, 1);
                    if (cb != null) callBinder(p, PLAYER, P_REMOVE_CALLBACK, cb, false);
                }
            }
        });
        try {
            if (conn != null) app.unbindService(conn);
        } catch (Throwable ignored) {
        }
        try {
            app.stopService(new Intent().setComponent(new ComponentName(PKG, SERVICE_CLASS)));
        } catch (Throwable ignored) {
        }
    }

    // ---- commands ------------------------------------------------------------------------------

    void tune(final int khz) {
        pendingKhz = khz;
        io().post(new Runnable() {
            @Override
            public void run() {
                if (api == null) return;
                callVoid(api, API, A_GOTO_FREQ, khz);
            }
        });
    }

    void seek(final boolean up) {
        io().post(new Runnable() {
            @Override
            public void run() {
                if (api == null) return;
                callVoid(api, API, up ? A_SEEK_UP : A_SEEK_DOWN);
            }
        });
    }

    /** Local (strong stations only) versus distance mode. The factory API only offers a toggle. */
    void setLocal(final boolean local) {
        desiredLocal = local ? 1 : 0;
        io().post(new Runnable() {
            @Override
            public void run() {
                applyLocal();
            }
        });
    }

    private void applyLocal() {
        if (api == null || desiredLocal < 0) return;
        int cur = callInt(api, API, A_IS_DX_LOCAL);
        if (cur == FAIL) return;
        if ((cur != 0) != (desiredLocal == 1)) callVoid(api, API, A_LOCDX_EVENT);
    }

    void mute(final boolean mute) {
        io().post(new Runnable() {
            @Override
            public void run() {
                if (player != null) callOneway(player, PLAYER, P_SET_MUTE, mute ? 1 : 0);
            }
        });
    }

    /** The factory band cycles FM1, FM2, FM3, AM1. The launcher only does FM, so skip AM. */
    private void ensureFmBand() {
        for (int i = 0; i < 4; i++) {
            int band = callInt(api, API, A_GET_BAND);
            if (band == FAIL || band <= 2) return;
            callVoid(api, API, A_BAND_EVENT);
        }
    }

    private void fetchInfo() {
        if (!running || player == null) return;
        Parcel r = call(player, PLAYER, P_GET_INFO, 0, null, false, null);
        if (r == null) return;
        try {
            if (r.readInt() != 0) parseInfo(r);
        } catch (Throwable t) {
            Log.w(TAG, "getRadioInfo: " + t);
        } finally {
            r.recycle();
        }
    }

    // ---- callback from the fmradio service ------------------------------------------------------

    private void registerCallback() {
        if (player == null || callback != null) return;
        Binder b = new Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                if (code == INTERFACE_TRANSACTION) {
                    if (reply != null) reply.writeString(CALLBACK);
                    return true;
                }
                if (code == CB_EVENT) { // onEvent(int event, RadioInfo info)
                    data.enforceInterface(CALLBACK);
                    data.readInt();
                    if (data.readInt() != 0) parseInfo(data);
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                if (code == CB_STRING_EVENT) { // onStringEvent(int event, String value)
                    data.enforceInterface(CALLBACK);
                    int ev = data.readInt();
                    final String s = data.readString();
                    if (reply != null) reply.writeNoException();
                    if (s != null) {
                        final String t = clean(s);
                        if (ev == EV_PS) postPs(t);
                        else if (ev == EV_RT) postRt(t);
                    }
                    return true;
                }
                return false;
            }
        };
        b.attachInterface(null, CALLBACK);
        Parcel r = call(player, PLAYER, P_SET_CALLBACK, 0, null, false, b);
        if (r != null) {
            r.recycle();
            callback = b;
        }
    }

    /** Reads a RadioInfo from the parcel (layout from RadioInfo.readFromParcel) and publishes it. */
    private void parseInfo(Parcel p) {
        final int freq = p.readInt();
        final int scanType = p.readInt();
        final boolean stereo = p.readInt() != 0;
        final boolean local = p.readInt() != 0;
        String band = p.readString();
        p.readInt();                  // index in the preset list
        String ps = p.readString();
        String rt = p.readString();
        // RDS state and PTY follow; not needed here.
        if (band != null && !band.startsWith("FM")) return;       // AM frequencies are not ours
        if (freq < 64000 || freq > 110000) return;
        final boolean seeking = scanType == SCAN_PLAY || scanType == SEEK_UP || scanType == SEEK_DOWN;
        lastKhz = freq;
        lastStereo = stereo;
        lastLocal = local;
        main.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) listener.onState(freq, stereo, seeking, local);
            }
        });
        if (ps != null && ps.trim().length() > 0) postPs(clean(ps));
        if (rt != null && rt.trim().length() > 0) postRt(clean(rt));
    }

    private void postPs(final String s) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) listener.onPs(s);
            }
        });
    }

    private void postRt(final String s) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) listener.onRt(s);
            }
        });
    }

    private static String clean(String s) {
        return s.replace('\u0000', ' ').replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s{2,}", " ").trim();
    }

    // ---- raw AIDL --------------------------------------------------------------------------------

    private int callInt(IBinder b, String desc, int code, int... args) {
        Parcel r = call(b, desc, code, 0, args, false, null);
        if (r == null) return FAIL;
        try {
            return r.readInt();
        } catch (Throwable t) {
            return FAIL;
        } finally {
            r.recycle();
        }
    }

    private void callVoid(IBinder b, String desc, int code, int... args) {
        Parcel r = call(b, desc, code, 0, args, false, null);
        if (r != null) r.recycle();
    }

    private void callOneway(IBinder b, String desc, int code, int... args) {
        call(b, desc, code, IBinder.FLAG_ONEWAY, args, true, null);
    }

    private void callBinder(IBinder b, String desc, int code, IBinder arg, boolean oneway) {
        Parcel r = call(b, desc, code, oneway ? IBinder.FLAG_ONEWAY : 0, null, oneway, arg);
        if (r != null) r.recycle();
    }

    /**
     * Writes an AIDL call (interface token, ints, optional strong binder) and transacts it.
     * For two-way calls returns the reply parcel positioned after the exception header (caller
     * recycles it); returns null on failure and for one-way calls.
     */
    private Parcel call(IBinder b, String desc, int code, int flags, int[] ints, boolean oneway, IBinder binderArg) {
        if (b == null) return null;
        Parcel d = Parcel.obtain();
        Parcel r = oneway ? null : Parcel.obtain();
        try {
            d.writeInterfaceToken(desc);
            if (ints != null) for (int v : ints) d.writeInt(v);
            if (binderArg != null) d.writeStrongBinder(binderArg);
            b.transact(code, d, r, flags);
            if (r != null) {
                r.readException();
                return r;
            }
            return null;
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : "");
            Log.w(TAG, desc + " #" + code + " failed: " + t);
            if (r != null) r.recycle();
            if (t instanceof android.os.DeadObjectException) {
                if (b == api) api = null;
                if (b == player) player = null;
            }
            return null;
        } finally {
            d.recycle();
        }
    }
}
