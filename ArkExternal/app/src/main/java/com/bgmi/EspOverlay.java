package com.bgmi;

import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;

import java.io.File;
import java.nio.ByteBuffer;

public class EspOverlay extends Service {

    static {
        System.loadLibrary("shaitaan");
        System.loadLibrary("render");
    }

    // ── JNI declarations ──────────────────────────────────────────────────────
    static native boolean nativeInit(Surface surface, int w, int h);
    static native void    nativeSetFont(byte[] rgba, int aw, int ah,
                                        float[] charWidths, float[] charOffsets,
                                        float charH, float atlasUsedW);
    static native void    nativeStartRender();
    static native void    nativeSetFlags(boolean line, boolean box, boolean skel,
                                         boolean hp, boolean name, boolean dist,
                                         boolean count, boolean veh);
    static native void    nativeStop();

    // ── Feature flags ─────────────────────────────────────────────────────────
    public static volatile boolean drawLine     = false;
    public static volatile boolean drawSkeleton = false;
    public static volatile boolean drawHealth   = false;
    public static volatile boolean drawDistance = false;
    public static volatile boolean drawName     = false;
    public static volatile boolean drawCount    = false;
    public static volatile boolean drawVehicle  = false;
    public static volatile boolean drawBox      = false;

    // ── Static setters (called from HeartbeatManager / Downtwo panel) ─────────
    public static void setDrawLine(boolean v)     { drawLine     = v; pushFlags(); }
    public static void setDrawSkeleton(boolean v) { drawSkeleton = v; pushFlags(); }
    public static void setDrawHealth(boolean v)   { drawHealth   = v; pushFlags(); }
    public static void setDrawDistance(boolean v) { drawDistance = v; pushFlags(); }
    public static void setDrawName(boolean v)     { drawName     = v; pushFlags(); }
    public static void setDrawCount(boolean v)    { drawCount    = v; pushFlags(); }
    public static void setDrawVehicle(boolean v)  { drawVehicle  = v; pushFlags(); }
    public static void setDrawBox(boolean v)      { drawBox      = v; pushFlags(); }

    private static void pushFlags() {
        if (instance == null) return;
        nativeSetFlags(drawLine, drawBox, drawSkeleton, drawHealth,
                       drawName, drawDistance, drawCount, drawVehicle);
    }

    // ── Logging helper (called by OverlayService) ─────────────────────────────
    public static void espLog(String tag, String msg) {
        android.util.Log.i("ArkESP/" + tag, msg);
    }

    // ── Instance ──────────────────────────────────────────────────────────────
    private static EspOverlay instance;
    private WindowManager      wm;
    private SurfaceView        surfaceView;
    private boolean            nativeStarted = false;

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        android.graphics.Point sz = new android.graphics.Point();
        wm.getDefaultDisplay().getRealSize(sz);
        final int screenW = Math.max(sz.x, sz.y);
        final int screenH = Math.min(sz.x, sz.y);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            screenW, screenH, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.RGBA_8888);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 0; lp.y = 0;

        surfaceView = new SurfaceView(this);
        surfaceView.getHolder().setFormat(PixelFormat.RGBA_8888);
        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                if (!nativeStarted) {
                    Surface surf = holder.getSurface();
                    boolean ok = nativeInit(surf, screenW, screenH);
                    if (ok) {
                        generateAndUploadFont();
                        nativeStartRender();
                        nativeSetFlags(drawLine, drawBox, drawSkeleton, drawHealth,
                                       drawName, drawDistance, drawCount, drawVehicle);
                        nativeStarted = true;
                    }
                }
            }
            @Override
            public void surfaceChanged(SurfaceHolder h, int fmt, int w, int t) {}
            @Override
            public void surfaceDestroyed(SurfaceHolder h) {
                if (nativeStarted) { nativeStop(); nativeStarted = false; }
            }
        });

        wm.addView(surfaceView, lp);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        instance = null;
        if (nativeStarted) { nativeStop(); nativeStarted = false; }
        if (surfaceView != null && wm != null) {
            try { wm.removeView(surfaceView); } catch (Exception ignored) {}
        }
    }

    // ── Font atlas generation ─────────────────────────────────────────────────
    private void generateAndUploadFont() {
        int atlasW = 512, atlasH = 64;
        float charH = 28f;

        Bitmap bmp = Bitmap.createBitmap(atlasW, atlasH, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(charH);
        p.setColor(Color.WHITE);
        p.setFakeBoldText(true);

        String fontPath = getFilesDir().getAbsolutePath() + "/esp_font.ttf";
        if (new File(fontPath).exists()) {
            try { p.setTypeface(Typeface.createFromFile(fontPath)); }
            catch (Exception ignored) { p.setTypeface(Typeface.DEFAULT_BOLD); }
        } else {
            p.setTypeface(Typeface.DEFAULT_BOLD);
        }

        // 96 printable ASCII chars: space (32) through ~ (126)
        float[] charWidths  = new float[96];
        float[] charOffsets = new float[96];
        float x = 0f;

        for (int i = 0; i < 96; i++) {
            char ch = (char)(32 + i);
            charOffsets[i] = x;
            charWidths[i]  = p.measureText(String.valueOf(ch));
            c.drawText(String.valueOf(ch), x, charH - p.descent(), p);
            x += charWidths[i] + 2f;
            if (x >= atlasW - 20f) break; // safety: don't overflow atlas
        }

        ByteBuffer buf = ByteBuffer.allocate(atlasW * atlasH * 4);
        bmp.copyPixelsToBuffer(buf);
        bmp.recycle();

        nativeSetFont(buf.array(), atlasW, atlasH, charWidths, charOffsets, charH, x);
    }

    // ── Hide-ESP (screenshot hide via SurfaceControl reflection) ─────────────
    public static void setHideFlag(boolean hide) {
        sHideFlag = hide;
        new android.os.Handler(android.os.Looper.getMainLooper())
            .post(() -> { if (instance != null) instance.applyHideFlag(hide); });
    }
    private static volatile boolean sHideFlag = false;

    @android.annotation.SuppressLint("PrivateApi")
    private void applyHideFlag(boolean hide) {
        if (surfaceView == null) return;
        try {
            java.lang.reflect.Field aiField =
                android.view.View.class.getDeclaredField("mAttachInfo");
            aiField.setAccessible(true);
            Object attachInfo = aiField.get(surfaceView);
            if (attachInfo == null) return;

            java.lang.reflect.Field vriField =
                attachInfo.getClass().getDeclaredField("mViewRootImpl");
            vriField.setAccessible(true);
            Object viewRootImpl = vriField.get(attachInfo);
            if (viewRootImpl == null) return;

            android.view.SurfaceControl sc = null;
            for (String fname : new String[]{"mSurfaceControl","mBlastSurfaceControl"}) {
                try {
                    java.lang.reflect.Field f =
                        viewRootImpl.getClass().getDeclaredField(fname);
                    f.setAccessible(true);
                    Object obj = f.get(viewRootImpl);
                    if (obj instanceof android.view.SurfaceControl) {
                        sc = (android.view.SurfaceControl) obj;
                        break;
                    }
                } catch (NoSuchFieldException ignored) {}
            }
            if (sc == null) { applyPrivateFlagFallback(hide); return; }

            final int SKIP = 0x40;
            android.view.SurfaceControl.Transaction tx =
                new android.view.SurfaceControl.Transaction();
            java.lang.reflect.Method setFlagsM = null;
            for (java.lang.reflect.Method m : tx.getClass().getDeclaredMethods()) {
                if ("setFlags".equals(m.getName()) && m.getParameterTypes().length == 3) {
                    setFlagsM = m; break;
                }
            }
            if (setFlagsM != null) {
                setFlagsM.setAccessible(true);
                setFlagsM.invoke(tx, sc, hide ? SKIP : 0, SKIP);
                tx.apply();
            } else {
                applyPrivateFlagFallback(hide);
            }
        } catch (Throwable e) {
            applyPrivateFlagFallback(hide);
        }
    }

    private void applyPrivateFlagFallback(boolean hide) {
        if (surfaceView == null || wm == null) return;
        try {
            WindowManager.LayoutParams lp =
                (WindowManager.LayoutParams) surfaceView.getLayoutParams();
            java.lang.reflect.Field pf =
                WindowManager.LayoutParams.class.getDeclaredField("privateFlags");
            pf.setAccessible(true);
            int flagsVal = (int) pf.get(lp);
            if (hide) flagsVal |= 0x00000040;
            else      flagsVal &= ~0x00000040;
            pf.set(lp, flagsVal);
            wm.updateViewLayout(surfaceView, lp);
        } catch (Throwable ignored) {}
    }
}
