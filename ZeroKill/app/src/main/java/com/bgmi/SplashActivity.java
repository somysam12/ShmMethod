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
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

@SuppressWarnings({"unused","deprecation"})
public class SplashActivity extends AppCompatActivity {

    public static boolean launched = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private static final int REQ_NOTIF = 2001;

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

        // If returning from PermissionsActivity, skip permission check entirely — all granted.
        boolean fromPerms = getIntent().getBooleanExtra("from_perms", false);
        if (fromPerms) {
            runSplashAnimation();
            return;
        }

        if (needsPermissions()) {
            startActivity(new Intent(this, PermissionsActivity.class));
            overridePendingTransition(R.anim.slide_up_enter, R.anim.splash_exit);
            finish();
            return;
        }

        runSplashAnimation();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    private void runSplashAnimation() {
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
        goToLogin();
    }

    private void showNotificationPermissionScreen() {
        float d = getResources().getDisplayMetrics().density;
        int p32 = Math.round(32 * d);
        int p64 = Math.round(64 * d);

        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0D0D0D"));
        root.setGravity(android.view.Gravity.CENTER);
        root.setPadding(p32, p64, p32, p64);

        android.widget.TextView title = new android.widget.TextView(this);
        title.setText("Permissions required");
        title.setTextColor(Color.parseColor("#9E9E9E"));
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(android.view.Gravity.CENTER);
        android.widget.LinearLayout.LayoutParams titleLp =
            new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        titleLp.bottomMargin = Math.round(16 * d);
        title.setLayoutParams(titleLp);

        android.widget.TextView desc = new android.widget.TextView(this);
        desc.setText("Allow notifications to show the overlay service status.");
        desc.setTextColor(Color.parseColor("#757575"));
        desc.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f);
        desc.setGravity(android.view.Gravity.CENTER);
        android.widget.LinearLayout.LayoutParams descLp =
            new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        descLp.bottomMargin = Math.round(40 * d);
        desc.setLayoutParams(descLp);

        android.widget.Button btn = new android.widget.Button(this);
        btn.setText("Allow permission");
        btn.setTextColor(Color.WHITE);
        btn.setBackgroundColor(Color.parseColor("#7B5E2A"));
        android.widget.LinearLayout.LayoutParams btnLp =
            new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                Math.round(52 * d));
        btn.setLayoutParams(btnLp);
        btn.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            }
        });

        root.addView(title);
        root.addView(desc);
        root.addView(btn);
        setContentView(root);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
            @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // Proceed to login regardless of whether notification permission was granted
        if (requestCode == REQ_NOTIF) {
            goToLogin();
        }
    }

    private void goToLogin() {
        if (isFinishing() || isDestroyed()) return;
        startActivity(new Intent(SplashActivity.this, LoginActivity.class));
        overridePendingTransition(R.anim.slide_up_enter, R.anim.splash_exit);
        finish();
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
