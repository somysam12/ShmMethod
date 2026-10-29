package com.bgmi;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

@SuppressWarnings({"unused", "deprecation"})
public class PermissionsActivity extends AppCompatActivity {

    private static final int REQ_OVERLAY   = 1001;
    private static final int REQ_STORAGE   = 1002;
    private static final int REQ_INSTALL   = 1003;
    private static final int REQ_NOTIF     = 1004;

    private TextView statusText;
    private Button retryBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
            WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        setContentView(R.layout.activity_permissions);

        statusText = findViewById(R.id.permissionStatus);
        retryBtn   = findViewById(R.id.permissionRetry);

        retryBtn.setOnClickListener(v -> requestNext());
        requestNext();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (allGranted()) {
            proceed();
        }
    }

    // Permission request order: Storage (All Files) → Overlay → Install
    // Notification is handled in SplashActivity, not here.
    private void requestNext() {
        if (!hasStorage()) {
            if (statusText != null)
                statusText.setText(getString(R.string.permissions_storage));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
                startActivityForResult(i, REQ_STORAGE);
            } else {
                requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, REQ_STORAGE);
            }
            return;
        }
        if (!hasOverlay()) {
            if (statusText != null)
                statusText.setText(getString(R.string.permissions_overlay));
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
            startActivityForResult(i, REQ_OVERLAY);
            return;
        }
        if (!hasInstall()) {
            if (statusText != null)
                statusText.setText(getString(R.string.permissions_install));
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + getPackageName()));
            startActivityForResult(i, REQ_INSTALL);
            return;
        }
        proceed();
    }

    private boolean allGranted() {
        return hasOverlay() && hasStorage() && hasInstall();
    }

    private boolean hasOverlay() {
        return Settings.canDrawOverlays(this);
    }

    private boolean hasStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasInstall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return getPackageManager().canRequestPackageInstalls();
        }
        return true;
    }

    private void proceed() {
        // Go to SplashActivity (not LoginActivity) — SplashActivity handles notification perm.
        // Pass flag so SplashActivity skips re-checking these permissions.
        Intent i = new Intent(this, SplashActivity.class);
        i.putExtra("from_perms", true);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        overridePendingTransition(R.anim.slide_left_enter, R.anim.slide_right_exit);
        finish();
    }

    // On Android 11+, MANAGE_EXTERNAL_STORAGE requires a process restart to fully take effect.
    // Poll until isExternalStorageManager() is true (max 5×100ms = 500ms), then restart silently.
    private void pollAndRestart(int attempts) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            Intent restart = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (restart != null) {
                restart.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(restart);
            }
            // 300ms delay so startActivity can process before process is killed
            new Handler(Looper.getMainLooper()).postDelayed(
                () -> android.os.Process.killProcess(android.os.Process.myPid()), 300);
        } else if (attempts < 5) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> pollAndRestart(attempts + 1), 100);
        } else {
            // 500ms total — force restart anyway for fresh permissions
            Intent restart = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (restart != null) {
                restart.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(restart);
            }
            new Handler(Looper.getMainLooper()).postDelayed(
                () -> android.os.Process.killProcess(android.os.Process.myPid()), 300);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // On Android 11+, MANAGE_EXTERNAL_STORAGE requires restart to activate file access.
        if (requestCode == REQ_STORAGE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pollAndRestart(0);
            return;
        }
        requestNext();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        requestNext();
    }
}
