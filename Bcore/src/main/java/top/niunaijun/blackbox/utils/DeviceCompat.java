package top.niunaijun.blackbox.utils;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class DeviceCompat {

    private static final String TAG = "DeviceCompat";

    public static boolean isSamsung() {
        return "samsung".equalsIgnoreCase(Build.MANUFACTURER);
    }

    public static boolean isMiui() {
        return !TextUtils.isEmpty(getSystemProperty("ro.miui.ui.version.name"))
                || !TextUtils.isEmpty(getSystemProperty("ro.miui.version.code_time"));
    }

    public static boolean isHyperOS() {
        return !TextUtils.isEmpty(getSystemProperty("ro.mi.os.version.name"))
                || !TextUtils.isEmpty(getSystemProperty("ro.miui.version.code_time"))
                       && Build.VERSION.SDK_INT >= 34;
    }

    public static boolean isOppo() {
        String m = Build.MANUFACTURER.toLowerCase();
        return m.contains("oppo") || m.contains("realme") || m.contains("oneplus")
                || !TextUtils.isEmpty(getSystemProperty("ro.build.version.opporom"));
    }

    public static boolean isVivo() {
        return "vivo".equalsIgnoreCase(Build.MANUFACTURER)
                || !TextUtils.isEmpty(getSystemProperty("ro.vivo.os.version"));
    }

    public static boolean isOnePlus() {
        return "oneplus".equalsIgnoreCase(Build.MANUFACTURER)
                || !TextUtils.isEmpty(getSystemProperty("ro.oxygen.version"))
                || !TextUtils.isEmpty(getSystemProperty("ro.oplus.theme"));
    }

    public static boolean isNothing() {
        return "nothing".equalsIgnoreCase(Build.MANUFACTURER);
    }

    public static boolean isLava() {
        return "lava".equalsIgnoreCase(Build.MANUFACTURER);
    }

    public static boolean isMicromax() {
        return "micromax".equalsIgnoreCase(Build.MANUFACTURER);
    }

    public static boolean isIqoo() {
        String m = Build.MANUFACTURER.toLowerCase();
        return m.contains("iqoo") || (isVivo() && Build.MODEL.toLowerCase().startsWith("iqoo"));
    }

    public static boolean isAtLeastAndroid12() { return Build.VERSION.SDK_INT >= 31; }
    public static boolean isAtLeastAndroid13() { return Build.VERSION.SDK_INT >= 33; }
    public static boolean isAtLeastAndroid14() { return Build.VERSION.SDK_INT >= 34; }
    public static boolean isAtLeastAndroid15() { return Build.VERSION.SDK_INT >= 35; }
    public static boolean isAtLeastAndroid16() { return Build.VERSION.SDK_INT >= 36; }

    public static void startServiceCompat(Context context, Intent intent) {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                context.startForegroundService(intent);
                return;
            } catch (SecurityException e) {
                Log.w(TAG, "startForegroundService failed (" + e.getMessage() + "), falling back");
            } catch (Exception e) {
                Log.w(TAG, "startForegroundService failed: " + e.getMessage());
            }
        }
        try {
            context.startService(intent);
        } catch (Exception e) {
            Log.e(TAG, "startService also failed: " + e.getMessage());
        }
    }

    public static Intent applyBackgroundLaunchFlags(Intent intent) {
        int flags = intent.getFlags();
        flags |= Intent.FLAG_ACTIVITY_NEW_TASK;
        if (Build.VERSION.SDK_INT >= 31) {
            flags |= Intent.FLAG_ACTIVITY_NO_ANIMATION;
        }
        intent.setFlags(flags);

        if (isMiui() || isHyperOS()) {
            try {
                Field f = Intent.class.getDeclaredField("mExtras");
                f.setAccessible(true);
                Bundle extras = (Bundle) f.get(intent);
                if (extras == null) {
                    extras = new Bundle();
                }
                extras.putBoolean("miui_background_start", true);
                intent.putExtras(extras);
            } catch (Exception ignored) {}
        }
        return intent;
    }

    public static void applySamsungCompat(Context context) {
        if (!isSamsung()) return;
        try {
            Log.d(TAG, "Applying Samsung One UI compat for " + context.getPackageName());
            installSamsungWindowCrashGuard();
        } catch (Exception e) {
            Log.w(TAG, "Samsung compat failed: " + e.getMessage());
        }
    }

    private static void installSamsungWindowCrashGuard() {
        Thread.UncaughtExceptionHandler existing = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            if (e instanceof android.view.WindowManager.BadTokenException) {
                Log.w(TAG, "Swallowing Samsung BadTokenException on thread " + t.getName());
                return;
            }
            if (existing != null) existing.uncaughtException(t, e);
        });
    }

    public static void applyMiuiCompat(Context context) {
        if (!isMiui() && !isHyperOS()) return;
        try {
            Class<?> bpm = Class.forName("android.os.PowerManager");
            try {
                Method ignoreBattery = bpm.getDeclaredMethod(
                        "setIgnoreBatteryOptimizations", String.class);
                ignoreBattery.setAccessible(true);
                ignoreBattery.invoke(context.getSystemService(Context.POWER_SERVICE),
                        context.getPackageName());
            } catch (Exception ignored) {}
        } catch (Exception e) {
            Log.w(TAG, "MIUI compat failed: " + e.getMessage());
        }
    }

    public static boolean checkMiuiOverlayPermission(Context context) {
        if (!isMiui() && !isHyperOS()) return true;
        if (Build.VERSION.SDK_INT >= 23) {
            return Settings.canDrawOverlays(context);
        }
        return true;
    }

    public static void applyOppoCompat(Context context) {
        if (!isOppo()) return;
        try {
            Class<?> amCls = Class.forName("android.app.ActivityManager");
            try {
                Method m = amCls.getDeclaredMethod("setBackgroundActivityStartAllowedBySystem",
                        boolean.class);
                m.setAccessible(true);
                m.invoke(context.getSystemService(Context.ACTIVITY_SERVICE), true);
            } catch (Exception ignored) {}
        } catch (Exception e) {
            Log.w(TAG, "Oppo compat failed: " + e.getMessage());
        }
    }

    public static void applyVivoCompat(Context context) {
        if (!isVivo() && !isIqoo()) return;
        try {
            Class<?> pm = Class.forName("android.os.Process");
            try {
                Method m = pm.getDeclaredMethod("setThreadPriority", int.class, int.class);
                m.setAccessible(true);
                m.invoke(null, Process.myPid(), -10);
            } catch (Exception ignored) {}
        } catch (Exception e) {
            Log.w(TAG, "Vivo compat failed: " + e.getMessage());
        }
    }

    public static void fixAndroid15EdgeToEdge(Activity activity) {
        if (Build.VERSION.SDK_INT < 35) return;
        try {
            Class<?> wc = Class.forName("android.view.WindowInsetsController");
            Method m = wc.getDeclaredMethod("setSystemBarsAppearance", int.class, int.class);
            m.setAccessible(true);
            Object ctrl = activity.getWindow().getInsetsController();
            if (ctrl != null) m.invoke(ctrl, 0, 0xFFFF);
        } catch (Exception ignored) {}
    }

    public static boolean requiresExplicitPendingIntent() {
        return Build.VERSION.SDK_INT >= 35;
    }

    public static boolean isReflectionRestricted() {
        return Build.VERSION.SDK_INT >= 36;
    }

    public static int getHeapLimitMB(Context context) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        return am != null ? am.getMemoryClass() : 128;
    }

    public static boolean isLowRamDevice(Context context) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null && Build.VERSION.SDK_INT >= 19 && am.isLowRamDevice()) return true;
        return getHeapLimitMB(context) <= 192;
    }

    public static void applyAll(Context context) {
        try { applySamsungCompat(context); } catch (Exception e) { Log.w(TAG, e.getMessage()); }
        try { applyMiuiCompat(context);    } catch (Exception e) { Log.w(TAG, e.getMessage()); }
        try { applyOppoCompat(context);    } catch (Exception e) { Log.w(TAG, e.getMessage()); }
        try { applyVivoCompat(context);    } catch (Exception e) { Log.w(TAG, e.getMessage()); }
        applyNativeCrashFixes();
    }

    public static void applyNativeCrashFixes() {}

    public static String getSystemProperty(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class);
            return (String) get.invoke(null, key);
        } catch (Exception e) {
            return "";
        }
    }

    public static String getOemName() {
        if (isSamsung())   return "Samsung";
        if (isHyperOS())   return "HyperOS (Xiaomi)";
        if (isMiui())      return "MIUI (Xiaomi)";
        if (isOnePlus())   return "OnePlus";
        if (isOppo())      return "Oppo/Realme";
        if (isIqoo())      return "iQOO";
        if (isVivo())      return "Vivo";
        if (isNothing())   return "Nothing";
        if (isLava())      return "Lava";
        if (isMicromax())  return "Micromax";
        return Build.MANUFACTURER;
    }

    private DeviceCompat() {}
}
