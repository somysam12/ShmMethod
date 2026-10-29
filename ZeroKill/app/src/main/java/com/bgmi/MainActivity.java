package com.bgmi;
import com.bgmi.R;

import android.content.Intent;
import android.graphics.drawable.AnimationDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatButton;

import com.bgmi.utils.Prefs;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import net_62v.external.MetaActivationManager;
import com.mundo.MundoCore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings({"unused","deprecation"})
public class MainActivity extends AppCompatActivity {

    // libZeroKill loaded by ArkApplication
    public static native String exdate();

    private static final String PKG_BGMI    = "com.pubg.imobile";
    private static final long   DELAY       = 1200L;
    private static final String KEY_OBB_DONE = "ark_obb_copied";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private LinearLayout    licenseLayout;
    private LinearLayout    processingLayout;
    private TextView        statusText;
    private TextView        syncText;
    private ProgressBar     obbProgressBar;
    private TextView        obbPercentText;
    private TextView        obbSizeText;
    private TextView        tvD, tvH, tvM, tvS;
    private TextView        expiryDateTv;
    private TextView        deviceNameTv;
    private AppCompatButton btnAction;
    private LinearLayout    bgmiInstalledRow;
    private BottomSheetDialog bsd;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        setContentView(R.layout.tron_three);

        // Start animated gradient background
        View mainRoot = findViewById(R.id.main_layout);
        if (mainRoot != null && mainRoot.getBackground() instanceof AnimationDrawable) {
            ((AnimationDrawable) mainRoot.getBackground()).start();
        }

        licenseLayout    = findViewById(R.id.licenseLayout);
        processingLayout = findViewById(R.id.processingLayout);
        statusText       = findViewById(R.id.statusText);
        syncText         = findViewById(R.id.syncText);
        obbProgressBar   = findViewById(R.id.obbProgressBar);
        obbPercentText   = findViewById(R.id.obbPercentText);
        obbSizeText      = findViewById(R.id.obbSizeText);
        tvD              = findViewById(R.id.tvD);
        expiryDateTv     = findViewById(R.id.expiryDateTv);
        tvH              = findViewById(R.id.tvH);
        tvM              = findViewById(R.id.tvM);
        tvS              = findViewById(R.id.tvS);
        btnAction        = findViewById(R.id.startgame);
        bgmiInstalledRow = findViewById(R.id.bgmiInstalledRow);
        deviceNameTv     = findViewById(R.id.deviceName);
        setDeviceInfo();
        setAppNameFont();
        startCountdown();
        // OBB copy is done in LoginActivity; auto-launch game immediately
        showLicense("Opening game...");
        mainHandler.postDelayed(this::launchGame, DELAY);
    }

    // ── Expiry countdown ─────────────────────────────────────────────────────

    private void startCountdown() {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                try {
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    Date exp = sdf.parse(exdate());
                    if (expiryDateTv != null) {
                        SimpleDateFormat displaySdf = new SimpleDateFormat("dd MMM yyyy \u2022 hh:mm a");
                        expiryDateTv.setText("Expires " + displaySdf.format(exp));
                    }
                    long diff = exp.getTime() - System.currentTimeMillis();
                    if (diff > 0) {
                        if (tvD != null) tvD.setText(String.format("%02d", diff / 86400000L));
                        if (tvH != null) tvH.setText(String.format("%02d", (diff / 3600000L) % 24));
                        if (tvM != null) tvM.setText(String.format("%02d", (diff / 60000L) % 60));
                        if (tvS != null) tvS.setText(String.format("%02d", (diff / 1000L) % 60));
                        mainHandler.postDelayed(this, 1000);
                    } else {
                        Toast.makeText(MainActivity.this, "Subscription Expired", Toast.LENGTH_LONG).show();
                        finish();
                    }
                } catch (Exception ignored) {}
            }
        });
    }

    // ── Button state ─────────────────────────────────────────────────────────

    private void setButtonState(boolean ready) {
        if (btnAction == null) return;
        btnAction.setVisibility(View.VISIBLE);
        btnAction.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null);
        if (ready) {
            if (bgmiInstalledRow != null) bgmiInstalledRow.setVisibility(View.VISIBLE);
            btnAction.setText("LAUNCH GAME");
            btnAction.setBackground(getResources().getDrawable(R.drawable.ark_btn_launch));
            btnAction.setTextColor(0xFFFFFFFF);
            btnAction.setOnClickListener(v -> {
                android.animation.ObjectAnimator sx = android.animation.ObjectAnimator.ofFloat(v, "scaleX", 1f, 1.05f, 0.98f, 1f);
                android.animation.ObjectAnimator sy = android.animation.ObjectAnimator.ofFloat(v, "scaleY", 1f, 1.05f, 0.98f, 1f);
                android.animation.AnimatorSet anim = new android.animation.AnimatorSet();
                anim.playTogether(sx, sy); anim.setDuration(260);
                anim.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(android.animation.Animator a) {
                        showLicense("Launching...");
                        mainHandler.postDelayed(MainActivity.this::launchGame, DELAY);
                    }
                });
                anim.start();
            });
        } else {
            if (bgmiInstalledRow != null) bgmiInstalledRow.setVisibility(View.GONE);
            btnAction.setText("COPY GAME DATA + OBB");
            btnAction.setBackground(getResources().getDrawable(R.drawable.ark_btn_copy));
            btnAction.setTextColor(0xFFFF8C00);
            btnAction.setOnClickListener(v -> {
                android.animation.ObjectAnimator sx = android.animation.ObjectAnimator.ofFloat(v, "scaleX", 1f, 0.97f, 1.02f, 1f);
                android.animation.ObjectAnimator sy = android.animation.ObjectAnimator.ofFloat(v, "scaleY", 1f, 0.97f, 1.02f, 1f);
                android.animation.AnimatorSet anim = new android.animation.AnimatorSet();
                anim.playTogether(sx, sy); anim.setDuration(220);
                anim.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(android.animation.Animator a) {
                        showDialog("Game Setup",
                            "This will copy OBB and install the game inside the loader. Keep the app open.",
                            MainActivity.this::startObbCopy, () -> {});
                    }
                });
                anim.start();
            });
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

    // ── OBB copy — copies to SdCard path matching BEnvironment ───────────────

    // Get virtual OBB container path — SdCard base matching BEnvironment.getExternalStorageDirectory()
    private File getMundoObbPath() {
        return new File(realExternalRoot() + "/Android/obb/com.visionpro/scopedStorage/com.pubg.imobile/Android/obb/" + PKG_BGMI);
    }

    private void startObbCopy() {
        new Thread(() -> {
            try {
                String realRoot = realExternalRoot();
                File srcObbDir = new File(realRoot + "/Android/obb/" + PKG_BGMI);

                // Step 1: get Mundo OBB container path (waits for SDK ready)
                File destDir = getMundoObbPath();
                if (!destDir.exists()) destDir.mkdirs();

                // Step 2: check already copied
                File[] existing = destDir.listFiles((d, n) -> n.endsWith(".obb"));
                if (existing != null && existing.length > 0) {
                    installIfNeeded();
                    markObbCopied();
                    runOnUiThread(() -> { showLicense("Ready"); setButtonState(true); });
                    return;
                }

                // Step 3: find source OBB (retry 3x)
                File[] srcs = null;
                for (int i = 0; i < 3; i++) {
                    srcs = srcObbDir.listFiles((d, n) -> n.endsWith(".obb"));
                    if (srcs != null && srcs.length > 0) break;
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                }
                if (srcs == null || srcs.length == 0) {
                    runOnUiThread(() -> showDialog("OBB Not Found",
                        "Install BGMI first, then retry.", () -> {}, () -> {}));
                    return;
                }

                File src   = srcs[0];
                File dst   = new File(destDir, src.getName());
                long total = src.length();
                AtomicBoolean done = new AtomicBoolean(false);

                // 5 min timeout
                mainHandler.postDelayed(() -> {
                    if (!done.get()) { done.set(true); runOnUiThread(() -> showLicense("Copy timed out")); }
                }, 300_000);

                // Step 4: fast copy via FileChannel (16 MB chunks, 1%-throttled UI)
                try (java.io.FileInputStream  fin  = new java.io.FileInputStream(src);
                     java.io.FileOutputStream fout = new java.io.FileOutputStream(dst);
                     java.nio.channels.FileChannel inCh  = fin.getChannel();
                     java.nio.channels.FileChannel outCh = fout.getChannel()) {
                    long position = 0L, chunkSize = 16L * 1024 * 1024;
                    int lastPct = -1;
                    while (position < total) {
                        long transferred = inCh.transferTo(position, chunkSize, outCh);
                        if (transferred <= 0) break;
                        position += transferred;
                        int pct = total > 0 ? (int)((position * 100L) / total) : 0;
                        if (pct != lastPct) {
                            lastPct = pct;
                            final long pos2 = position;
                            runOnUiThread(() -> showObbCopy("Copying OBB...", pct, pos2, total));
                        }
                    }
                }

                if (done.get()) return;
                done.set(true);

                // Step 5: mark copied BEFORE install
                markObbCopied();

                // Step 6: install APK inside Mundo virtual space
                runOnUiThread(() -> showObbCopy("Installing APK...", 100, total, total));
                installIfNeeded();

                runOnUiThread(() -> { showLicense("Ready"); setButtonState(true); });

            } catch (Exception e) {
                runOnUiThread(() -> showLicense("Copy failed: " + e.getMessage()));
            }
        }).start();
    }

    private void installIfNeeded() {
        try {
            if (MundoCore.get() != null && !MundoCore.get().isInstalled(PKG_BGMI, 0))
                MundoCore.get().installPackageAsUser(PKG_BGMI, 0);
        } catch (Throwable ignored) {}
    }

    private void markObbCopied() {
        new Prefs(this).setBool(KEY_OBB_DONE, true);
    }

    // ── Game launch ───────────────────────────────────────────────────────────

    // ── Mundo engine activation (MD doc requirement) ────────────────────────
    private void ensureMundoActivated() {
        try {
            boolean active = false;
            try { active = MetaActivationManager.getActivatedStatus(); } catch (Throwable ignored) {}
            if (!active) try { MetaActivationManager.activateSdk(ArkApplication.getSdkKey()); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    private void launchGame() {
        new Thread(() -> {
            try {
                if (MundoCore.get() != null && !MundoCore.get().isInstalled(PKG_BGMI, 0)) {
                    runOnUiThread(() -> showLicense("Installing BGMI..."));
                    installIfNeeded();
                }
                MundoCore.get().launchApk(PKG_BGMI, 0);
                // Start floating overlay menu (GameZone TCP feature control)
                runOnUiThread(this::startOverlayMenu);
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                    "Launch failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // ── Floating overlay menu — UNCHANGED from original ───────────────────────

    private void startOverlayMenu() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception ignored) {}
            Toast.makeText(this, "Enable overlay, then relaunch", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            startService(new Intent(this, OverlayService.class));
        } catch (Exception e) {
            Toast.makeText(this, "Overlay start failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private void showLicense(String status) {
        runOnUiThread(() -> {
            if (licenseLayout    != null) licenseLayout.setVisibility(View.VISIBLE);
            if (processingLayout != null) processingLayout.setVisibility(View.GONE);
            if (statusText       != null) statusText.setText(status);
        });
    }

    private void showObbCopy(String label, int pct, long done, long total) {
        runOnUiThread(() -> {
            if (licenseLayout    != null) licenseLayout.setVisibility(View.GONE);
            if (processingLayout != null) processingLayout.setVisibility(View.VISIBLE);
            if (statusText       != null) statusText.setText(label);
            if (syncText         != null) syncText.setText("Copying game data...");
            if (obbProgressBar   != null) obbProgressBar.setProgress(pct);
            if (obbPercentText   != null) obbPercentText.setText(pct + "%");
            if (obbSizeText != null && total > 0)
                obbSizeText.setText(fmt(done) + " / " + fmt(total));
        });
    }

    private void showDialog(String title, String msg, Runnable ok, Runnable cancel) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (bsd != null && bsd.isShowing()) bsd.dismiss();
            bsd = new BottomSheetDialog(this, R.style.AppTheme);
            View v = getLayoutInflater().inflate(R.layout.bottom_sheet_dialog_layout, null);
            bsd.setContentView(v);
            ImageView    ico = v.findViewById(R.id.dialog_icon);
            TextView     tv1 = v.findViewById(R.id.title);
            TextView     tv2 = v.findViewById(R.id.msg);
            AppCompatButton okB = v.findViewById(R.id.btn);
            AppCompatButton cxB = v.findViewById(R.id.btn_cancle);
            if (ico != null) ico.setImageResource(R.mipmap.ic_launcher);
            if (tv1 != null) tv1.setText(title);
            if (tv2 != null) tv2.setText(msg);
            if (okB != null) okB.setOnClickListener(x -> { bsd.dismiss(); ok.run(); });
            if (cxB != null) cxB.setOnClickListener(x -> { bsd.dismiss(); cancel.run(); });
            bsd.show();
        });
    }

    private static String fmt(long b) {
        if (b <= 0) return "0 B";
        if (b < 1048576) return String.format("%.1f KB", b / 1024.0);
        return String.format("%.1f MB", b / 1048576.0);
    }

    private void setDeviceInfo() {
        if (deviceNameTv == null) return;
        String mfr   = android.os.Build.MANUFACTURER;
        String model = android.os.Build.MODEL;
        deviceNameTv.setText(model.startsWith(mfr) ? model : mfr + " " + model);
        deviceNameTv.setVisibility(android.view.View.VISIBLE);
        boolean rooted = new java.io.File("/system/bin/su").exists()
                      || new java.io.File("/system/xbin/su").exists();
        // Update root badge text
        try {
            android.view.ViewGroup parent = (android.view.ViewGroup) deviceNameTv.getParent();
            for (int i = 0; i < parent.getChildCount(); i++) {
                android.view.View v = parent.getChildAt(i);
                if (v instanceof android.widget.LinearLayout) {
                    android.widget.LinearLayout ll = (android.widget.LinearLayout) v;
                    for (int j = 0; j < ll.getChildCount(); j++) {
                        android.view.View c = ll.getChildAt(j);
                        if (c instanceof android.widget.LinearLayout) {
                            android.widget.LinearLayout inner = (android.widget.LinearLayout) c;
                            for (int k = 0; k < inner.getChildCount(); k++) {
                                if (inner.getChildAt(k) instanceof android.widget.TextView) {
                                    android.widget.TextView tv = (android.widget.TextView) inner.getChildAt(k);
                                    if (tv.getText().toString().contains("ROOT") || tv.getText().toString().contains("NON")) {
                                        tv.setText(rooted ? "ROOT DEVICE" : "NON-ROOT DEVICE");
                                        tv.setTextColor(rooted ? 0xFFD080FF : 0xFFFF4500);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private void setAppNameFont() {
        TextView tv = findViewById(R.id.appNameText);
        if (tv == null) return;
        try { tv.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(this, R.font.en_regular)); }
        catch (Throwable ignored) {}
    }


    @Override
    public void onBackPressed() {
        Toast.makeText(this, "Press back twice to exit", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacksAndMessages(null);
        if (bsd != null && bsd.isShowing()) bsd.dismiss();
    }
}
