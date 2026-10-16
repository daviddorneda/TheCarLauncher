package com.thecarlauncher.radio;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.pm.ActivityInfo;
import android.media.AudioManager;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Collects everything this head unit exposes that could drive its FM tuner: system services,
 * vendor framework classes, radio / MCU related packages (with their services, receivers and
 * activities) and audio parameters. The report is what is needed to write a tuner backend for a
 * platform other than QuickFish (for example UIS8581A units with "ZA" firmware).
 *
 * Everything is read-only; nothing is sent to the MCU.
 */
public final class RadioProbe {
    private static final Pattern PKG = Pattern.compile(
            "(?i).*(radio|\\bfm\\b|[.]fm|fm[.]|tuner|mcu|za[.]|[.]za|zhian|zhi[.]an|hct|syu|qf|raise|canbus|"
                    + "autokit|carservice|vehicle|dsp|amplifier|audiosetting|factory).*");
    private static final Pattern SERVICE = Pattern.compile(
            "(?i).*(mcu|radio|fm|tuner|za|zhian|hct|syu|qf|can|vehicle|car|audio_?ext|dsp|serial|uart|hcn).*");
    private static final String[] CLASSES = {
            "android.qf.mcu.IMcuManager",
            "android.hardware.radio.RadioManager",
            "android.media.AudioSystem",
            "com.android.fmradio.FmNative",
            "com.hcn.fm.FmManager",
            "com.syu.ms.IRemoteModule",
            "com.zhian.mcu.IMcuManager",
            "com.zhian.mcu.McuManager",
            "android.za.mcu.IMcuManager",
    };
    private static final String[] AUDIO_KEYS = {
            "fm_radio_on", "fm_mute", "fm_freq", "fm_volume", "fm_status", "FM_status", "mcu_channel",
    };

    private RadioProbe() {}

    /** Builds the full report, one line per entry (so it can be shown as rows or saved as text). */
    public static List<String> run(Context c) {
        List<String> out = new ArrayList<>();
        out.add("## Device");
        out.add("model: " + Build.MANUFACTURER + " / " + Build.MODEL + " / " + Build.DEVICE);
        out.add("android: " + Build.VERSION.RELEASE + " (sdk " + Build.VERSION.SDK_INT + ")");
        out.add("fingerprint: " + Build.FINGERPRINT);
        for (String k : new String[]{"ro.board.platform", "ro.product.board", "ro.hardware", "ro.build.display.id",
                "ro.vendor.product.name"}) {
            out.add(k + ": " + prop(k));
        }

        out.add("## System services (filtered)");
        boolean any = false;
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            String[] names = (String[]) sm.getMethod("listServices").invoke(null);
            if (names != null) {
                java.util.Arrays.sort(names);
                for (String n : names) {
                    if (n != null && SERVICE.matcher(n).matches()) {
                        out.add(n);
                        any = true;
                    }
                }
                out.add("(total services: " + names.length + ")");
            }
        } catch (Throwable t) {
            out.add("listServices failed: " + t.getClass().getSimpleName());
        }
        if (!any) out.add("(none matched)");

        out.add("## Vendor classes visible to apps");
        for (String cn : CLASSES) {
            String r;
            try {
                Class.forName(cn);
                r = "PRESENT";
            } catch (Throwable t) {
                r = "missing";
            }
            out.add(cn + ": " + r);
        }

        out.add("## Audio parameters");
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        for (String k : AUDIO_KEYS) {
            String v = "";
            try {
                v = am.getParameters(k);
            } catch (Throwable ignored) {
            }
            out.add(k + ": " + (v == null || v.length() == 0 ? "(empty)" : v));
        }

        out.add("## Related packages");
        PackageManager pm = c.getPackageManager();
        List<PackageInfo> all;
        try {
            all = pm.getInstalledPackages(PackageManager.GET_SERVICES | PackageManager.GET_RECEIVERS
                    | PackageManager.GET_ACTIVITIES);
        } catch (Throwable t) {
            all = new ArrayList<>();
            out.add("getInstalledPackages failed: " + t.getClass().getSimpleName());
        }
        for (PackageInfo p : all) {
            if (p.packageName.equals(c.getPackageName())) continue;
            String label = "";
            try {
                label = String.valueOf(pm.getApplicationLabel(p.applicationInfo));
            } catch (Throwable ignored) {
            }
            if (!PKG.matcher(p.packageName + " " + label).matches()) continue;
            boolean system = p.applicationInfo != null && (p.applicationInfo.flags
                    & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
            out.add(String.format(Locale.US, "%s  [%s]  v%s%s", p.packageName, label,
                    p.versionName, system ? "  (system)" : ""));
            if (p.services != null) {
                for (ServiceInfo s : p.services) {
                    out.add("    service " + s.name + (s.exported ? "  exported" : "")
                            + (s.permission != null ? "  perm=" + s.permission : ""));
                }
            }
            if (p.receivers != null) {
                int n = 0;
                for (ActivityInfo r : p.receivers) {
                    if (n++ >= 12) {
                        out.add("    … " + (p.receivers.length - 12) + " more receivers");
                        break;
                    }
                    out.add("    receiver " + r.name + (r.exported ? "  exported" : ""));
                }
            }
            if (p.activities != null && p.activities.length > 0) {
                int n = 0;
                for (ActivityInfo a : p.activities) {
                    if (n++ >= 6) {
                        out.add("    … " + (p.activities.length - 6) + " more activities");
                        break;
                    }
                    out.add("    activity " + a.name);
                }
            }
        }

        out.add("## Next step");
        out.add("Intent actions of the factory radio app cannot be read from here. If you have adb:");
        out.add("adb shell dumpsys package <radio package> | grep -A3 -i \"Action:\"");
        out.add("adb logcat -v time | grep -i -E \"fm|radio|mcu\"   (while changing station in the factory radio app)");
        return out;
    }

    /** Writes the report to /sdcard/radio-probe.txt (or the app's own folder). Returns the path or null. */
    public static String save(Context c, List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        File[] targets = {new File("/sdcard/radio-probe.txt"), new File(c.getExternalFilesDir(null), "radio-probe.txt")};
        for (File f : targets) {
            try {
                FileOutputStream o = new FileOutputStream(f);
                o.write(sb.toString().getBytes("UTF-8"));
                o.close();
                return f.getAbsolutePath();
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String prop(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class, String.class);
            return (String) get.invoke(null, key, "");
        } catch (Throwable t) {
            return "";
        }
    }
}
