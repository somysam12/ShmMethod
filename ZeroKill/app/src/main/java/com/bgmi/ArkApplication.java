package com.bgmi;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.mundo.MundoCore;
import com.mundo.app.configuration.ClientConfiguration;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.io.File;

@SuppressWarnings({"unused","deprecation"})
public class ArkApplication extends Application {

    static {
        try { System.loadLibrary("sdk"); }
        catch (UnsatisfiedLinkError ignored) {}
    }

    public static native String getSdkKey();
    private static final String TAG = "ArkApp";

    @Override
    protected void attachBaseContext(Context base) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try { HiddenApiBypass.addHiddenApiExemptions("L"); }
            catch (Throwable t) { Log.w(TAG, "HiddenApiBypass: " + t.getMessage()); }
        }
        super.attachBaseContext(base);
        try {
            MundoCore.get().doAttachBaseContext(base, new ClientConfiguration() {
                @Override public String getHostPackageName() { return base.getPackageName(); }
                @Override public boolean isEnableDaemonService() { return false; }
                @Override public boolean requestInstallPackage(File file) { return false; }
            });
        } catch (Exception e) { Log.e(TAG, "attachBaseContext: " + e.getMessage()); }
        try {
            File lib = new File(base.getFilesDir(), "loader/libbgmi.so");
            Log.d(TAG, "PROCESS START lib=" + lib.exists() + " process=" + getProcessNameCompat(base));
        } catch (Exception ignored) {}
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try { MundoCore.get().doCreate(); }
        catch (Exception e) { Log.e(TAG, "MundoCore.doCreate: " + e.getMessage()); }
        try { com.mundo.core.system.api.MetaActivationManager.activateSdk(getSdkKey()); }
        catch (Exception e) { Log.e(TAG, "SDK activation: " + e.getMessage()); }
        try { disableFirebase(); } catch (Exception ignored) {}
    }

    private String getProcessNameCompat(Context base) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 28) return Application.getProcessName();
            int pid = android.os.Process.myPid();
            android.app.ActivityManager am =
                (android.app.ActivityManager) base.getSystemService(ACTIVITY_SERVICE);
            if (am != null)
                for (android.app.ActivityManager.RunningAppProcessInfo p : am.getRunningAppProcesses())
                    if (p.pid == pid) return p.processName;
        } catch (Exception ignored) {}
        return "?";
    }

    private void disableFirebase() throws Exception {
        Class<?> fb = Class.forName("com.google.firebase.messaging.FirebaseMessaging");
        java.lang.reflect.Method m = fb.getDeclaredMethod("setAutoInitEnabled", boolean.class);
        m.invoke(fb.getMethod("getInstance").invoke(null), false);
    }
}
