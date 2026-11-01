package com.bgmi;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.AnimationDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

@SuppressWarnings({"unused","deprecation"})
public class SplashActivity extends AppCompatActivity {

    public static boolean launched = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final int REQ_NOTIF = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        launched = true;

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
            WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        setContentView(R.layout.tron_first);

        // Start animated gradient background
        View root = findViewById(R.id.splashRoot);
        if (root != null && root.getBackground() instanceof AnimationDrawable) {
            ((AnimationDrawable) root.getBackground()).start();
        }

        // "Checking SDK" shows only 3 out of every 5 launches; others skip straight to "verified"
        android.content.SharedPreferences sp = getSharedPreferences("ark_splash", 0);
        int count = sp.getInt("launch_count", 0);
        sp.edit().putInt("launch_count", count + 1).apply();
        boolean showSdk = (count % 5) < 3; // positions 0,1,2 → show; 3,4 → skip

        TextView tv = findViewById(R.id.tv_app_name);
        if (showSdk) {
            if (tv != null) tv.setText("Checking SDK server...");
            handler.postDelayed(this::showVerified, 350);
        } else {
            if (tv != null) tv.setText("License verified. Opening login...");
            handler.postDelayed(this::exitSplash, 400);
        }
    }

    private void showVerified() {
        if (isFinishing() || isDestroyed()) return;
        TextView tv = findViewById(R.id.tv_app_name);
        if (tv != null) tv.setText("License verified. Opening login...");
        handler.postDelayed(this::exitSplash, 350);
    }

    private void exitSplash() {
        if (isFinishing() || isDestroyed()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                showNotificationPermissionScreen();
                return;
            }
        }
        Class<?> next = needsPermissions() ? PermissionsActivity.class : LoginActivity.class;
        startActivity(new Intent(SplashActivity.this, next));
        overridePendingTransition(R.anim.slide_up_enter, R.anim.splash_exit);
        finish();
    }

    private void showNotificationPermissionScreen() {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setGravity(Gravity.CENTER);
        root.setPadding(Math.round(40 * d), 0, Math.round(40 * d), 0);

        TextView title = new TextView(this);
        title.setText("Permissions required");
        title.setTextColor(Color.parseColor("#CCCCCC"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        titleLp.bottomMargin = Math.round(12 * d);
        title.setLayoutParams(titleLp);

        TextView desc = new TextView(this);
        desc.setText("Allow notifications to show the overlay service status.");
        desc.setTextColor(Color.parseColor("#888888"));
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        desc.setGravity(Gravity.CENTER);
        desc.setLineSpacing(0f, 1.3f);

        root.addView(title);
        root.addView(desc);
        setContentView(root);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQ_NOTIF) {
            Class<?> next = needsPermissions() ? PermissionsActivity.class : LoginActivity.class;
            startActivity(new Intent(this, next));
            overridePendingTransition(R.anim.slide_up_enter, R.anim.splash_exit);
            finish();
        }
    }

    private boolean needsPermissions() {
        if (!Settings.canDrawOverlays(this)) return true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) return true;
        } else {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!getPackageManager().canRequestPackageInstalls()) return true;
        }
        return false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }
}
