package com.thecarlauncher.radio;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.pm.ActivityInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.IBinder;

import dalvik.system.DexClassLoader;
import dalvik.system.DexFile;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Enumeration;
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

        binders(out);
        scanClasses(c, out);

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

    // ---- binder services: interface descriptors -------------------------------------------------

    private static final String[] BINDERS = {"mcu_service", "fmradio", "broadcastradio", "serial", "carstatus"};

    private static void binders(List<String> out) {
        out.add("## Binder services (interface descriptors)");
        for (String name : BINDERS) {
            try {
                Class<?> sm = Class.forName("android.os.ServiceManager");
                IBinder b = (IBinder) sm.getMethod("getService", String.class).invoke(null, name);
                if (b == null) {
                    out.add(name + ": (null)");
                    continue;
                }
                String d = "?";
                try {
                    d = b.getInterfaceDescriptor();
                } catch (Throwable t) {
                    d = "error " + t.getClass().getSimpleName();
                }
                out.add(name + ": " + d + "  alive=" + b.pingBinder());
                if (d.indexOf('.') > 0) {
                    dumpClass(out, d, null);
                    dumpClass(out, d + "$Stub", null);
                }
            } catch (Throwable t) {
                out.add(name + ": failed " + t.getClass().getSimpleName());
            }
        }
    }

    // ---- class scan: framework jars and the factory radio app -----------------------------------

    private static final Pattern STRICT = Pattern.compile("(?i).*(hcn|mcu|fmradio|\\.fm\\.|\\.ifm|tuner).*");
    private static final String[] JAR_DIRS = {"/system/framework", "/system_ext/framework", "/vendor/framework",
            "/product/framework", "/vendor/app", "/system/priv-app/FmRadio"};
    private static final String RADIO_PKG = "com.hcn.autoradio";

    private static void scanClasses(Context c, List<String> out) {
        out.add("## Classes (framework)");
        int dumped = 0;
        for (String dir : JAR_DIRS) {
            File[] files = new File(dir).listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (!f.getName().endsWith(".jar") && !f.getName().endsWith(".apk")) continue;
                List<String> hits = matches(f.getAbsolutePath(), 60);
                if (hits.isEmpty()) continue;
                out.add(f.getAbsolutePath());
                for (String h : hits) {
                    out.add("    " + h);
                    if (dumped < 15 && (h.contains("$Stub") || h.matches(".*[.]I[A-Z][A-Za-z]*$"))) {
                        dumped++;
                        dumpClass(out, h, null);
                    }
                }
            }
        }

        out.add("## Classes (" + RADIO_PKG + ")");
        try {
            String apk = c.getPackageManager().getApplicationInfo(RADIO_PKG, 0).sourceDir;
            out.add("apk: " + apk);
            for (String n : allClasses(apk, 400)) out.add("    " + n);
            copyApk(c, apk, out);
            vendorDump(c, apk, out);
        } catch (Throwable t) {
            out.add("failed: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    // ---- full interface dump, loaded from the factory radio APK itself --------------------------

    /**
     * Class loader that prefers the classes of the factory radio APK over the (hidden-API protected)
     * copies in the boot classpath, so reflection sees every method and every TRANSACTION_* code.
     */
    private static final class ChildFirst extends DexClassLoader {
        ChildFirst(String dex, String opt, ClassLoader parent) {
            super(dex, opt, null, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            Class<?> k = findLoadedClass(name);
            if (k == null) {
                try {
                    k = findClass(name);
                } catch (ClassNotFoundException e) {
                    return super.loadClass(name, resolve);
                }
            }
            if (resolve) resolveClass(k);
            return k;
        }
    }

    private static final String[] VENDOR = {
            "android.radio.IRadioPlayer", "android.radio.IRadioPlayer$Stub",
            "android.radio.IRadioCallback", "android.radio.IRadioCallback$Stub",
            "android.radio.RadioPlayer", "android.radio.RadioPlayer$BAND", "android.radio.RadioPlayer$OnEventListener",
            "android.radio.RadioInfo", "android.radio.Utility$RadioParameters",
            "android.carsource.IMcuManager", "android.carsource.IMcuManager$Stub",
            "android.carsource.IMcuListener", "android.carsource.IMcuListener$Stub",
            "android.carsource.McuConstant", "android.carsource.McuManager",
            "android.carsource.McuManager$OnEventListener",
            "android.carstatus.ICarStatus", "android.carstatus.ICarStatus$Stub",
    };

    private static void vendorDump(Context c, String apk, List<String> out) {
        out.add("## Vendor interfaces (loaded from " + RADIO_PKG + ")");
        ClassLoader cl;
        try {
            cl = new ChildFirst(apk, c.getCodeCacheDir().getAbsolutePath(), c.getClassLoader());
        } catch (Throwable t) {
            out.add("loader failed: " + t.getClass().getSimpleName());
            return;
        }
        for (String name : VENDOR) {
            try {
                Class<?> k = Class.forName(name, false, cl);
                Class<?> stub = null;
                if (k.isInterface()) {
                    try {
                        stub = Class.forName(name + "$Stub", false, cl);
                    } catch (Throwable ignored) {
                    }
                }
                out.add("  [class] " + name + (k.isInterface() ? " (interface)" : "")
                        + (k.getSuperclass() != null && k.getSuperclass() != Object.class
                        ? " extends " + k.getSuperclass().getName() : ""));
                int nf = 0;
                for (Field f : k.getDeclaredFields()) {
                    if (nf >= 150) {
                        out.add("      …");
                        break;
                    }
                    try {
                        f.setAccessible(true);
                        if (Modifier.isStatic(f.getModifiers())) {
                            Class<?> t = f.getType();
                            if (t.isPrimitive() || t == String.class) {
                                nf++;
                                out.add("      const " + f.getName() + " = " + f.get(null));
                            }
                        } else {
                            nf++;
                            out.add("      field " + f.getType().getSimpleName() + " " + f.getName());
                        }
                    } catch (Throwable ignored) {
                    }
                }
                List<String> ms = new ArrayList<>();
                for (Method m : k.getDeclaredMethods()) {
                    if (m.isSynthetic()) continue;
                    StringBuilder sb = new StringBuilder();
                    int code = -1;
                    if (stub != null) {
                        try {
                            Field f = stub.getDeclaredField("TRANSACTION_" + m.getName());
                            f.setAccessible(true);
                            code = f.getInt(null);
                        } catch (Throwable ignored) {
                        }
                    }
                    sb.append(code >= 0 ? String.format(Locale.US, "%04d", code) : "----").append("  ");
                    sb.append(m.getReturnType().getSimpleName()).append(' ').append(m.getName()).append('(');
                    Class<?>[] ps = m.getParameterTypes();
                    for (int i = 0; i < ps.length; i++) sb.append(i > 0 ? ", " : "").append(ps[i].getName());
                    ms.add(sb.append(')').toString());
                }
                java.util.Collections.sort(ms);
                int nm = 0;
                for (String m : ms) {
                    if (nm++ >= 160) {
                        out.add("      …");
                        break;
                    }
                    out.add("      " + m);
                }
            } catch (Throwable t) {
                out.add("  [class] " + name + ": not loadable (" + t.getClass().getSimpleName() + ")");
            }
        }
    }

    private static List<String> matches(String path, int max) {
        List<String> r = new ArrayList<>();
        try {
            DexFile df = new DexFile(path);
            Enumeration<String> e = df.entries();
            while (e.hasMoreElements() && r.size() < max) {
                String n = e.nextElement();
                if (STRICT.matcher(n).matches()) r.add(n);
            }
            df.close();
        } catch (Throwable ignored) {
        }
        return r;
    }

    private static List<String> allClasses(String path, int max) {
        List<String> r = new ArrayList<>();
        try {
            DexFile df = new DexFile(path);
            Enumeration<String> e = df.entries();
            while (e.hasMoreElements() && r.size() < max) {
                String n = e.nextElement();
                if (n.startsWith("com.hcn.autoradio") || n.startsWith("android.radio") || n.startsWith("android.carsource")
                        || n.startsWith("android.carstatus") || n.startsWith("android.sourceservice")) r.add(n);
            }
            df.close();
        } catch (Throwable ignored) {
        }
        java.util.Collections.sort(r);
        return r;
    }

    /** Prints methods, constants (AIDL TRANSACTION_* codes) and the DESCRIPTOR of a class. */
    private static void dumpClass(List<String> out, String name, ClassLoader cl) {
        try {
            Class<?> k = cl == null ? Class.forName(name) : Class.forName(name, false, cl);
            out.add("  [class] " + name + (k.isInterface() ? " (interface)" : ""));
            for (Field f : k.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                if (f.getType() != int.class && f.getType() != String.class) continue;
                try {
                    f.setAccessible(true);
                    out.add("      const " + f.getName() + " = " + f.get(null));
                } catch (Throwable ignored) {
                }
            }
            int n = 0;
            for (Method m : k.getDeclaredMethods()) {
                if (n++ >= 80) {
                    out.add("      …");
                    break;
                }
                StringBuilder sb = new StringBuilder("      " + m.getReturnType().getSimpleName() + " " + m.getName() + "(");
                Class<?>[] ps = m.getParameterTypes();
                for (int i = 0; i < ps.length; i++) sb.append(i > 0 ? ", " : "").append(ps[i].getSimpleName());
                out.add(sb.append(")").toString());
            }
        } catch (Throwable t) {
            out.add("  [class] " + name + ": not loadable (" + t.getClass().getSimpleName() + ")");
        }
    }

    private static void copyApk(Context c, String apk, List<String> out) {
        File[] dirs = {new File("/sdcard/radio-probe"), new File(c.getExternalFilesDir(null), "radio-probe")};
        for (File dir : dirs) {
            try {
                if (!dir.exists() && !dir.mkdirs()) continue;
                File dst = new File(dir, RADIO_PKG + ".apk");
                java.io.FileInputStream in = new java.io.FileInputStream(apk);
                FileOutputStream o = new FileOutputStream(dst);
                byte[] buf = new byte[65536];
                int r;
                while ((r = in.read(buf)) > 0) o.write(buf, 0, r);
                o.close();
                in.close();
                out.add("apk copied to " + dst.getAbsolutePath());
                return;
            } catch (Throwable ignored) {
            }
        }
        out.add("apk copy failed (copy " + apk + " by hand if needed)");
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
