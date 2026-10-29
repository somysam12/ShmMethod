package com.bgmi;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.drawable.AnimationDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatButton;
import androidx.cardview.widget.CardView;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.channels.FileChannel;

import com.bgmi.utils.Downtwo;
import com.bgmi.utils.Prefs;
import com.mundo.MundoCore;

@SuppressWarnings({"unused", "deprecation"})
public class LoginActivity extends BaseActivity {

    static {
        try { System.loadLibrary("ZeroKill"); }
        catch (UnsatisfiedLinkError ignored) {}
    }

    private static final String USER = "USER";

    private Prefs prefs;
    private EditText etKey;
    private androidx.cardview.widget.CardView btnLogin;
    private View btnPaste;
    private ImageButton eyeToggle;
    private boolean passwordVisible = false;

    private LinearLayout processingCard;
    private ProgressBar progressBar, downloadProgressBar;
    private TextView loadingText, progressText, percentText, downloadSizeText, speedText;
    private View speedRow;
    private AppCompatButton okButton;

    private long dlStartMs = 0;

    private static final int RC_STORAGE  = 100;
    private static final int RC_INSTALL  = 200;
    private static final int RC_OVERLAY  = 300;
    private static final String KEY_PERMS_DONE = "ark_perms_done";
    private static final String KEY_OBB_DONE   = "ark_obb_copied";
    private static final String PKG_BGMI        = "com.pubg.imobile";

    // Native declarations
    private static native boolean nativeVerifyLoginToken(String token);
    private static native String nativeGetSerial();
    private static native String Check(android.content.Context ctx, String key);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        //noinspection deprecation
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        setContentView(R.layout.tron_two);

        // Start animated gradient background
        View root = findViewById(R.id.main_layout);
        if (root != null && root.getBackground() instanceof AnimationDrawable) {
            ((AnimationDrawable) root.getBackground()).start();
        }

        prefs = Prefs.with(this);
        bindViews();
        setTitle();
        setupListeners();
        etKey.setText(prefs.read(USER, ""));
        checkPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
    }

    // ── Permission chain ────────────────────────────────────────────────────
    private void checkPermissions() {
        if (!hasStorage()) { askStorage(); return; }
        if (!hasInstall()) { askInstall(); return; }
        if (!hasOverlay()) { askOverlay(); return; }
        onPermissionsReady();
    }

    private boolean hasStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            return Environment.isExternalStorageManager();
        return androidx.core.content.ContextCompat.checkSelfPermission(this,
            android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED;
    }
    private boolean hasInstall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            return getPackageManager().canRequestPackageInstalls();
        return true;
    }
    private boolean hasOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            return Settings.canDrawOverlays(this);
        return true;
    }

    private void askStorage() {
        showSheet("SYSTEM ACCESS REQUIRED",
            "UNRESTRICTED FILESYSTEM CLEARANCE IS MANDATORY. This application requires absolute read/write authority over all external storage partitions. Denial will terminate the process immediately.",
            "AUTHORIZE", () -> {
                dismissSheet();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    startActivityForResult(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())), RC_STORAGE);
                } else {
                    androidx.core.app.ActivityCompat.requestPermissions(this,
                        new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE,
                                     android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, RC_STORAGE);
                }
            },
            "TERMINATE", () -> { dismissSheet(); finish(); System.exit(0); }
        );
    }

    private void askInstall() {
        showSheet("DEPLOYMENT CLEARANCE REQUIRED",
            "EXECUTION AUTHORITY OVER UNSIGNED PACKAGES IS NON-NEGOTIABLE. Critical combat modules must be deployed directly. Without this authorization, operational components cannot be installed and functionality will be severely compromised.",
            "AUTHORIZE", () -> {
                dismissSheet();
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())), RC_INSTALL);
            },
            "TERMINATE", () -> { dismissSheet(); finish(); System.exit(0); }
        );
    }

    private void askOverlay() {
        showSheet("OVERLAY DOMINANCE REQUIRED",
            "SCREEN PENETRATION CLEARANCE IS DEMANDED. This application must render its interface over all active processes simultaneously. This is an absolute prerequisite for in-game overlay operation. No exceptions.",
            "AUTHORIZE", () -> {
                dismissSheet();
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())), RC_OVERLAY);
            },
            "TERMINATE", () -> { dismissSheet(); finish(); System.exit(0); }
        );
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == RC_STORAGE) {
            // On Android 11+ MANAGE_EXTERNAL_STORAGE takes full kernel-level effect only
            // after the app process restarts, and the system state may lag a few hundred ms
            // after the user taps Allow. Poll until isExternalStorageManager() returns true
            // (up to 3 s) before restarting so the new process always has the permission.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pollStoragePermissionThenRestart(0);
                return;
            }
            if (!hasInstall()) { askInstall(); return; }
            if (!hasOverlay()) { askOverlay(); return; }
            onPermissionsReady();
        } else if (req == RC_INSTALL) {
            if (!hasOverlay()) { askOverlay(); return; }
            onPermissionsReady();
        } else if (req == RC_OVERLAY) {
            onPermissionsReady();
        }
    }

    private void pollStoragePermissionThenRestart(int attempts) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            // Permission is active — restart now
            try {
                Intent restart = getPackageManager().getLaunchIntentForPackage(getPackageName());
                if (restart != null) {
                    restart.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(restart);
                }
            } finally {
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        } else if (attempts < 15) {
            // Not yet active — check again in 200 ms (max ~3 s total)
            new Handler(Looper.getMainLooper()).postDelayed(
                () -> pollStoragePermissionThenRestart(attempts + 1), 200);
        } else {
            // Timed out — restart anyway so the user doesn't get stuck
            try {
                Intent restart = getPackageManager().getLaunchIntentForPackage(getPackageName());
                if (restart != null) {
                    restart.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(restart);
                }
            } finally {
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == RC_STORAGE) {
            if (!hasInstall()) { askInstall(); return; }
            if (!hasOverlay()) { askOverlay(); return; }
            onPermissionsReady();
        }
    }

    private void onPermissionsReady() {
        showLoginUI();
    }

    @SuppressLint("SetTextI18n")
    private void updateDownloadUI(int pct, long done, long total) {
        if (processingCard      != null) processingCard.setVisibility(View.VISIBLE);
        if (loadingText         != null) loadingText.setText("Downloading game update...");
        if (progressText        != null) { progressText.setVisibility(View.VISIBLE); progressText.setText("Downloaded " + pct + "%"); }
        if (progressBar         != null) progressBar.setVisibility(View.VISIBLE);
        if (downloadProgressBar != null) downloadProgressBar.setVisibility(View.GONE);
        if (percentText         != null) percentText.setVisibility(View.GONE);
        if (downloadSizeText    != null) downloadSizeText.setVisibility(View.GONE);
        if (speedRow            != null) speedRow.setVisibility(View.GONE);
        if (okButton            != null) okButton.setVisibility(View.GONE);
    }

    private static String fmt(long b) {
        if (b < 1024 * 1024) return String.format("%.1f KB", b / 1024.0);
        return String.format("%.1f MB", b / (1024.0 * 1024));
    }

    // ── Login UI ────────────────────────────────────────────────────────────
    private void showLoginUI() {
        if (processingCard != null) processingCard.setVisibility(View.GONE);
        if (etKey != null)    etKey.setEnabled(true);
        if (btnLogin != null) btnLogin.setEnabled(true);
        getWindow().getDecorView().post(this::runEntryAnim);
        setTitle();
    }

    private void runEntryAnim() {
        // Vision_Tw: no entry animation — background AnimationDrawable handles visual
    }

    private void bindViews() {
        etKey               = findViewById(R.id.et1);
        btnLogin            = (androidx.cardview.widget.CardView) findViewById(R.id.btnSignIn);
        btnPaste            = findViewById(R.id.img1);
        eyeToggle           = findViewById(R.id.eyeToggle);
        processingCard      = findViewById(R.id.processingCard);
        progressBar         = findViewById(R.id.progressBar);
        downloadProgressBar = findViewById(R.id.downloadProgressBar);
        loadingText         = findViewById(R.id.loadingText);
        progressText        = findViewById(R.id.progressText);
        percentText         = findViewById(R.id.percentText);
        downloadSizeText    = findViewById(R.id.downloadSizeText);
        speedText           = findViewById(R.id.speedText);
        speedRow            = findViewById(R.id.speedRow);
        okButton            = findViewById(R.id.okButton);
    }

    private void setupListeners() {
        // Key is visible by default (no password masking) — matches real Vision behaviour
        passwordVisible = true;
        if (eyeToggle != null) {
            eyeToggle.setImageResource(R.drawable.ic_eye_open);
            eyeToggle.setOnClickListener(v -> {
                passwordVisible = !passwordVisible;
                etKey.setTransformationMethod(passwordVisible
                    ? HideReturnsTransformationMethod.getInstance()
                    : PasswordTransformationMethod.getInstance());
                etKey.setSelection(etKey.getText().length());
                eyeToggle.setImageResource(passwordVisible ? R.drawable.ic_eye_open : R.drawable.ic_eye_off);
            });
        }
        if (btnPaste != null) {
            btnPaste.setOnClickListener(v -> {
                ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cb != null && cb.hasPrimaryClip()) {
                    ClipData.Item item = cb.getPrimaryClip().getItemAt(0);
                    if (item != null && item.getText() != null) {
                        String txt = item.getText().toString().trim();
                        if (txt.length() > 4) etKey.setText(txt);
                        else Toast.makeText(this, "Invalid key", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }
        if (btnLogin != null) {
            btnLogin.setOnClickListener(v -> {
                // Dismiss keyboard and return card to bottom position
                android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null && getCurrentFocus() != null)
                    imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
                String key = etKey.getText().toString().trim();
                if (key.isEmpty()) {
                    etKey.setError("Enter your license key");
                    return;
                }
                prefs.write(USER, key);
                doLogin(key);
            });
        }
    }

    // ── Login ────────────────────────────────────────────────────────────────
    private void doLogin(String key) {
        showProcessing("Verifying License");
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper(),
            msg -> {
                if (msg.what == 0) {
                    hideProcessing();
                    String serial = "";
                    try { serial = nativeGetSerial(); } catch (Throwable ignored) {}
                    writeLicFile(key, serial);
                    startObbCopyFromLogin(key, serial);
                } else {
                    showError("Invalid Key", (String) msg.obj);
                }
                return true;
            });
        new Thread(() -> {
            String result = "";
            boolean loginSuccess = false;
            try {
                for (int attempt = 0; attempt < 3; attempt++) {
                    result = Check(this, key);
                    // Check() returns the login token on success — verify it natively
                    // (anti-smali-patch). nativeVerifyLoginToken consumes the token, so
                    // it is called exactly once per attempt.
                    if (result != null && !result.isEmpty() && nativeVerifyLoginToken(result)) {
                        loginSuccess = true;
                        break;
                    }
                    // Always retry with backoff — covers both server errors and first-call
                    // native init delay (nativeVerifyLoginToken can return false on cold start).
                    if (attempt < 2) {
                        try { Thread.sleep(600); } catch (InterruptedException ignored) {}
                    }
                }
            } catch (Throwable t) {
                result = t.getMessage() != null ? t.getMessage() : "Error";
            }
            android.os.Message m = android.os.Message.obtain();
            m.what = loginSuccess ? 0 : 1;
            m.obj  = loginSuccess ? null : ((result != null && !result.isEmpty()) ? result : "Key is invalid");
            h.sendMessage(m);
        }).start();
    }

    // Writes License.txt to Android/obb/com.visionpro/ with key, serial, packageName
    private void writeLicFile(String key, String serial) {
        try {
            java.io.File dir = new java.io.File(realExternalRoot(), "Android/obb/com.visionpro");
            if (!dir.exists()) dir.mkdirs();
            java.io.File lic = new java.io.File(dir, "License.txt");
            String data = (key != null ? key : "") + "\n"
                        + (serial != null ? serial : "") + "\n"
                        + getPackageName();
            java.io.FileOutputStream fos = new java.io.FileOutputStream(lic, false);
            fos.write(data.getBytes("UTF-8"));
            fos.flush(); fos.close();
            try { lic.setReadable(true, false); } catch (Exception ignored) {}
        } catch (Exception e) {
            android.util.Log.e("ARK_LIC", "writeLicFile failed: " + e.getMessage());
        }
    }

    private static String realExternalRoot() {
        try {
            int userId = (int) android.os.UserHandle.class
                    .getMethod("myUserId").invoke(null);
            return "/storage/emulated/" + userId;
        } catch (Throwable t) {
            return "/storage/emulated/0";
        }
    }

    // ── OBB copy (runs before download, on login screen) ───────────────────────
    @android.annotation.SuppressLint("SetTextI18n")
    private void startObbCopyFromLogin(String key, String serial) {
        // Use realExternalRoot() to bypass any VirtualApp storage hook
        String realRoot = realExternalRoot();
        // src: real BGMI OBB directory
        // dest: matches BEnvironment.getExternalStorageDirectory() nested path
        java.io.File destDir = new java.io.File(realRoot + "/Android/obb/com.visionpro/scopedStorage/" + PKG_BGMI);

        if (prefs.getBool(KEY_OBB_DONE, false)) {
            java.io.File[] obbFiles = destDir.listFiles((d, n) -> n.endsWith(".obb"));
            if (obbFiles != null && obbFiles.length > 0) {
                showProcessingMsg("Opening game...");
                new Handler(Looper.getMainLooper()).postDelayed(() -> launchGameAndFinish(), 100);
                return;
            }
            prefs.setBool(KEY_OBB_DONE, false); // reset — OBB not at expected location
        }
        showProcessingMsg("Copying Obb...");
        new Thread(() -> {
            try {
                java.io.File srcObbDir = new java.io.File(realRoot + "/Android/obb/" + PKG_BGMI);
                if (!destDir.exists()) destDir.mkdirs();

                java.io.File[] existing = destDir.listFiles((d, n) -> n.endsWith(".obb"));
                if (existing != null && existing.length > 0) {
                    prefs.setBool(KEY_OBB_DONE, true);
                    runOnUiThread(() -> showProcessingMsg("Opening game..."));
                    runOnUiThread(() -> startLibDownloadSilent(key, serial));
                    return;
                }

                // Poll for OBB with retries to handle first-launch permission propagation
                java.io.File[] srcs = null;
                for (int i = 0; i < 10; i++) {
                    srcs = srcObbDir.listFiles((d, n) -> n.endsWith(".obb"));
                    if (srcs != null && srcs.length > 0) break;
                    Thread.sleep(1000);
                }
                if (srcs == null || srcs.length == 0) {
                    runOnUiThread(() -> showError("OBB Not Found", "Install BGMI first, then retry."));
                    return;
                }

                java.io.File src = srcs[0];
                java.io.File dst = new java.io.File(destDir, src.getName());
                long total = src.length();

                try (FileInputStream  fin  = new FileInputStream(src);
                     FileOutputStream fout = new FileOutputStream(dst);
                     FileChannel inCh  = fin.getChannel();
                     FileChannel outCh = fout.getChannel()) {
                    long position  = 0L;
                    long chunkSize = 32L * 1024 * 1024;
                    int lastPct    = -1;
                    while (position < total) {
                        long transferred = inCh.transferTo(position, chunkSize, outCh);
                        if (transferred <= 0) break;
                        position += transferred;
                        int pct = total > 0 ? (int)((position * 100L) / total) : 0;
                        if (pct != lastPct) {
                            lastPct = pct;
                            final int p = pct;
                            runOnUiThread(() -> showObbProgress(p));
                        }
                    }
                }

                prefs.setBool(KEY_OBB_DONE, true);
                runOnUiThread(() -> showProcessingMsg("Opening game..."));
                runOnUiThread(() -> startLibDownloadSilent(key, serial));

            } catch (Exception e) {
                runOnUiThread(() -> showError("Copy Failed",
                    e.getMessage() != null ? e.getMessage() : "Unknown error"));
            }
        }).start();
    }

    private void showProcessingMsg(String msg) {
        runOnUiThread(() -> {
            if (processingCard      != null) processingCard.setVisibility(View.VISIBLE);
            if (loadingText         != null) loadingText.setText(msg);
            if (progressText        != null) progressText.setText("");
            if (progressBar         != null) progressBar.setVisibility(View.GONE);
            if (downloadProgressBar != null) downloadProgressBar.setVisibility(View.GONE);
            if (percentText         != null) percentText.setVisibility(View.GONE);
            if (downloadSizeText    != null) downloadSizeText.setVisibility(View.GONE);
            if (speedRow            != null) speedRow.setVisibility(View.GONE);
            if (okButton            != null) okButton.setVisibility(View.GONE);
        });
    }

    @android.annotation.SuppressLint("SetTextI18n")
    private void showObbProgress(int pct) {
        if (processingCard      != null) processingCard.setVisibility(View.VISIBLE);
        if (loadingText         != null) loadingText.setText("Copying Obb...");
        if (progressText        != null) { progressText.setVisibility(View.VISIBLE); progressText.setText(pct + "%"); }
        if (progressBar         != null) progressBar.setVisibility(View.GONE);
        if (downloadProgressBar != null) downloadProgressBar.setVisibility(View.GONE);
        if (percentText         != null) percentText.setVisibility(View.GONE);
        if (downloadSizeText    != null) downloadSizeText.setVisibility(View.GONE);
        if (speedRow            != null) speedRow.setVisibility(View.GONE);
        if (okButton            != null) okButton.setVisibility(View.GONE);
    }

    private void startLibDownloadSilent(String key, String serial) {
        dlStartMs = System.currentTimeMillis();
        Downtwo dl = new Downtwo(this, success -> launchGameAndFinish());
        dl.setCredentials(key, serial, getPackageName());
        dl.setSizeProgressListener((pct, done, total) ->
            runOnUiThread(() -> updateDownloadUI(pct, done, total)));
        dl.execute();
    }

    private void launchGameAndFinish() {
        showProcessingMsg("Opening game...");
        new Thread(() -> {
            try {
                Thread.sleep(100);
                try {
                    if (MundoCore.get() != null && !MundoCore.get().isInstalled(PKG_BGMI, 0))
                        MundoCore.get().installPackageAsUser(PKG_BGMI, 0);
                } catch (Throwable ignored) {}
                if (MundoCore.get() != null) MundoCore.get().launchApk(PKG_BGMI, 0);
                runOnUiThread(() -> {
                    try { startService(new Intent(this, OverlayService.class)); } catch (Exception ig) {}
                    finish();
                });
            } catch (Exception e) {
                runOnUiThread(() -> showError("Launch Failed",
                    e.getMessage() != null ? e.getMessage() : "Error launching game"));
            }
        }).start();
    }

    // ── Processing helpers ──────────────────────────────────────────────────
    private void showProcessing(String msg) {
        runOnUiThread(() -> {
            if (processingCard != null) processingCard.setVisibility(View.VISIBLE);
            if (loadingText != null)    loadingText.setText(msg.toUpperCase());
            if (progressText != null)   progressText.setText("Please wait...");
            if (progressBar != null)    progressBar.setVisibility(View.VISIBLE);
            if (downloadProgressBar != null) downloadProgressBar.setVisibility(View.GONE);
            if (percentText != null)    percentText.setVisibility(View.GONE);
            if (downloadSizeText != null) downloadSizeText.setVisibility(View.GONE);
            if (speedRow != null)       speedRow.setVisibility(View.GONE);
            if (okButton != null)       okButton.setVisibility(View.GONE);
        });
    }

    private void hideProcessing() {
        if (processingCard != null) processingCard.setVisibility(View.GONE);
    }

    @SuppressLint("SetTextI18n")
    private void showError(String title, String msg) {
        runOnUiThread(() -> {
            if (processingCard != null) processingCard.setVisibility(View.VISIBLE);
            if (loadingText != null)    { loadingText.setText(title); loadingText.setTextColor(0xFFFF4060); }
            if (progressText != null)   progressText.setText(msg);
            if (progressBar != null)    progressBar.setVisibility(View.GONE);
            if (downloadProgressBar != null) downloadProgressBar.setVisibility(View.GONE);
            if (percentText != null)    percentText.setVisibility(View.GONE);
            if (downloadSizeText != null) { downloadSizeText.setVisibility(View.GONE); downloadSizeText.setText(""); }
            if (speedRow != null)       speedRow.setVisibility(View.GONE);
            if (speedText != null)      { speedText.setVisibility(View.GONE); speedText.setText(""); }
            if (okButton != null) {
                okButton.setVisibility(View.VISIBLE);
                okButton.setOnClickListener(v -> {
                    if (loadingText != null) loadingText.setTextColor(0xFFFFFFFF);
                    hideProcessing();
                });
            }
        });
    }

    private void setTitle() {
        // Vision_Tw style: no custom title on login screen
    }

}
