package com.bgmi;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.Window;
import android.view.WindowManager;

/**
 * Landscape ESP preview — shows all overlay elements with fake demo data.
 * No distance-based scaling: every box is the same pixel size regardless of
 * the fake distance shown. Tap anywhere to close.
 *
 * To open from your login screen:
 *   startActivity(new Intent(this, TrialPreviewActivity.class));
 */
public class TrialPreviewActivity extends Activity implements SurfaceHolder.Callback {

    private SurfaceView sv;
    private SurfaceHolder holder;
    private final Handler handler = new Handler();
    private volatile boolean running = false;

    // Fixed ESP box size — same at every distance (no scaling)
    private static final float BOX_W = 44f;
    private static final float BOX_H = 72f;

    // Demo players: { headX%, headY%, distM, isBot(0/1), healthPct }
    private static final float[][] PLAYERS = {
        {0.22f, 0.44f,  12f, 0, 0.90f},   // enemy – near left
        {0.50f, 0.32f,  78f, 0, 0.52f},   // enemy – center
        {0.76f, 0.40f, 152f, 0, 0.28f},   // enemy – far right
        {0.17f, 0.62f,  46f, 1, 0.88f},   // bot   – lower left
        {0.64f, 0.58f,  95f, 1, 0.72f},   // bot   – lower right
    };
    private static final String[] NAMES = {"Dravid", "PlayerXX", "Eagle99", "Bot", "Bot"};

    // Vehicle: { x%, y%, distM, hasEnemy(0/1) }
    private static final float[] VEHICLE = {0.86f, 0.20f, 210f, 1};

    // Enemy and bot counts for the pill counter demo
    private static final int ENEMY_COUNT = 3;
    private static final int BOT_COUNT   = 2;

    private Paint fill, stroke, text;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        sv = new SurfaceView(this);
        sv.getHolder().setFormat(PixelFormat.RGBA_8888);
        sv.getHolder().addCallback(this);
        setContentView(sv);

        sv.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_UP) finish();
            return true;
        });

        fill   = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        text   = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setFakeBoldText(true);
    }

    @Override public void surfaceCreated(SurfaceHolder h)  { holder = h; running = true;  handler.post(loop); }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int ht) {}
    @Override public void surfaceDestroyed(SurfaceHolder h){ running = false; handler.removeCallbacks(loop); }
    @Override protected void onDestroy() { running = false; handler.removeCallbacks(loop); super.onDestroy(); }

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            if (!running) return;
            draw();
            handler.postDelayed(this, 33);
        }
    };

    // ── Main draw ─────────────────────────────────────────────────────────────

    private void draw() {
        Canvas c = null;
        try {
            c = holder.lockCanvas();
            if (c == null) return;
            float sw = c.getWidth(), sh = c.getHeight();

            c.drawColor(Color.BLACK);

            // Pill counter (split: ENEMY_COUNT | BOT_COUNT)
            float pillBottomY = drawPillCounter(c, sw, sh, ENEMY_COUNT, BOT_COUNT);

            // ESP lines — pill-bottom-center → every player head (drawn behind players)
            for (float[] p : PLAYERS) {
                float hx = p[0] * sw, hy = p[1] * sh;
                boolean isBot = p[3] != 0;
                stroke.setColor(isBot ? 0x99FFD03B : 0x9974FF4A);
                stroke.setStrokeWidth(3.5f);
                c.drawLine(sw * 0.5f, pillBottomY + 4f, hx, hy, stroke);
            }

            // Players
            for (int i = 0; i < PLAYERS.length; i++) drawPlayer(c, sw, sh, i);

            // Vehicle
            drawVehicle(c, sw, sh);

            // FPS top-left
            text.setTextSize(40f);
            text.setColor(0xFF00FFFF);
            c.drawText("FPS:60", 16f, 54f, text);

            // Expiry bottom-left
            text.setTextSize(26f);
            float expH = text.getTextSize();
            fill.setColor(Color.argb(191, 0, 0, 0));
            String expStr = "ACCESS VALIDITY 30 DAYS";
            float expW = text.measureText(expStr);
            c.drawRect(86f, sh - expH - 28f, 86f + expW + 12f, sh - 18f, fill);
            text.setColor(0xFFFF3333);
            c.drawText(expStr, 92f, sh - 22f, text);

            // Watermark bottom-right
            text.setTextSize(28f);
            String ark = "ARK", cheat = " CHEAT", bgmi = " BGMI";
            float wX = sw - text.measureText(ark) - text.measureText(cheat)
                           - text.measureText(bgmi) - 24f;
            float wY = sh - 18f;
            text.setColor(Color.WHITE);         c.drawText(ark,   wX, wY, text); wX += text.measureText(ark);
            text.setColor(0xFFFF9900);          c.drawText(cheat, wX, wY, text); wX += text.measureText(cheat);
            text.setColor(0xFFA4FF73);          c.drawText(bgmi,  wX, wY, text);

            // Hint
            text.setTextSize(20f);
            text.setColor(0x66FFFFFF);
            String hint = "TRIAL MODE — TAP TO CLOSE";
            c.drawText(hint, sw * 0.5f - text.measureText(hint) * 0.5f, sh - 48f, text);

        } finally {
            if (c != null) holder.unlockCanvasAndPost(c);
        }
    }

    // ── Pill counter ──────────────────────────────────────────────────────────

    private float drawPillCounter(Canvas c, float sw, float sh, int ec, int bc) {
        final float PILH = 36f, PADX = 24f, PILY = 12f;
        float cx = sw * 0.5f;

        if (ec <= 0 && bc <= 0) {
            // "Clear" pill — green metallic
            text.setTextSize(32f);
            float tw = text.measureText("Clear"), pillW = tw + PADX * 2f;
            pillFill(c, cx, PILY, pillW, PILH, Color.argb(229, 18, 51, 26));
            pillFill(c, cx, PILY, pillW, PILH * 0.32f, Color.argb(36, 77, 230, 102));
            pillBorder(c, cx, PILY, pillW, PILH, Color.argb(153, 56, 199, 84));
            drawPillText(c, cx - tw * 0.5f, PILY, PILH, "Clear", 32f, 0xFFA4FF73);

        } else if (ec > 0 && bc <= 0) {
            // Enemy-only — red metallic
            text.setTextSize(32f);
            String s = String.format("%02d", ec);
            float tw = text.measureText(s), pillW = tw + PADX * 2f;
            pillFill(c, cx, PILY, pillW, PILH, Color.argb(229, 56, 15, 18));
            pillFill(c, cx, PILY, pillW, PILH * 0.32f, Color.argb(31, 255, 77, 77));
            pillBorder(c, cx, PILY, pillW, PILH, Color.argb(166, 217, 61, 66));
            drawPillText(c, cx - tw * 0.5f, PILY, PILH, s, 32f, 0xFFFF7373);

        } else if (ec <= 0 && bc > 0) {
            // Bot-only — yellow metallic
            text.setTextSize(32f);
            String s = String.format("%02d", bc);
            float tw = text.measureText(s), pillW = tw + PADX * 2f;
            pillFill(c, cx, PILY, pillW, PILH, Color.argb(229, 51, 46, 10));
            pillFill(c, cx, PILY, pillW, PILH * 0.32f, Color.argb(31, 255, 224, 51));
            pillBorder(c, cx, PILY, pillW, PILH, Color.argb(166, 224, 199, 61));
            drawPillText(c, cx - tw * 0.5f, PILY, PILH, s, 32f, 0xFFFFE659);

        } else {
            // Split pill
            text.setTextSize(32f);
            String eStr = String.format("%02d", ec), bStr = String.format("%02d", bc);
            float etw = text.measureText(eStr), btw = text.measureText(bStr);
            float halfW = Math.max(etw, btw) + PADX * 2f;
            float pillW = halfW * 2f;
            float lx = cx - pillW * 0.5f;
            float cr = PILH * 0.38f;

            // Base pill
            pillFill(c, cx, PILY, pillW, PILH, Color.argb(235, 26, 26, 36));
            // Left red tint
            fill.setColor(Color.argb(120, 140, 15, 20));
            c.drawRect(lx + cr, PILY, cx, PILY + PILH, fill);
            // Right yellow tint
            fill.setColor(Color.argb(100, 115, 97, 10));
            c.drawRect(cx, PILY, lx + pillW - cr, PILY + PILH, fill);
            // Top highlight
            pillFill(c, cx, PILY, pillW, PILH * 0.32f, Color.argb(25, 178, 184, 204));
            // Border
            pillBorder(c, cx, PILY, pillW, PILH, Color.argb(140, 140, 148, 173));
            // Divider
            stroke.setColor(Color.argb(128, 140, 148, 173));
            stroke.setStrokeWidth(1.5f);
            c.drawLine(cx, PILY + 4f, cx, PILY + PILH - 4f, stroke);

            float eCX = (lx + cx) * 0.5f;
            float bCX = (cx + lx + pillW) * 0.5f;
            drawPillText(c, eCX - etw * 0.5f, PILY, PILH, eStr, 32f, 0xFFFF7373);
            drawPillText(c, bCX - btw * 0.5f, PILY, PILH, bStr, 32f, 0xFFFFE659);
        }

        return PILY + PILH;
    }

    private void drawPillText(Canvas c, float tx, float pillY, float pillH,
                               String s, float sz, int color) {
        text.setTextSize(sz);
        float ty = pillY + (pillH + sz) * 0.5f - text.descent();
        // shadow
        text.setColor(Color.argb(204, 0, 0, 0));
        c.drawText(s, tx + 1.5f, ty + 1.5f, text);
        text.setColor(color);
        c.drawText(s, tx, ty, text);
    }

    private void pillFill(Canvas c, float cx, float y, float w, float h, int color) {
        float cr = h * 0.38f, x = cx - w * 0.5f;
        fill.setColor(color);
        c.drawRect(x + cr, y, x + w - cr, y + h, fill);
        c.drawRect(x, y + cr * 0.5f, x + cr, y + h - cr * 0.5f, fill);
        c.drawRect(x + w - cr, y + cr * 0.5f, x + w, y + h - cr * 0.5f, fill);
    }

    private void pillBorder(Canvas c, float cx, float y, float w, float h, int color) {
        float cr = h * 0.38f, x = cx - w * 0.5f;
        stroke.setColor(color); stroke.setStrokeWidth(1.5f);
        c.drawLine(x + cr, y, x + w - cr, y, stroke);
        c.drawLine(x + cr, y + h, x + w - cr, y + h, stroke);
        c.drawLine(x, y + cr * 0.5f, x, y + h - cr * 0.5f, stroke);
        c.drawLine(x + w, y + cr * 0.5f, x + w, y + h - cr * 0.5f, stroke);
        c.drawLine(x, y + cr * 0.5f, x + cr, y, stroke);
        c.drawLine(x + w - cr, y, x + w, y + cr * 0.5f, stroke);
        c.drawLine(x, y + h - cr * 0.5f, x + cr, y + h, stroke);
        c.drawLine(x + w - cr, y + h, x + w, y + h - cr * 0.5f, stroke);
    }

    // ── Player ────────────────────────────────────────────────────────────────

    private void drawPlayer(Canvas c, float sw, float sh, int idx) {
        float hx   = PLAYERS[idx][0] * sw;
        float hy   = PLAYERS[idx][1] * sh;
        float dist = PLAYERS[idx][2];
        boolean isBot = PLAYERS[idx][3] != 0;
        float hp   = PLAYERS[idx][4];

        float bL = hx - BOX_W * 0.5f;
        float bR = hx + BOX_W * 0.5f;
        float bT = hy - 2f;
        float bB = bT + BOX_H;

        int boxColor = isBot ? 0xFFFFC96B : 0xFFA4FF73;

        // Skeleton (stick figure, remapped into fixed box)
        drawSkeleton(c, hx, bT, BOX_W, BOX_H,
            Color.argb(200, Color.red(boxColor), Color.green(boxColor), Color.blue(boxColor)));

        // Corner box (same L-shape style as OpenGL renderer)
        float cLen = BOX_W * 0.28f;
        stroke.setColor(boxColor); stroke.setStrokeWidth(2.5f);
        c.drawLine(bL, bT, bL + cLen, bT, stroke);         c.drawLine(bL, bT, bL, bT + cLen, stroke);
        c.drawLine(bR - cLen, bT, bR, bT, stroke);         c.drawLine(bR, bT, bR, bT + cLen, stroke);
        c.drawLine(bL, bB - cLen, bL, bB, stroke);         c.drawLine(bL, bB, bL + cLen, bB, stroke);
        c.drawLine(bR - cLen, bB, bR, bB, stroke);         c.drawLine(bR, bB - cLen, bR, bB, stroke);

        // Health bar
        float hbT = bT - 9f, hbB = bT - 5f;
        fill.setColor(Color.argb(178, 20, 20, 20));
        c.drawRect(bL, hbT, bR, hbB, fill);
        int hColor = hp > 0.6f ? Color.argb(255, 0, 220, 0)
                   : hp > 0.3f ? Color.argb(255, 255, 200, 0)
                   :              Color.argb(255, 255, 50, 0);
        fill.setColor(hColor);
        c.drawRect(bL, hbT, bL + BOX_W * hp, hbB, fill);

        // Name
        text.setTextSize(20f);
        String name = NAMES[idx];
        float ntw = text.measureText(name), nY = bB + 20f, nX = hx - ntw * 0.5f;
        fill.setColor(Color.argb(140, 0, 0, 0));
        c.drawRect(nX - 3f, nY - 17f, nX + ntw + 3f, nY + 3f, fill);
        text.setColor(Color.argb(204, 0, 0, 0)); c.drawText(name, nX + 1f, nY + 1f, text);
        text.setColor(Color.WHITE);              c.drawText(name, nX, nY, text);

        // Distance
        String dStr = (int) dist + "m";
        float dtw = text.measureText(dStr), dY = nY + 22f, dX = hx - dtw * 0.5f;
        text.setColor(Color.argb(204, 0, 0, 0)); c.drawText(dStr, dX + 1f, dY + 1f, text);
        text.setColor(boxColor);                 c.drawText(dStr, dX, dY, text);
    }

    private void drawSkeleton(Canvas c, float hx, float bT, float bW, float bH, int col) {
        stroke.setColor(col); stroke.setStrokeWidth(1.5f);
        float cx = hx;
        float headCY = bT + bH * 0.09f;
        c.drawCircle(cx, headCY, bW * 0.11f, stroke);      // head
        float neckY   = bT + bH * 0.18f;
        float waistY  = bT + bH * 0.55f;
        c.drawLine(cx, neckY, cx, waistY, stroke);          // spine
        float footY   = bT + bH * 0.95f;
        c.drawLine(cx, waistY, cx - bW*0.18f, footY, stroke);
        c.drawLine(cx, waistY, cx + bW*0.18f, footY, stroke); // legs
        float sY = bT + bH * 0.29f;
        c.drawLine(cx, sY, cx - bW*0.42f, bT + bH * 0.50f, stroke);
        c.drawLine(cx, sY, cx + bW*0.42f, bT + bH * 0.50f, stroke); // arms
    }

    // ── Vehicle ───────────────────────────────────────────────────────────────

    private void drawVehicle(Canvas c, float sw, float sh) {
        float vx = VEHICLE[0] * sw, vy = VEHICLE[1] * sh;
        boolean hasEnemy = VEHICLE[3] != 0;
        String label = "UAZ (" + (int) VEHICLE[2] + "m)";
        text.setTextSize(34f);
        float tw = text.measureText(label);
        float nx = vx - tw * 0.5f, ny = vy;
        fill.setColor(Color.argb(204, 10, 10, 15));
        c.drawRect(nx - 4f, ny - 30f, nx + tw + 4f, ny + 4f, fill);
        text.setColor(Color.argb(204, 0, 0, 0)); c.drawText(label, nx + 1.5f, ny + 1.5f, text);
        text.setColor(hasEnemy ? 0xFFFF4F4F : 0xFF4BDE6A);
        c.drawText(label, nx, ny, text);
    }
}
