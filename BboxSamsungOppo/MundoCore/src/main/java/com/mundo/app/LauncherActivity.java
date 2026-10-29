package com.mundo.app;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Bundle;

import androidx.annotation.Nullable;

import com.mundo.MundoCore;
import com.mundo.utils.Slog;
import org.lsposed.lsparanoid.Obfuscate;

@Obfuscate
public class LauncherActivity extends Activity {

    public static final String TAG = "SplashScreen";

    public static final String KEY_INTENT = "launch_intent";
    public static final String KEY_PKG = "launch_pkg";
    public static final String KEY_USER_ID = "launch_user_id";
    private boolean isRunning = false;

    public static void launch(Intent intent, int userId) {
        Intent splash = new Intent();
        splash.setClass(MundoCore.getContext(), LauncherActivity.class);
        splash.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        splash.putExtra(KEY_INTENT, intent);
        splash.putExtra(KEY_PKG, intent.getPackage());
        splash.putExtra(KEY_USER_ID, userId);
        MundoCore.getContext().startActivity(splash);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        setTheme(android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        super.onCreate(savedInstanceState);
        overridePendingTransition(0, 0);

        Intent intent = getIntent();
        if (intent == null) {
            finish();
            return;
        }

        Intent launchIntent = intent.getParcelableExtra(KEY_INTENT);
        String packageName = intent.getStringExtra(KEY_PKG);
        int userId = intent.getIntExtra(KEY_USER_ID, 0);

        PackageInfo packageInfo = MundoCore.getBPackageManager().getPackageInfo(packageName, 0, userId);
        if (packageInfo == null) {
            Slog.e(TAG, packageName + " not installed!");
            finish();
            return;
        }

        new Thread(() -> MundoCore.getBActivityManager().startActivity(launchIntent, userId)).start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        isRunning = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isRunning) {
            overridePendingTransition(0, 0);
            finish();
        }
    }
}
