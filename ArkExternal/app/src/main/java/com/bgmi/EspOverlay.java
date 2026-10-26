package com.bgmi;

import android.app.Service;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.os.IBinder;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

public class EspOverlay extends Service {

    private static final String TAG = "ArkESP";

    // ── SHM constants (match EspFrameData.h exactly) ─────────────────────────
    private static final int MAX_PLAYERS   = 100;
    private static final int MAX_VEHICLES  = 20;
    private static final int MAX_BONES     = 17;
    private static final int PLAYER_STRIDE = 216;
    private static final int VEHICLE_STRIDE= 48;
    private static final int FRAME_HEADER  = 32;
    private static final int PLAYERS_OFFSET  = FRAME_HEADER;
    private static final int VEHICLES_OFFSET = PLAYERS_OFFSET + MAX_PLAYERS * PLAYER_STRIDE;
    private static final int SHM_SIZE = VEHICLES_OFFSET + MAX_VEHICLES * VEHICLE_STRIDE + 64;

    private static final String[] SHM_PATHS = {
        "/storage/emulated/0/CrashLog/ark_esp_v1",
        "/storage/emulated/0/Android/data/com.bgmi/files/ark_esp_v1",
        "/data/data/com.bgmi/files/ark_esp_v1",
        "/data/local/tmp/ark_esp_v1"
    };

    // ── HUD SHM offsets (fps + expiryText live in original pad[64] area) ──────
    private static final int FPS_OFFSET    = VEHICLES_OFFSET + MAX_VEHICLES * VEHICLE_STRIDE; // +0
    private static final int EXPIRY_OFFSET = FPS_OFFSET + 4; // fps=int(4bytes), then char[48]

    // ╔══════════════════════════════════════════════════════════════════════╗
    // ║  BLUEFOX STYLE CONFIG — exact colors from BlueFox 1.1.2 smali      ║
    // ╚══════════════════════════════════════════════════════════════════════╝
    // Box colors — BlueFox TEAM_COLORS[]: orange, lime, cyan, pink, lavender
    // We map: visible=lime, knocked=red, bot=orange (matches BlueFox palette)
    private static final String  CFG_VIS_C     = "#A4FF73";  // lime  — visible enemy
    private static final String  CFG_HIDE_C    = "#FF4444";  // red   — behind cover
    private static final String  CFG_KNK_C     = "#FF3333";  // red   — knocked (DOWNED)
    private static final String  CFG_BOT_C     = "#FFC96B";  // orange — bot (BlueFox team1)
    private static final float   CFG_BOX_W     = 1.5f;  // thin stroke (BlueFox style)
    private static final int     CFG_BOX_A     = 220;
    private static final int     CFG_FILL_A    = 22;    // very low alpha fill (BlueFox boxFillPaint)

    private static final boolean CFG_SKEL_ON   = true;
    private static final float   CFG_SKEL_W    = 1.2f;  // thin bones (BlueFox linePaint)
    private static final int     CFG_SKEL_A    = 200;   // alpha=0xC8 from smali

    // Health bar — thin 3px strip above box (BlueFox style)
    private static final float   CFG_HP_H      = 3f;    // 3px thin (BlueFox)
    private static final int     CFG_HP_A      = 255;
    private static final String  CFG_HP_G      = "#2ECC40";
    private static final String  CFG_HP_P      = "#ffcc00";
    private static final String  CFG_HP_R      = "#FF3333";

    // Name + distance — below box, small mono text (BlueFox mNamePaint / DistancePaint)
    private static final float   CFG_NAME_SZ   = 11f;   // ~35*scaleY in BlueFox (small)
    private static final float   CFG_DIST_SZ   = 10f;   // ~25*scaleY in BlueFox
    private static final String  CFG_NAME_C    = "#ffffff";

    // Line — dashed, bottom-center → player foot (BlueFox DrawLinePlayer)
    private static final String  CFG_LINE_C    = "#A4FF73";
    private static final float   CFG_LINE_W    = 1f;
    private static final int     CFG_LINE_A    = 80;    // semi-transparent

    // Enemy count panel — top-right corner box (BlueFox mEcXxx)
    private static final boolean CFG_CNT_ON    = true;
    private static final float   CFG_CNT_SZ    = 10f;
    private static final float   CFG_CNT_R     = 6f;    // corner radius (mEcCornerRadius)
    private static final String  CFG_CNT_E     = "#A4FF73";  // player rows
    private static final String  CFG_CNT_B     = "#FFC96B";  // bot rows
    private static final String  CFG_CNT_CL    = "#27AE60";

    // ── HUD positions — change X/Y/font to reposition watermark, FPS, expiry ─
    // Watermark "ARK CHEAT BGMI" — bottom right
    private static final String WM1         = "ARK";          // yellow
    private static final String WM2         = " CHEAT";       // orange
    private static final String WM3         = " BGMI";        // white
    private static final float  WM_FONT     = 28f;   // watermark font — bigger
    private static final float  WM_Y_OFF    = 32f;            // px above bottom
    private static final float  WM_X_OFF    = 20f;   // px from right → shifts watermark LEFT
    // FPS indicator — top left
    private static final float  HUD_FPS_X   = 160f;  // FPS shifted RIGHT
    private static final float  HUD_FPS_Y   = 36f;
    private static final float  HUD_FPS_SZ  = 22f;   // FPS bigger
    // Expiry text — bottom left
    private static final float  HUD_EXP_X   = 120f;  // expiry shifted RIGHT
    private static final float  HUD_EXP_Y_OFF = 32f;         // px above bottom
    private static final float  HUD_EXP_SZ  = 20f;   // expiry bigger

    // ── Hide-ESP: screenshot protection via FLAG_SECURE ───────────────────────
    private static EspOverlay instance;
    private static volatile boolean sHideFlag = false;

    /** Called by OverlayService when user toggles Hide ESP */
    public static void setHideFlag(boolean hide) {
        sHideFlag = hide;
        new android.os.Handler(android.os.Looper.getMainLooper())
            .post(() -> { if (instance != null) instance.applyHideFlag(hide); });
    }

    /**
     * Hides the overlay from screenshots WITHOUT using FLAG_SECURE.
     *
     * FLAG_SECURE on a fullscreen overlay blocks the device's screenshot button
     * entirely (causes "Can't take screenshot due to security policy" error).
     *
     * Instead, use SurfaceControl.Transaction.setFlags() to set
     * eLayerSkipScreenshot (0x40) on just the overlay's surface.
     * This is the same flag native ANwCreator.hpp's eSkipScreenshot sets.
     *
     * Result: overlay is invisible in screenshots/recordings but physically
     * visible to the player on their screen — exactly like the menu does.
     */
    @android.annotation.SuppressLint("PrivateApi")
    private void applyHideFlag(boolean hide) {
        if (espView == null) return;
        try {
            // Get ViewRootImpl → mSurfaceControl via reflection
            java.lang.reflect.Field aiField =
                android.view.View.class.getDeclaredField("mAttachInfo");
            aiField.setAccessible(true);
            Object attachInfo = aiField.get(espView);
            if (attachInfo == null) { espLog("HIDE","attachInfo null"); return; }

            java.lang.reflect.Field vriField =
                attachInfo.getClass().getDeclaredField("mViewRootImpl");
            vriField.setAccessible(true);
            Object viewRootImpl = vriField.get(attachInfo);
            if (viewRootImpl == null) { espLog("HIDE","viewRootImpl null"); return; }

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
            if (sc == null) { espLog("HIDE","SurfaceControl field not found"); return; }

            // eLayerSkipScreenshot = 0x40 — same flag native ANwCreator eSkipScreenshot sets.
            // SurfaceControl.Transaction.setFlags() is @hide (not in public SDK) so we
            // must call it via reflection — direct call causes compile error.
            final int SKIP = 0x40;
            android.view.SurfaceControl.Transaction tx =
                new android.view.SurfaceControl.Transaction();

            java.lang.reflect.Method setFlagsM = null;
            for (java.lang.reflect.Method m : tx.getClass().getDeclaredMethods()) {
                if ("setFlags".equals(m.getName()) && m.getParameterTypes().length == 3) {
                    setFlagsM = m;
                    break;
                }
            }

            if (setFlagsM != null) {
                setFlagsM.setAccessible(true);
                setFlagsM.invoke(tx, sc, hide ? SKIP : 0, SKIP);
                tx.apply();
                espLog("HIDE", "eLayerSkipScreenshot=" + hide + " via reflection ✓");
            } else {
                espLog("HIDE", "setFlags not found — trying privateFlags fallback");
                applyPrivateFlagFallback(hide);
            }
        } catch (Throwable e) {
            espLog("HIDE", "SurfaceControl: " + e.getMessage() + " — trying fallback");
            applyPrivateFlagFallback(hide);
        }
    }

    /** Fallback: WindowManager.LayoutParams.privateFlags reflection */
    private void applyPrivateFlagFallback(boolean hide) {
        if (espView == null || wm == null) return;
        try {
            WindowManager.LayoutParams lp =
                (WindowManager.LayoutParams) espView.getLayoutParams();
            java.lang.reflect.Field pf =
                WindowManager.LayoutParams.class.getDeclaredField("privateFlags");
            pf.setAccessible(true);
            int flags = (int) pf.get(lp);
            if (hide) flags |= 0x00000040; // PRIVATE_FLAG_NO_SCREENSHOT
            else       flags &= ~0x00000040;
            pf.set(lp, flags);
            wm.updateViewLayout(espView, lp);
            espLog("HIDE", "privateFlags fallback=" + hide + " ✓");
        } catch (Throwable e) {
            espLog("HIDE", "privateFlags fallback: " + e.getMessage());
        }
    }

    // ── Feature flags ─────────────────────────────────────────────────────────
    public static volatile boolean drawLine     = false;
    public static volatile boolean drawSkeleton = false; // = "Box" in menu
    public static volatile boolean drawHealth   = false;
    public static volatile boolean drawDistance = false;
    public static volatile boolean drawName     = false;
    public static volatile boolean drawCount    = false;
    public static volatile boolean drawVehicle  = false;

    // ── State ─────────────────────────────────────────────────────────────────
    private WindowManager    wm;
    private EspView          espView;
    private MappedByteBuffer shmBuf;
    private RandomAccessFile shmFile;
    private int              lastSeq = -1;
    int                      screenW, screenH;
    private String           shmPathUsed = null;
    private int              dbgFramesRead = 0;
    private long             dbgLastLogMs  = 0;
    private static final long LOG_INTERVAL = 5000;

    // ── Paints (all pre-allocated — zero alloc in onDraw) ────────────────────
    private final Paint pBox     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBoxFill = new Paint(Paint.ANTI_ALIAS_FLAG);  // BlueFox boxFillPaint
    private final Paint pHpBg    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pHpFg    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pName    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pNameSh  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pNameBg  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pDist    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pDistSh  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pLine    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pEcBg    = new Paint(Paint.ANTI_ALIAS_FLAG);  // BlueFox mEcBgPaint
    private final Paint pEcOl    = new Paint(Paint.ANTI_ALIAS_FLAG);  // BlueFox mEcOutlinePaint
    private final Paint pEcTx    = new Paint(Paint.ANTI_ALIAS_FLAG);  // BlueFox mEcPlayerTextPaint
    private final Paint pVeh     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pVehSh   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pSkel    = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Standard BGMI skeleton bone connections (17 joints)
    private static final int[][] BONE_LINKS = {
        {0,1},{1,2},{2,3},{3,4},{4,5},   // spine chain
        {1,6},{6,7},{7,8},               // left arm
        {1,9},{9,10},{10,11},            // right arm
        {5,12},{12,13},{13,14},          // left leg
        {5,15},{15,16}                   // right leg
    };
    private final RectF rf       = new RectF();

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        instance = this;
        initPaints();
        initWindow();
        preTouchShmFile();
        openShm();
        startChoreographer();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        instance = null;
        if (espView != null && wm != null)
            try { wm.removeView(espView); } catch (Exception ignored) {}
        closeShm();
    }

    // ── Paint setup ── BlueFox style ─────────────────────────────────────────
    private void initPaints() {
        // Box outline — STROKE (BlueFox boxPaint)
        pBox.setStyle(Paint.Style.STROKE);
        pBox.setStrokeWidth(CFG_BOX_W);
        pBox.setStrokeCap(Paint.Cap.BUTT);  // BlueFox uses square caps

        // Box fill — very low alpha (BlueFox boxFillPaint, FILL style)
        pBoxFill.setStyle(Paint.Style.FILL);

        // Health bar — FILL (thin 3px strip)
        pHpBg.setStyle(Paint.Style.FILL); pHpBg.setColor(Color.argb(180, 0, 0, 0));
        pHpFg.setStyle(Paint.Style.FILL);

        // Name — small monospace-style, CENTER aligned (BlueFox mNamePaint)
        pName.setTextSize(CFG_NAME_SZ);
        pName.setTextAlign(Paint.Align.CENTER);
        pName.setFakeBoldText(true);

        // Name shadow stroke (BlueFox SHADOW = ARGB(160,0,0,0))
        pNameSh.setStyle(Paint.Style.STROKE); pNameSh.setStrokeWidth(2f);
        pNameSh.setColor(Color.argb(160, 0, 0, 0));
        pNameSh.setTextSize(CFG_NAME_SZ); pNameSh.setTextAlign(Paint.Align.CENTER);
        pNameSh.setFakeBoldText(true);

        // Name bg pill (behind name text)
        pNameBg.setStyle(Paint.Style.FILL);
        pNameBg.setColor(Color.argb(160, 0, 0, 0));

        // Distance — smaller, team color (BlueFox DistancePaint)
        pDist.setTextSize(CFG_DIST_SZ);
        pDist.setTextAlign(Paint.Align.CENTER);

        pDistSh.setStyle(Paint.Style.STROKE); pDistSh.setStrokeWidth(1.5f);
        pDistSh.setColor(Color.argb(160, 0, 0, 0));
        pDistSh.setTextSize(CFG_DIST_SZ); pDistSh.setTextAlign(Paint.Align.CENTER);

        // Skeleton — STROKE, thin, alpha=200 (BlueFox linePaint)
        pSkel.setStyle(Paint.Style.STROKE);
        pSkel.setStrokeWidth(CFG_SKEL_W);
        pSkel.setStrokeCap(Paint.Cap.ROUND);
        pSkel.setAlpha(CFG_SKEL_A);

        // ESP line — thin dashed (BlueFox DrawLinePlayer)
        pLine.setStyle(Paint.Style.STROKE);
        pLine.setStrokeWidth(CFG_LINE_W);

        // Enemy count panel (BlueFox mEcBgPaint/mEcOutlinePaint/mEcPlayerTextPaint)
        pEcBg.setStyle(Paint.Style.FILL);
        pEcBg.setColor(Color.argb(178, 0, 0, 0));   // 70% black bg
        pEcOl.setStyle(Paint.Style.STROKE);
        pEcOl.setStrokeWidth(1f);
        pEcOl.setColor(Color.argb(64, 255, 255, 255));
        pEcTx.setTextSize(CFG_CNT_SZ);
        pEcTx.setTextAlign(Paint.Align.LEFT);
        pEcTx.setFakeBoldText(false);

        // Vehicle text
        pVeh.setTextSize(CFG_NAME_SZ); pVeh.setTextAlign(Paint.Align.CENTER);
        pVehSh.setStyle(Paint.Style.STROKE); pVehSh.setStrokeWidth(2f);
        pVehSh.setColor(Color.argb(200, 0, 0, 0));
        pVehSh.setTextSize(CFG_NAME_SZ); pVehSh.setTextAlign(Paint.Align.CENTER);
    }

    // ── Window — always landscape ─────────────────────────────────────────────
    private void initWindow() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        android.graphics.Point sz = new android.graphics.Point();
        wm.getDefaultDisplay().getRealSize(sz);
        screenW = Math.max(sz.x, sz.y);
        screenH = Math.min(sz.x, sz.y);
        espLog("INIT", "Screen: " + screenW + "×" + screenH);
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
        espView = new EspView(this);
        wm.addView(espView, lp);
    }

    // ── SHM ──────────────────────────────────────────────────────────────────
    private void preTouchShmFile() {
        new File("/storage/emulated/0/CrashLog").mkdirs();
        touch(new File("/storage/emulated/0/CrashLog/ark_esp_v1"));
        File ext = getExternalFilesDir(null);
        if (ext != null) { ext.mkdirs(); touch(new File(ext, "ark_esp_v1")); }
        getFilesDir().mkdirs();
    }
    private void touch(File f) {
        try { if (!f.exists()) { RandomAccessFile r = new RandomAccessFile(f,"rw"); r.setLength(SHM_SIZE); r.close(); }}
        catch (Exception e) { espLog("INIT","touch fail:"+f+" "+e.getMessage()); }
    }
    private void openShm() {
        List<String> paths = new ArrayList<>();
        paths.add("/storage/emulated/0/CrashLog/ark_esp_v1");
        File ext = getExternalFilesDir(null);
        if (ext != null) paths.add(ext.getAbsolutePath()+"/ark_esp_v1");
        paths.add(getFilesDir().getAbsolutePath()+"/ark_esp_v1");
        paths.add("/data/local/tmp/ark_esp_v1");
        espLog("INIT","Trying "+paths.size()+" SHM paths");
        for (String p : paths) {
            try {
                shmFile = new RandomAccessFile(p,"rw");
                if (shmFile.length() < SHM_SIZE) shmFile.setLength(SHM_SIZE);
                shmBuf = shmFile.getChannel().map(FileChannel.MapMode.READ_WRITE,0,SHM_SIZE);
                shmBuf.order(ByteOrder.LITTLE_ENDIAN);
                shmPathUsed = p; espLog("INIT","✔ SHM:"+p); return;
            } catch (Exception e) {
                espLog("INIT","✘ "+p+" "+e.getMessage());
                try { if (shmFile!=null) { shmFile.close(); shmFile=null; }} catch(Exception ig){}
                shmBuf=null;
            }
        }
        espLog("INIT","✘✘ ALL SHM paths failed");
    }
    private void closeShm() {
        try { if (shmFile!=null) shmFile.close(); } catch (Exception ignored) {}
        shmFile=null; shmBuf=null;
    }

    // ── Choreographer ─────────────────────────────────────────────────────────
    // Rate-limited to ~28fps (35ms interval):
    // Game writes SHM at 60fps but overlay only redraws every 35ms.
    // Effect: ESP position "snaps" every 35ms → looks like "slight shake then stable"
    // (same as socket-based cheats where TCP latency limits update rate).
    // At 60fps every frame would move, creating constant jitter with gyroscope.
    private long lastDrawMs = 0;
    private static final long DRAW_INTERVAL_MS = 35L; // ~28fps

    private void startChoreographer() {
        Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
            @Override public void doFrame(long ns) {
                boolean any = drawLine||drawSkeleton||drawHealth||drawDistance||drawName||drawCount||drawVehicle;
                if (espView!=null && shmBuf!=null && any) {
                    long now = System.currentTimeMillis();
                    int seq  = shmBuf.getInt(0);
                    // Only redraw when SHM has new data AND 35ms has passed
                    if (seq != lastSeq && now - lastDrawMs >= DRAW_INTERVAL_MS) {
                        lastSeq     = seq;
                        lastDrawMs  = now;
                        dbgFramesRead++;
                        espView.invalidate();
                    }
                }
                long now2 = System.currentTimeMillis();
                if (now2-dbgLastLogMs>=LOG_INTERVAL) { dbgLastLogMs=now2; logDiag(any); }
                Choreographer.getInstance().postFrameCallback(this);
            }
        });
    }

    // ── Color helpers ─────────────────────────────────────────────────────────
    /** Health fill color — uses CFG_HP_* colors with CFG_HP_A alpha */
    private int hpFadeColor(float hp) {
        String hex = hp > 0.65f ? CFG_HP_G : (hp > 0.35f ? CFG_HP_P : CFG_HP_R);
        int c = Color.parseColor(hex);
        return Color.argb(CFG_HP_A, Color.red(c), Color.green(c), Color.blue(c));
    }

    /** Parse hex color + apply alpha */
    private static int ca(String hex, int alpha) {
        int c = Color.parseColor(hex);
        return Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c));
    }

    // ── Enemy count panel — BlueFox mEcXxx style (top-right corner) ──────────
    // Panel: dark rounded rect + outline, then one row per player/bot entry
    private void drawEcPanel(Canvas c, float sw, int enemyCnt, int botCnt) {
        if (!CFG_CNT_ON || (enemyCnt == 0 && botCnt == 0)) return;

        final float PAD  = 6f;          // internal padding
        final float ROW  = CFG_CNT_SZ + 6f; // row height
        final float TW   = 72f;         // panel width
        int rows = enemyCnt + botCnt;
        float PH = PAD + ROW + rows * ROW + PAD; // header row + data rows

        float px = sw - TW - 8f;  // 8px from right edge
        float py = 8f;            // 8px from top

        // bg + outline
        rf.set(px, py, px+TW, py+PH);
        c.drawRoundRect(rf, CFG_CNT_R, CFG_CNT_R, pEcBg);
        c.drawRoundRect(rf, CFG_CNT_R, CFG_CNT_R, pEcOl);

        // divider line after header
        float divY = py + PAD + ROW;
        c.drawLine(px+4, divY, px+TW-4, divY, pEcOl);

        // "ENEMIES" header
        pEcTx.setColor(Color.argb(200, 200, 210, 220));
        float hY = py + PAD + ROW - pEcTx.descent() - 2f;
        c.drawText("ENEMIES", px + PAD, hY, pEcTx);

        // Player rows (lime — BlueFox mEcPlayerPaint)
        float ry = divY + ROW;
        for (int i = 0; i < enemyCnt; i++) {
            pEcTx.setColor(ca(CFG_CNT_E, 220));
            // Small row bg
            rf.set(px+3, ry - ROW + 2, px+TW-3, ry - 1);
            c.drawRoundRect(rf, 2, 2, pEcBg);
            c.drawText("PLR " + (i+1), px+PAD, ry - pEcTx.descent() - 2f, pEcTx);
            ry += ROW;
        }

        // Bot rows (orange — BlueFox mEcBotPaint)
        for (int i = 0; i < botCnt; i++) {
            pEcTx.setColor(ca(CFG_CNT_B, 220));
            rf.set(px+3, ry - ROW + 2, px+TW-3, ry - 1);
            c.drawRoundRect(rf, 2, 2, pEcBg);
            c.drawText("BOT " + (i+1), px+PAD, ry - pEcTx.descent() - 2f, pEcTx);
            ry += ROW;
        }
    }

    // ── Drawing ───────────────────────────────────────────────────────────────
    private class EspView extends View {
        EspView(android.content.Context ctx) { super(ctx); }

        @Override
        protected void onDraw(Canvas canvas) {
            if (shmBuf == null) return;

            ByteBuffer buf = shmBuf.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            int seq;
            do { seq = buf.getInt(0); } while ((seq & 1) != 0); // seqlock

            buf.position(0);
            buf.getInt(); buf.getInt(); buf.getFloat(); buf.getFloat(); // skip header meta
            int playerCount = buf.getInt();
            int vehCount    = buf.getInt();
            int enemyCount  = buf.getInt();
            int botCount    = buf.getInt();

            if (buf.getInt(0) != seq) return;
            if (playerCount < 0 || playerCount > MAX_PLAYERS)  return;
            if (vehCount    < 0 || vehCount    > MAX_VEHICLES) return;

            float sw = screenW, sh = screenH;
            boolean anyEsp = drawLine||drawSkeleton||drawHealth||drawDistance||drawName;

            // ── PLAYERS ──────────────────────────────────────────────────────
            for (int i = 0; i < playerCount; i++) {
                buf.position(PLAYERS_OFFSET + i * PLAYER_STRIDE);

                float headX = buf.getFloat() * sw;
                float headY = buf.getFloat() * sh;
                float rootX = buf.getFloat() * sw;
                float rootY = buf.getFloat() * sh;

                float[] bx = new float[MAX_BONES], by = new float[MAX_BONES];
                boolean[] bv = new boolean[MAX_BONES];
                for (int b=0;b<MAX_BONES;b++) bx[b]=buf.getFloat()*sw;
                for (int b=0;b<MAX_BONES;b++) by[b]=buf.getFloat()*sh;
                for (int b=0;b<MAX_BONES;b++) bv[b]=buf.get()!=0;
                buf.position(buf.position()+3); // C++ struct padding after boneValid[17]

                float   health   = buf.getFloat();
                float   distance = buf.getFloat();
                boolean isBot    = buf.get()!=0;
                boolean isVis    = buf.get()!=0;
                boolean isKnk    = buf.get()!=0;
                buf.get(); // hasArmor
                byte[] nb = new byte[32]; buf.get(nb);
                String name = new String(nb).trim().replace("\0","");

                if (!anyEsp) continue;

                // ── FIXED BOX SIZE — same pixel size at ALL distances and zoom levels ──
                // User requirement: NO auto-scaling. 44x72px regardless of range.
                // Anchored at headY (head position), extends down by FBH.
                // boxCX = midpoint of head and root for accurate horizontal centering.
                final float FBW  = 44f;   // fixed box width  (px) — never changes
                final float FBH  = 72f;   // fixed box height (px) — never changes
                float boxCX = (headX + rootX) / 2f;
                float bL    = boxCX - FBW / 2f;
                float bR    = boxCX + FBW / 2f;
                float bT    = headY - 2f;       // anchor: just above head
                float bB    = bT + FBH;          // fixed height — NOT rootY


                // Box color — from user config
                int boxColor;
                if (isKnk)      boxColor = ca(CFG_KNK_C, CFG_BOX_A);           // red = knocked (BlueFox)
                else if (isBot) boxColor = isVis ? ca(CFG_BOT_C, CFG_BOX_A) : Color.argb(160,160,160,160);
                else            boxColor = isVis ? ca(CFG_VIS_C, CFG_BOX_A) : ca(CFG_HIDE_C, CFG_BOX_A);

                // ── 1. ESP LINE: bottom-center → player foot (BlueFox DrawLinePlayer) ──
                if (drawLine) {
                    pLine.setColor(isVis ? ca(CFG_LINE_C, CFG_LINE_A) : Color.argb(60,150,150,150));
                    canvas.drawLine(sw * 0.5f, sh, boxCX, bB, pLine);
                }

                // ── 2. SKELETON — distance-independent (remapped into fixed box) ──
                // Native boneX/boneY are real screen coords that shrink at long range.
                // Fix: find the natural bounding rect of all valid bones, then
                // linearly remap each bone into the fixed box (bL→bR, bT→bB).
                // Result: skeleton always fills the same fixed pixel area regardless
                // of distance — identical to how box/health/name stay fixed.
                if (drawSkeleton) {
                    float bMinX = Float.MAX_VALUE, bMaxX = -Float.MAX_VALUE;
                    float bMinY = Float.MAX_VALUE, bMaxY = -Float.MAX_VALUE;
                    boolean anyBone = false;
                    for (int b = 0; b < MAX_BONES; b++) {
                        if (bv[b]) {
                            if (bx[b] < bMinX) bMinX = bx[b]; if (bx[b] > bMaxX) bMaxX = bx[b];
                            if (by[b] < bMinY) bMinY = by[b]; if (by[b] > bMaxY) bMaxY = by[b];
                            anyBone = true;
                        }
                    }
                    if (anyBone) {
                        float rX = Math.max(bMaxX - bMinX, 1f);
                        float rY = Math.max(bMaxY - bMinY, 1f);
                        float[] fbx = new float[MAX_BONES], fby = new float[MAX_BONES];
                        for (int b = 0; b < MAX_BONES; b++) {
                            if (bv[b]) {
                                fbx[b] = bL + FBW * (bx[b] - bMinX) / rX;
                                fby[b] = bT + FBH * (by[b] - bMinY) / rY;
                            }
                        }
                        pSkel.setColor(boxColor);
                        for (int[] link : BONE_LINKS) {
                            int f = link[0], t2 = link[1];
                            if (f < MAX_BONES && t2 < MAX_BONES && bv[f] && bv[t2])
                                canvas.drawLine(fbx[f], fby[f], fbx[t2], fby[t2], pSkel);
                        }
                    }
                }

                // ── 3. FULL RECTANGLE BOX — fill + outline (BlueFox style) ───
                if (drawSkeleton) {
                    // Subtle fill (BlueFox boxFillPaint, FILL, low alpha)
                    pBoxFill.setColor((CFG_FILL_A << 24) | (boxColor & 0x00FFFFFF));
                    canvas.drawRect(bL, bT, bR, bB, pBoxFill);
                    // Outline
                    pBox.setColor(boxColor);
                    canvas.drawRect(bL, bT, bR, bB, pBox);
                }

                // ── 4. HEALTH BAR — thin 3px strip above box (BlueFox style) ──
                if (drawHealth || drawSkeleton) {
                    float hbT = bT - CFG_HP_H - 3f;
                    float hbB = bT - 1f;
                    canvas.drawRect(bL, hbT, bR, hbB, pHpBg);
                    pHpFg.setColor(hpFadeColor(health));
                    canvas.drawRect(bL, hbT, bL + FBW * Math.max(health, 0f), hbB, pHpFg);
                }

                // ── 5. NAME below box (BlueFox mNamePaint — CENTER, below box) ──
                if (drawName) {
                    String dName = name.isEmpty() ? (isBot ? "Bot" : "?") : name;
                    pName.setTextSize(CFG_NAME_SZ);
                    pNameSh.setTextSize(CFG_NAME_SZ);
                    float nY = bB + 12f;
                    float nW = pName.measureText(dName) + 8f;
                    rf.set(boxCX - nW/2f, nY - CFG_NAME_SZ - 1f, boxCX + nW/2f, nY + 3f);
                    canvas.drawRoundRect(rf, 3, 3, pNameBg);
                    canvas.drawText(dName, boxCX, nY, pNameSh);
                    pName.setColor(Color.parseColor(CFG_NAME_C));
                    canvas.drawText(dName, boxCX, nY, pName);
                }

                // ── 6. DISTANCE below name — team color (BlueFox DistancePaint) ──
                if (drawDistance) {
                    float dy = bB + (drawName ? 26f : 13f);
                    pDist.setTextSize(CFG_DIST_SZ);
                    pDistSh.setTextSize(CFG_DIST_SZ);
                    pDist.setColor(boxColor);
                    pDistSh.setColor(Color.argb(160, 0, 0, 0));
                    canvas.drawText((int)distance + "m", boxCX, dy, pDistSh);
                    canvas.drawText((int)distance + "m", boxCX, dy, pDist);
                }
            }

            // ── VEHICLES ─────────────────────────────────────────────────────
            if (drawVehicle) {
                for (int i = 0; i < vehCount; i++) {
                    buf.position(VEHICLES_OFFSET + i * VEHICLE_STRIDE);
                    float vx   = buf.getFloat()*sw, vy = buf.getFloat()*sh;
                    float vd   = buf.getFloat();
                    boolean he = buf.get()!=0;
                    byte[] vn  = new byte[32]; buf.get(vn);
                    String vName = new String(vn).trim().replace("\0","");
                    // Small text (13sp) — same as name
                    pVeh.setColor(he ? Color.argb(215,255,80,80) : Color.argb(215,77,221,106));
                    String vt = vName+"("+((int)vd)+"M)";
                    canvas.drawText(vt, vx, vy, pVehSh);
                    canvas.drawText(vt, vx, vy, pVeh);
                }
            }

            // ── ENEMY COUNT PANEL — BlueFox mEcXxx style (top-right corner) ──
            if (drawCount) {
                drawEcPanel(canvas, sw, enemyCount, botCount);
            }

            // ── HUD: Watermark + FPS + Expiry (from SHM) ─────────────────────
            // Read fps and expiry from SHM (written by native PostRender)
            int  hudFps   = 0;
            String hudExp = "";
            if (shmBuf != null) {
                try {
                    hudFps = shmBuf.getInt(FPS_OFFSET);
                    byte[] eb = new byte[48];
                    for (int ei = 0; ei < 48; ei++) eb[ei] = shmBuf.get(EXPIRY_OFFSET + ei);
                    hudExp = new String(eb).trim().replace("\0", "");
                } catch (Throwable ignored) {}
            }

            // FPS — top left (cyan text)
            if (hudFps > 0) {
                String fpsTxt = "FPS: " + hudFps;
                pDist.setTextSize(HUD_FPS_SZ);
                pDist.setColor(Color.argb(230, 0, 255, 255)); // CYAN
                pDistSh.setTextSize(HUD_FPS_SZ);
                pDistSh.setColor(Color.argb(200, 0, 0, 0));
                pDist.setTextAlign(Paint.Align.LEFT);
                pDistSh.setTextAlign(Paint.Align.LEFT);
                canvas.drawText(fpsTxt, HUD_FPS_X + 1, HUD_FPS_Y + 1, pDistSh);
                canvas.drawText(fpsTxt, HUD_FPS_X,     HUD_FPS_Y,     pDist);
                pDist.setTextAlign(Paint.Align.CENTER);
                pDistSh.setTextAlign(Paint.Align.CENTER);
                pDist.setTextSize(CFG_NAME_SZ); pDistSh.setTextSize(CFG_NAME_SZ);
            }

            // Expiry — bottom left (red text)
            if (!hudExp.isEmpty()) {
                float expY = sh - HUD_EXP_Y_OFF;
                pDist.setTextSize(HUD_EXP_SZ); pDistSh.setTextSize(HUD_EXP_SZ);
                pDist.setColor(Color.argb(230, 255, 50, 50)); // RED
                pDist.setTextAlign(Paint.Align.LEFT); pDistSh.setTextAlign(Paint.Align.LEFT);
                canvas.drawText(hudExp, HUD_EXP_X + 1, expY + 1, pDistSh);
                canvas.drawText(hudExp, HUD_EXP_X,     expY,     pDist);
                pDist.setTextAlign(Paint.Align.CENTER); pDistSh.setTextAlign(Paint.Align.CENTER);
                pDist.setTextSize(CFG_NAME_SZ); pDistSh.setTextSize(CFG_NAME_SZ);
            }

            // Watermark "ARK CHEAT BGMI" — bottom right (3-color)
            {
                float wmY   = sh - WM_Y_OFF;
                pName.setTextSize(WM_FONT); pNameSh.setTextSize(WM_FONT);
                pName.setFakeBoldText(true);
                // measure each part
                pName.setColor(Color.WHITE);
                float w1 = pName.measureText(WM1);
                float w2 = pName.measureText(WM2);
                float w3 = pName.measureText(WM3);
                float wmX = sw - WM_X_OFF - w1 - w2 - w3;
                pName.setTextAlign(Paint.Align.LEFT); pNameSh.setTextAlign(Paint.Align.LEFT);
                // shadows
                canvas.drawText(WM1, wmX+1,       wmY+1, pNameSh);
                canvas.drawText(WM2, wmX+w1+1,    wmY+1, pNameSh);
                canvas.drawText(WM3, wmX+w1+w2+1, wmY+1, pNameSh);
                // colored text
                pName.setColor(Color.argb(255,255,220,0));   // ARK = yellow
                canvas.drawText(WM1, wmX, wmY, pName);
                pName.setColor(Color.argb(255,255,120,0));   // CHEAT = orange
                canvas.drawText(WM2, wmX+w1, wmY, pName);
                pName.setColor(Color.WHITE);                  // BGMI = white
                canvas.drawText(WM3, wmX+w1+w2, wmY, pName);
                // reset
                pName.setTextAlign(Paint.Align.CENTER); pNameSh.setTextAlign(Paint.Align.CENTER);
                pName.setTextSize(CFG_NAME_SZ); pNameSh.setTextSize(CFG_NAME_SZ);
            }
        }
    }

    // ── Logger ────────────────────────────────────────────────────────────────
    static void espLog(String sub, String msg) {
        android.util.Log.i("ArkESP/"+sub, msg);
        try { ContainerLogger.li("ArkESP/"+sub, msg); } catch (Throwable ignored) {}
    }

    private void logDiag(boolean any) {
        String f="Line="+(drawLine?"ON":"off")+" Box="+(drawSkeleton?"ON":"off")
            +" HP="+(drawHealth?"ON":"off")+" Dist="+(drawDistance?"ON":"off")
            +" Name="+(drawName?"ON":"off")+" Cnt="+(drawCount?"ON":"off")
            +" Veh="+(drawVehicle?"ON":"off");
        espLog("FLAGS",f);
        if (shmBuf==null) {
            espLog("SHM","✘ shmBuf=NULL "+(shmPathUsed==null?"(no path)":shmPathUsed));
        } else {
            int sw2=shmBuf.getInt(0),pc=shmBuf.getInt(16),ec=shmBuf.getInt(24),bc=shmBuf.getInt(28);
            espLog("SHM","✔ "+shmPathUsed+" seqW="+sw2+" players="+pc
                +" enemies="+ec+" bots="+bc+" frames="+dbgFramesRead+" anyFeat="+any);
            if (sw2==0) espLog("SHM","⚠ seqWrite=0 — native never wrote");
            if (pc==0&&ec==0) espLog("SHM","⚠ playerCount=0 — ESP loop not filling");
        }
    }
}
