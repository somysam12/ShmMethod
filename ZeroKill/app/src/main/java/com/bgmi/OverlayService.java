package com.bgmi;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.core.content.res.ResourcesCompat;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

@SuppressWarnings({"unused","deprecation"})
public class OverlayService extends Service {

    // ── XDCheat Elite Palette ────────────────────────────────────────────────
    static final int C_PURPLE      = Color.parseColor("#8E2DE2");
    static final int C_INDIGO      = Color.parseColor("#4A00E0");
    static final int C_PANEL       = Color.parseColor("#0e0e0e");
    static final int C_PANEL_BRD   = Color.parseColor("#1f1f1f");
    static final int C_RAISED      = Color.parseColor("#262626");
    static final int C_SECTION     = Color.parseColor("#1c1c1c");
    static final int C_BTN         = Color.parseColor("#3d3d3d");
    static final int C_TRACK       = Color.parseColor("#3d3d3d");
    static final int C_LABEL       = Color.parseColor("#ececec");
    static final int C_HEAD_TEXT   = Color.parseColor("#9232E0");
    static final int C_WHITE       = Color.WHITE;
    static final int C_DIMMED      = Color.parseColor("#888888");

    static final String TCP_HOST = "127.0.0.1";
    static final int    TCP_PORT = 28900;

    private WindowManager wm;
    private View iconRoot;
    private View menuView;
    private WindowManager.LayoutParams iconParams, menuParams;
    private boolean menuOpen = false;

    private LinearLayout contentPanel;
    private LinearLayout tabVisual, tabAim, tabMisc, tabDeviceId;
    private String currentTab = "VISUAL";
    private Typeface fHeading, fBody, fMono;
    private Vibrator vibrator;
    private int lastMenuX = Integer.MIN_VALUE, lastMenuY = Integer.MIN_VALUE;

    // State
    private boolean stLine=true, stSkel=true, stHealth=true, stBox=true,
                    stDist=true, stCount=true;
    private boolean stVehicle=true, stItems=true, stGrenade=true;
    private boolean stBtrack=true;
    private boolean stTimeFixer=false, stFps120=false, stWide=true, stHideEsp=false;
    private boolean menuSkipActive=false, iconSkipActive=false;
    private int     stDistance=120;
    private int     stBtHit=33;
    private int     stIpadRange=120;
    private boolean stIgnBot=false, stIgnKnocked=false;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        cleanupOverlay();
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    private void cleanupOverlay() {
        tcpRunning = false;
        try { cmdQueue.offer("ping"); } catch (Exception ignored) {}
        if (menuView != null) { try { wm.removeView(menuView); } catch (Exception ig) {} menuView = null; }
        if (iconRoot != null) { try { wm.removeView(iconRoot); } catch (Exception ig) {} iconRoot = null; }
    }

    @Override public void onCreate() {
        super.onCreate();
        loadFonts();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        startTcpSender();
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        addFloatingIcon();
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::sendInitialState, 2000);
    }

    private void loadFonts() {
        try {
            int id = getResources().getIdentifier("rajdhani_bold", "font", getPackageName());
            fHeading = id != 0 ? ResourcesCompat.getFont(this, id) : Typeface.DEFAULT_BOLD;
        } catch (Exception e) { fHeading = Typeface.DEFAULT_BOLD; }
        try {
            int id = getResources().getIdentifier("poppins_medium", "font", getPackageName());
            fBody = id != 0 ? ResourcesCompat.getFont(this, id) : Typeface.DEFAULT;
        } catch (Exception e) { fBody = Typeface.DEFAULT; }
        try {
            int id = getResources().getIdentifier("en_regular", "font", getPackageName());
            fMono = id != 0 ? ResourcesCompat.getFont(this, id) : Typeface.MONOSPACE;
        } catch (Exception e) { fMono = Typeface.MONOSPACE; }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private int res(String name, String type) {
        return getResources().getIdentifier(name, type, getPackageName());
    }

    private void vibrate() {
        if (vibrator == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                vibrator.vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE));
            else vibrator.vibrate(18);
        } catch (Exception ignored) {}
    }

    private int overlayType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        return WindowManager.LayoutParams.TYPE_PHONE;
    }

    // ── FLOATING ICON ────────────────────────────────────────────────────────
    private void addFloatingIcon() {
        FrameLayout wrap = new FrameLayout(this);

        ImageView img = new ImageView(this);
        img.setImageResource(res("logopng", "drawable"));
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        img.setPadding(dp(10), dp(10), dp(10), dp(10));
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dp(50), dp(50));
        ilp.gravity = Gravity.CENTER;
        wrap.addView(img, ilp);

        iconParams = new WindowManager.LayoutParams(
                dp(52), dp(52), overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        iconParams.gravity = Gravity.TOP | Gravity.START;
        iconParams.x = dp(20); iconParams.y = dp(200);
        iconRoot = wrap;
        wm.addView(iconRoot, iconParams);
        if (stHideEsp) iconRoot.post(() -> applySkipScreenshot(iconRoot, iconParams, true));

        wrap.setOnTouchListener(new View.OnTouchListener() {
            int initX, initY; float tx, ty; long downMs;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initX=iconParams.x; initY=iconParams.y;
                        tx=e.getRawX(); ty=e.getRawY(); downMs=System.currentTimeMillis();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        iconParams.x=initX+(int)(e.getRawX()-tx);
                        iconParams.y=initY+(int)(e.getRawY()-ty);
                        try{ wm.updateViewLayout(iconRoot,iconParams); }catch(Exception ig){}
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (System.currentTimeMillis()-downMs < 200
                                && Math.abs(e.getRawX()-tx)<10 && Math.abs(e.getRawY()-ty)<10) {
                            vibrate();
                            if (menuOpen) closeMenu(); else { buildMenu(); menuOpen=true; }
                        }
                        return true;
                }
                return false;
            }
        });
    }

    private void closeMenu() {
        if (menuView != null) {
            if (menuParams != null) { lastMenuX=menuParams.x; lastMenuY=menuParams.y; }
            try { wm.removeView(menuView); } catch (Exception ignored) {}
            menuView = null;
        }
        menuOpen = false;
        menuSkipActive = false;
        if (iconRoot != null) {
            if (lastMenuX != Integer.MIN_VALUE && iconParams != null) {
                iconParams.x = lastMenuX;
                iconParams.y = lastMenuY;
                try { wm.updateViewLayout(iconRoot, iconParams); } catch (Exception ignored) {}
            }
            iconRoot.setVisibility(View.VISIBLE);
        }
    }

    // ── BUILD MENU — XDCheat Elite style ────────────────────────────────────
    private void buildMenu() {
        // Root: transparent container — sidebar and rightPanel each have their own backgrounds
        FrameLayout outer = new FrameLayout(this);

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        inner.setPadding(0, 0, 0, 0);

        // ── SIDEBAR (110dp wide, radius 17dp) ────────────────────────────────
        LinearLayout sidebar = new LinearLayout(this);
        sidebar.setOrientation(LinearLayout.VERTICAL);
        sidebar.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable sideBg = new GradientDrawable();
        sideBg.setCornerRadius(dp(17));
        sideBg.setColor(C_PANEL);
        sideBg.setStroke(dp(1.5f), C_PANEL_BRD);
        sidebar.setBackground(sideBg);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(120),
                LinearLayout.LayoutParams.MATCH_PARENT);
        slp.rightMargin = dp(6);
        sidebar.setLayoutParams(slp);
        sidebar.setPadding(dp(7), dp(7), dp(7), dp(7));

        // Logo box
        FrameLayout logoBox = new FrameLayout(this);
        ImageView logoImg = new ImageView(this);
        logoImg.setImageResource(res("logopng", "drawable"));
        logoImg.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams lilp = new FrameLayout.LayoutParams(dp(60), dp(55));
        lilp.gravity = Gravity.CENTER;
        logoBox.addView(logoImg, lilp);
        LinearLayout.LayoutParams lblp = new LinearLayout.LayoutParams(dp(60), dp(55));
        lblp.gravity = Gravity.CENTER_HORIZONTAL;
        lblp.bottomMargin = dp(24);
        lblp.topMargin = dp(16);
        logoBox.setClickable(true);
        logoBox.setOnClickListener(v -> { vibrate(); closeMenu(); });
        sidebar.addView(logoBox, lblp);

        // Nav items: VISUALS, COMBAT, MISC, DEVICE ID — Vision_Tw icons
        tabVisual  = buildNavItem("VISUALS",   "VISUAL", res("esp",      "drawable"));
        tabAim     = buildNavItem("COMBAT",    "AIM",    res("aimbot",   "drawable"));
        tabMisc    = buildNavItem("MISC",      "MISC",   res("security", "drawable"));
        tabDeviceId = buildNavItem("DEVICE ID","DEVID",  res("security", "drawable"));
        sidebar.addView(tabVisual);
        sidebar.addView(tabAim);
        sidebar.addView(tabMisc);
        sidebar.addView(tabDeviceId);
        inner.addView(sidebar);

        // ── RIGHT PANEL ──────────────────────────────────────────────────────
        LinearLayout rightPanel = new LinearLayout(this);
        rightPanel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setCornerRadius(dp(17));
        panelBg.setColor(C_PANEL);
        panelBg.setStroke(dp(1.5f), C_PANEL_BRD);
        rightPanel.setBackground(panelBg);
        rightPanel.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));

        // ── HEADER BAR (45dp tall, Vision_Tw exact) ──────────────────────────
        FrameLayout header = new FrameLayout(this);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(45));
        header.setLayoutParams(hlp);
        header.setPadding(dp(16), 0, dp(16), 0);

        // Title: "VISION ELITE", 18sp, bold, letterSpacing=0.15
        TextView titleTv = new TextView(this);
        titleTv.setText("VISION ELITE");
        titleTv.setTextColor(C_WHITE);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        titleTv.setTypeface(fHeading, Typeface.BOLD);
        titleTv.setLetterSpacing(0.15f);
        FrameLayout.LayoutParams ttlp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        ttlp.gravity = Gravity.CENTER_VERTICAL | Gravity.START;
        ttlp.leftMargin = dp(4);
        ttlp.rightMargin = dp(44);
        titleTv.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        header.addView(titleTv, ttlp);

        // Close button: 32×32dp, #33ffffff bg, 6dp radius, × inside
        FrameLayout closeBtn = new FrameLayout(this);
        GradientDrawable cbg = new GradientDrawable();
        cbg.setCornerRadius(dp(6));
        cbg.setColor(Color.parseColor("#33ffffff"));
        closeBtn.setBackground(cbg);
        TextView closeX = new TextView(this);
        closeX.setText("✕");
        closeX.setTextColor(C_WHITE);
        closeX.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        closeX.setGravity(Gravity.CENTER);
        closeBtn.addView(closeX, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        closeBtn.setOnClickListener(v -> { vibrate(); closeMenu(); });
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(dp(32), dp(32));
        clp.gravity = Gravity.CENTER_VERTICAL | Gravity.END;
        header.addView(closeBtn, clp);
        rightPanel.addView(header);

        // Divider: #1affffff, 1dp, marginH=16dp
        View headLine = new View(this);
        headLine.setBackgroundColor(Color.parseColor("#1affffff"));
        LinearLayout.LayoutParams hllp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        hllp.leftMargin = dp(16); hllp.rightMargin = dp(16);
        rightPanel.addView(headLine, hllp);

        // Content scroll area
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        contentPanel = new LinearLayout(this);
        contentPanel.setOrientation(LinearLayout.VERTICAL);
        contentPanel.setPadding(dp(12), dp(12), dp(12), dp(8));
        scroll.addView(contentPanel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        rightPanel.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));
        inner.addView(rightPanel);

        outer.addView(inner, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        menuParams = new WindowManager.LayoutParams(
                dp(460), dp(300), overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        menuParams.gravity = Gravity.TOP | Gravity.START;
        if (lastMenuX != Integer.MIN_VALUE) {
            menuParams.x = lastMenuX;
            menuParams.y = lastMenuY;
        } else {
            menuParams.x = iconParams.x + dp(64);
            menuParams.y = iconParams.y;
        }

        // Black border rectangle wrapper
        FrameLayout outerFrame = new FrameLayout(this);
        outerFrame.setBackgroundColor(Color.BLACK);
        FrameLayout.LayoutParams rootInnerLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        rootInnerLp.setMargins(dp(6), dp(6), dp(6), dp(6));
        outerFrame.addView(outer, rootInnerLp);

        // Top-left corner drag handle only
        View dragHandle = new View(this);
        dragHandle.setClickable(true);
        dragHandle.setFocusable(false);
        dragHandle.setOnTouchListener(new View.OnTouchListener() {
            int initX, initY; float tx, ty; long lastMs = 0;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initX=menuParams.x; initY=menuParams.y;
                        tx=e.getRawX(); ty=e.getRawY(); return true;
                    case MotionEvent.ACTION_MOVE:
                        long now=System.currentTimeMillis();
                        if (now-lastMs<8) return true; lastMs=now;
                        menuParams.x=initX+(int)(e.getRawX()-tx);
                        menuParams.y=initY+(int)(e.getRawY()-ty);
                        try { wm.updateViewLayout(menuView, menuParams); } catch (Exception ig) {}
                        return true;
                }
                return false;
            }
        });
        FrameLayout.LayoutParams handleLp = new FrameLayout.LayoutParams(dp(44), dp(44));
        handleLp.gravity = Gravity.TOP | Gravity.START;
        outerFrame.addView(dragHandle, handleLp);

        menuView = outerFrame;
        if (iconRoot != null) iconRoot.setVisibility(View.GONE);
        wm.addView(menuView, menuParams);
        selectTab(currentTab);
        if (stHideEsp) menuView.post(() -> updateHideEspWindows(true));
    }

    private LinearLayout buildNavItem(String label, String key, int iconRes) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        rlp.bottomMargin = dp(4);
        row.setLayoutParams(rlp);
        row.setClickable(true);
        row.setFocusable(true);

        // Icon wrapper: 18×18dp, 4dp radius
        FrameLayout iconWrap = new FrameLayout(this);
        iconWrap.setTag("iw");
        GradientDrawable iwBg = new GradientDrawable();
        iwBg.setCornerRadius(dp(4));
        iwBg.setColor(Color.parseColor("#0dffffff"));
        iconWrap.setBackground(iwBg);
        ImageView icon = new ImageView(this);
        if (iconRes != 0) {
            icon.setImageResource(iconRes);
            icon.setColorFilter(C_WHITE, android.graphics.PorterDuff.Mode.SRC_IN);
        }
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams iilp = new FrameLayout.LayoutParams(dp(12), dp(12));
        iilp.gravity = Gravity.CENTER;
        iconWrap.addView(icon, iilp);
        row.addView(iconWrap, new LinearLayout.LayoutParams(dp(18), dp(18)));

        // Label: 9sp, bold, letterSpacing=0.1
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(Color.parseColor("#8cffffff"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
        tv.setTypeface(fHeading, Typeface.BOLD);
        tv.setLetterSpacing(0.1f);
        LinearLayout.LayoutParams tvlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        tvlp.leftMargin = dp(10);
        row.addView(tv, tvlp);
        row.setTag(tv);
        row.setOnClickListener(v -> { vibrate(); selectTab(key); });
        return row;
    }

    private void selectTab(String key) {
        currentTab = key;
        applyNavStyle(tabVisual,   key.equals("VISUAL"));
        applyNavStyle(tabAim,      key.equals("AIM"));
        applyNavStyle(tabMisc,     key.equals("MISC"));
        applyNavStyle(tabDeviceId, key.equals("DEVID"));
        contentPanel.removeAllViews();
        switch (key) {
            case "VISUAL": buildVisual();  break;
            case "AIM":    buildAim();     break;
            case "MISC":   buildMisc();    break;
            case "DEVID":  buildDeviceId(); break;
        }
    }

    private void applyNavStyle(LinearLayout row, boolean active) {
        if (row == null) return;
        if (active) {
            GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{C_PURPLE, C_INDIGO});
            bg.setCornerRadius(dp(8));
            row.setBackground(bg);
            if (row.getTag() instanceof TextView)
                ((TextView) row.getTag()).setTextColor(C_WHITE);
            View iw = row.findViewWithTag("iw");
            if (iw instanceof FrameLayout) {
                GradientDrawable iwBg = new GradientDrawable();
                iwBg.setCornerRadius(dp(4));
                iwBg.setColor(Color.parseColor("#33ffffff"));
                iw.setBackground(iwBg);
            }
        } else {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(8));
            bg.setColor(Color.TRANSPARENT);
            row.setBackground(bg);
            if (row.getTag() instanceof TextView)
                ((TextView) row.getTag()).setTextColor(Color.parseColor("#8cffffff"));
            View iw = row.findViewWithTag("iw");
            if (iw instanceof FrameLayout) {
                GradientDrawable iwBg = new GradientDrawable();
                iwBg.setCornerRadius(dp(4));
                iwBg.setColor(Color.parseColor("#0dffffff"));
                iw.setBackground(iwBg);
            }
        }
    }

    // Section header: Vision_Tw exact — #0dffffff bg, top-only 8dp radius, purple text 11sp
    private void sectionHeader(LinearLayout parent, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(C_HEAD_TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setTypeface(fHeading, Typeface.BOLD);
        t.setLetterSpacing(0.1f);
        t.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        t.setPadding(dp(8), dp(4), dp(8), dp(4));
        GradientDrawable bg = new GradientDrawable();
        // top-only 8dp corners (TL, TR, BR, BL each as two values)
        bg.setCornerRadii(new float[]{dp(8),dp(8), dp(8),dp(8), 0,0, 0,0});
        bg.setColor(Color.parseColor("#0dffffff"));
        t.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        t.setLayoutParams(lp);
        parent.addView(t);
    }

    // Column header same as section header
    private void colHeader(LinearLayout col, String text) {
        sectionHeader(col, text);
    }

    private void checkRow(LinearLayout parent, String label, boolean initial, final CB cb) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(3), dp(2), dp(3));
        row.setClickable(true);
        row.setFocusable(true);

        final TextView box = new TextView(this);
        box.setGravity(Gravity.CENTER);
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        box.setClickable(false);
        box.setFocusable(false);
        applyCheck(box, initial);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(22), dp(22));
        blp.rightMargin = dp(6);
        row.addView(box, blp);

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(C_LABEL);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        name.setTypeface(fBody);
        name.setClickable(false);
        name.setFocusable(false);
        name.setDuplicateParentStateEnabled(true);
        row.addView(name, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final boolean[] st = {initial};
        row.setOnClickListener(v -> {
            st[0] = !st[0];
            applyCheck(box, st[0]);
            vibrate();
            cb.onToggle(st[0]);
        });
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        parent.addView(row);
    }

    private TextView checkRowRet(LinearLayout parent, String label, boolean initial, final CB cb) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(4), dp(2), dp(4));
        row.setClickable(true);
        row.setFocusable(true);

        final TextView box = new TextView(this);
        box.setGravity(Gravity.CENTER);
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        box.setClickable(false);
        box.setFocusable(false);
        applyCheck(box, initial);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(26), dp(26));
        blp.rightMargin = dp(7);
        row.addView(box, blp);

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(C_LABEL);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        name.setTypeface(fBody);
        name.setClickable(false);
        name.setFocusable(false);
        row.addView(name);

        final boolean[] st = {initial};
        row.setOnClickListener(v -> {
            st[0] = !st[0];
            applyCheck(box, st[0]);
            vibrate();
            cb.onToggle(st[0]);
        });
        parent.addView(row);
        return box;
    }

    // Checked: Vision_Tw check.xml; unchecked: Vision_Tw uncheck.xml
    private void applyCheck(TextView box, boolean on) {
        int drawRes = on ? res("check", "drawable") : res("uncheck", "drawable");
        if (drawRes != 0) {
            box.setBackground(getResources().getDrawable(drawRes, null));
            box.setText("");
            box.setTextColor(Color.TRANSPARENT);
        } else {
            GradientDrawable bg = new GradientDrawable();
            if (on) {
                bg.setShape(GradientDrawable.OVAL);
                bg.setColor(C_PURPLE);
                bg.setStroke(dp(1.5f), C_PURPLE);
                box.setText("✔");
                box.setTextColor(C_WHITE);
                box.setTypeface(null, Typeface.BOLD);
            } else {
                bg.setColor(Color.TRANSPARENT);
                bg.setStroke(dp(1.6f), Color.parseColor("#8e8e9a"));
                box.setText("");
            }
            box.setBackground(bg);
        }
    }

    // Slider: purple fill track, purple thumb with white border
    private SeekBar makeSeekBar(int min, int max, int progress) {
        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(progress - min);
        sb.setSplitTrack(false);
        sb.setBackground(null);
        sb.setPadding(dp(9), 0, dp(9), 0);

        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setShape(GradientDrawable.RECTANGLE);
        trackBg.setCornerRadius(dp(100));
        trackBg.setColor(C_TRACK);
        GradientDrawable fillShape = new GradientDrawable();
        fillShape.setCornerRadius(dp(100));
        fillShape.setColor(C_PURPLE);
        android.graphics.drawable.ClipDrawable clip =
                new android.graphics.drawable.ClipDrawable(fillShape,
                        Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL);
        android.graphics.drawable.LayerDrawable track =
                new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[]{trackBg, clip});
        track.setId(0, android.R.id.background);
        track.setId(1, android.R.id.progress);
        final int th = dp(5);
        track.setLayerHeight(0, th); track.setLayerGravity(0, Gravity.CENTER_VERTICAL);
        track.setLayerHeight(1, th); track.setLayerGravity(1, Gravity.CENTER_VERTICAL);
        sb.setProgressDrawable(track);

        GradientDrawable thumbNormal = new GradientDrawable();
        thumbNormal.setShape(GradientDrawable.OVAL);
        thumbNormal.setColor(C_PURPLE);
        thumbNormal.setStroke(dp(2f), C_WHITE);
        thumbNormal.setSize(dp(14), dp(14));
        sb.setThumb(thumbNormal);
        sb.setThumbOffset(0);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(24));
        lp.topMargin = dp(2); lp.bottomMargin = dp(6);
        sb.setLayoutParams(lp);
        return sb;
    }

    // Action button: dark gray inactive, brownish-amber active — rectangular
    private TextView makeActionBtn(String label, boolean active) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(C_WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setTypeface(fBody);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(6), dp(9), dp(6), dp(9));
        styleActionBtn(t, active);
        return t;
    }

    private void styleActionBtn(TextView t, boolean active) {
        if (active) {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(4));
            bg.setColor(C_BTN);
            t.setBackground(bg);
        } else {
            t.setBackground(null);
        }
    }

    interface CB { void onToggle(boolean on); }

    // ── VISUAL TAB ────────────────────────────────────────────────────────────
    private void buildVisual() {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.HORIZONTAL);
        grid.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout colL = newCol(dp(4), dp(5));
        LinearLayout colR = newCol(dp(5), dp(4));

        colHeader(colL, "ESP Visuals");
        checkRow(colL, "Line",        stLine,    on->{ stLine=on;    sendTcp("line="+(on?1:0)); });
        checkRow(colL, "Skeleton",    stSkel,    on->{ stSkel=on;    sendTcp("skeleton="+(on?1:0)); });
        checkRow(colL, "Health",      stHealth,  on->{ stHealth=on;  sendTcp("health="+(on?1:0)); });
        checkRow(colL, "Box",         stBox,     on->{ stBox=on;     sendTcp("box="+(on?1:0)); });
        checkRow(colL, "Distance",    stDist,    on->{ stDist=on;    sendTcp("distance="+(on?1:0)); });
        checkRow(colL, "Enemy Count", stCount,   on->{ stCount=on;   sendTcp("count="+(on?1:0)); });

        colHeader(colR, "World Visuals");
        checkRow(colR, "Vehicles",          stVehicle, on->{ stVehicle=on; sendTcp("vehicle="+(on?1:0)); });

        grid.addView(colL); grid.addView(colR);
        contentPanel.addView(grid);
    }

    // ── AIM TAB ───────────────────────────────────────────────────────────────
    private void buildAim() {
        sectionHeader(contentPanel, "COMBAT FEATURES");

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        checkRow(col, "Bullet Track", stBtrack, on->{ stBtrack=on; sendTcp("btrack="+(on?1:0)); });

        // Distance slider
        TextView distLbl = new TextView(this);
        distLbl.setText("Distance: (" + stDistance + " m)");
        distLbl.setTextColor(Color.parseColor("#d2d2d2"));
        distLbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        distLbl.setTypeface(fBody);
        distLbl.setPadding(dp(2), dp(8), 0, dp(4));
        distLbl.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        col.addView(distLbl);

        SeekBar distBar = makeSeekBar(10, 150, stDistance);
        final boolean[] flagWarningActive = {false};
        distBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s, int p, boolean fromUser){
                int newVal = p + 10;
                if (fromUser && newVal > 120 && !flagWarningActive[0]) {
                    flagWarningActive[0] = true;
                    final int chosenVal = newVal;
                    showFlagWarning(
                        () -> { // CONFIRM
                            flagWarningActive[0] = false;
                            stDistance = chosenVal;
                            distLbl.setText("Distance: (" + stDistance + " m)");
                            sendTcp("btrange=" + stDistance);
                        },
                        () -> { // CANCEL — reset to 120
                            flagWarningActive[0] = false;
                            stDistance = 120;
                            distBar.setProgress(110); // 120 - 10 = 110
                            distLbl.setText("Distance: (120 m)");
                            sendTcp("btrange=120");
                        }
                    );
                } else if (!flagWarningActive[0]) {
                    stDistance = newVal;
                    distLbl.setText("Distance: (" + stDistance + " m)");
                    sendTcp("btrange=" + stDistance);
                }
            }
            public void onStartTrackingTouch(SeekBar s){}
            public void onStopTrackingTouch(SeekBar s){}
        });
        col.addView(distBar);

        // Ignore Bot / Ignore Knocked buttons
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams brlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        brlp.topMargin = dp(6);
        btnRow.setLayoutParams(brlp);

        TextView btnBot     = makeActionBtn("Ignore Bot",     stIgnBot);
        TextView btnKnocked = makeActionBtn("Ignore Knocked", stIgnKnocked);
        LinearLayout.LayoutParams bl1 = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        bl1.rightMargin = dp(9);
        btnBot.setLayoutParams(bl1);
        btnKnocked.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        btnBot.setOnClickListener(v -> {
            stIgnBot=!stIgnBot; styleActionBtn(btnBot, stIgnBot);
            vibrate(); sendTcp("ignorebot="+(stIgnBot?1:0));
        });
        btnKnocked.setOnClickListener(v -> {
            stIgnKnocked=!stIgnKnocked; styleActionBtn(btnKnocked, stIgnKnocked);
            vibrate(); sendTcp("ignoreknocked="+(stIgnKnocked?1:0));
        });
        btnRow.addView(btnBot); btnRow.addView(btnKnocked);
        col.addView(btnRow);
        contentPanel.addView(col);
    }

    // ── MISC TAB ──────────────────────────────────────────────────────────────
    private void buildMisc() {
        sectionHeader(contentPanel, "STABILITY FEATURES");

        // Row 1: 6H Time Fixer | 120 FPS (equal weight)
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout c1 = newCol(dp(4), dp(5));
        LinearLayout c2 = newCol(dp(5), dp(4));
        checkRow(c1, "6H Time Fixer", stTimeFixer, on->{ stTimeFixer=on; sendTcp("timefixer="+(on?1:0)); });
        checkRow(c2, "120 FPS",       stFps120,    on->{ stFps120=on;    sendTcp("fps120="+(on?1:0)); });
        row1.addView(c1); row1.addView(c2);
        contentPanel.addView(row1);

        // IPad View row: checkbox + "(range)" inline on same row
        final TextView[] ipadRangeTv = {null};

        LinearLayout ipadRow = new LinearLayout(this);
        ipadRow.setOrientation(LinearLayout.HORIZONTAL);
        ipadRow.setGravity(Gravity.CENTER_VERTICAL);
        ipadRow.setPadding(dp(4), dp(4), dp(4), dp(4));
        ipadRow.setClickable(true);
        ipadRow.setFocusable(true);
        ipadRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final TextView ipadBox = new TextView(this);
        ipadBox.setGravity(Gravity.CENTER);
        ipadBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        ipadBox.setClickable(false);
        ipadBox.setFocusable(false);
        applyCheck(ipadBox, stWide);
        LinearLayout.LayoutParams iblp = new LinearLayout.LayoutParams(dp(26), dp(26));
        iblp.rightMargin = dp(7);
        ipadRow.addView(ipadBox, iblp);

        TextView ipadLbl = new TextView(this);
        ipadLbl.setText("IPad View");
        ipadLbl.setTextColor(C_LABEL);
        ipadLbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        ipadLbl.setTypeface(fBody);
        ipadLbl.setClickable(false);
        ipadLbl.setFocusable(false);
        ipadRow.addView(ipadLbl, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        ipadRangeTv[0] = new TextView(this);
        ipadRangeTv[0].setText(" (" + stIpadRange + ")");
        ipadRangeTv[0].setTextColor(Color.parseColor("#d2d2d2"));
        ipadRangeTv[0].setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        ipadRangeTv[0].setTypeface(fBody);
        ipadRangeTv[0].setClickable(false);
        ipadRangeTv[0].setFocusable(false);
        ipadRow.addView(ipadRangeTv[0], new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final boolean[] ipadSt = {stWide};

        SeekBar ipadBar = makeSeekBar(90, 130, stIpadRange);
        ipadBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s, int p, boolean u){
                stIpadRange = p + 90;
                if (ipadRangeTv[0] != null)
                    ipadRangeTv[0].setText(" (" + stIpadRange + ")");
                sendTcp("ipadrange=" + stIpadRange);
            }
            public void onStartTrackingTouch(SeekBar s){}
            public void onStopTrackingTouch(SeekBar s){}
        });
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(dp(140), dp(24));
        sbLp.topMargin = dp(2); sbLp.bottomMargin = dp(4);
        sbLp.leftMargin = dp(33);
        ipadBar.setLayoutParams(sbLp);
        ipadBar.setVisibility(stWide ? View.VISIBLE : View.GONE);

        ipadRow.setOnClickListener(v -> {
            ipadSt[0] = !ipadSt[0];
            stWide = ipadSt[0];
            applyCheck(ipadBox, ipadSt[0]);
            ipadBar.setVisibility(ipadSt[0] ? View.VISIBLE : View.GONE);
            vibrate();
            sendTcp("wideview=" + (ipadSt[0] ? 1 : 0));
        });
        contentPanel.addView(ipadRow);
        contentPanel.addView(ipadBar);

        // Hide Recording — full width row
        LinearLayout hideRow = new LinearLayout(this);
        hideRow.setOrientation(LinearLayout.HORIZONTAL);
        hideRow.setGravity(Gravity.CENTER_VERTICAL);
        hideRow.setPadding(dp(4), dp(4), dp(4), dp(4));
        hideRow.setClickable(true);
        hideRow.setFocusable(true);
        hideRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final TextView hideBox = new TextView(this);
        hideBox.setGravity(Gravity.CENTER);
        hideBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hideBox.setClickable(false);
        hideBox.setFocusable(false);
        applyCheck(hideBox, stHideEsp);
        LinearLayout.LayoutParams hblp = new LinearLayout.LayoutParams(dp(26), dp(26));
        hblp.rightMargin = dp(7);
        hideRow.addView(hideBox, hblp);

        TextView hideLbl = new TextView(this);
        hideLbl.setText("Hide Recording");
        hideLbl.setTextColor(C_LABEL);
        hideLbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hideLbl.setTypeface(fBody);
        hideLbl.setClickable(false);
        hideLbl.setFocusable(false);
        hideRow.addView(hideLbl, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final boolean[] hideSt = {stHideEsp};
        hideRow.setOnClickListener(v -> {
            hideSt[0] = !hideSt[0];
            stHideEsp = hideSt[0];
            applyCheck(hideBox, hideSt[0]);
            vibrate();
            sendTcp("hideesp=" + (hideSt[0] ? 1 : 0));
            updateHideEspWindows(hideSt[0]);
        });
        contentPanel.addView(hideRow);
    }

    // ── DEVICE ID TAB ────────────────────────────────────────────────────────
    private void buildDeviceId() {
        // Title: "DEVICE ID" purple 13sp bold
        TextView titleTv = new TextView(this);
        titleTv.setText("DEVICE ID");
        titleTv.setTextColor(Color.parseColor("#c252ff"));
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        titleTv.setTypeface(fHeading, Typeface.BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.bottomMargin = dp(6);
        titleTv.setLayoutParams(tlp);
        contentPanel.addView(titleTv);

        // Desc text
        TextView descTv = new TextView(this);
        descTv.setText("Change your device ID to avoid 1-day and 12-hour flag bans.");
        descTv.setTextColor(Color.parseColor("#cccccc"));
        descTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        descTv.setTypeface(fBody);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.bottomMargin = dp(10);
        descTv.setLayoutParams(dlp);
        contentPanel.addView(descTv);

        // Status text — shows current ID and optionally new pending ID
        String currentId = "";
        try {
            currentId = android.provider.Settings.Secure.getString(
                getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
        } catch (Exception ignored) {}
        final String curId = currentId.isEmpty() ? "unknown" : currentId;

        final TextView statusTv = new TextView(this);
        statusTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        statusTv.setTypeface(fBody);
        SpannableString initial = buildIdSpan("Game Android ID: ", curId, null, null);
        statusTv.setText(initial);
        LinearLayout.LayoutParams slp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        slp2.bottomMargin = dp(12);
        statusTv.setLayoutParams(slp2);
        contentPanel.addView(statusTv);

        // "Change Device ID" button
        final TextView changeBtn = new TextView(this);
        changeBtn.setText("Change Device ID");
        changeBtn.setTextColor(C_WHITE);
        changeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        changeBtn.setTypeface(fBody, Typeface.BOLD);
        changeBtn.setGravity(Gravity.CENTER);
        changeBtn.setPadding(dp(10), dp(12), dp(10), dp(12));
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setCornerRadius(dp(8));
        btnBg.setColor(Color.parseColor("#9425bd"));
        changeBtn.setBackground(btnBg);
        changeBtn.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        contentPanel.addView(changeBtn);

        // Generate a simple new random ID (hex string)
        final String[] newId = {""};

        changeBtn.setOnClickListener(v -> {
            vibrate();
            newId[0] = Long.toHexString(Double.doubleToLongBits(Math.random())).substring(0, 16);
            SpannableString withNote = buildIdSpanWithNote(
                "Game Android ID: ", curId,
                "New Android ID (pending): ", newId[0]);
            statusTv.setText(withNote);
            sendTcp("changedeviceid=1");
            // Delete bbox fake-device.conf so virtual env picks up fresh ID on restart
            try {
                java.io.File fdc = new java.io.File(getFilesDir(), "system/fake-device.conf");
                if (fdc.exists()) fdc.delete();
            } catch (Throwable ignored) {}
            // Transform button into "Restart Game"
            changeBtn.setText("Restart Game");
            GradientDrawable rBg = new GradientDrawable();
            rBg.setCornerRadius(dp(8));
            rBg.setColor(Color.parseColor("#9425bd"));
            changeBtn.setBackground(rBg);
            changeBtn.setOnClickListener(v2 -> {
                vibrate();
                cleanupOverlay();
                restartApp();
            });
        });
    }

    private SpannableString buildIdSpan(String label1, String id1, String label2, String id2) {
        int tealColor = Color.parseColor("#00bcd4");
        String text = label1 + id1;
        if (label2 != null && id2 != null) {
            text += "\n" + label2 + id2 + "\nRestart the game to apply the change.";
        }
        SpannableString ss = new SpannableString(text);
        ss.setSpan(new ForegroundColorSpan(Color.parseColor("#cccccc")),
                0, label1.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ss.setSpan(new ForegroundColorSpan(tealColor),
                label1.length(), label1.length() + id1.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (label2 != null && id2 != null) {
            int off = label1.length() + id1.length() + 1;
            ss.setSpan(new ForegroundColorSpan(Color.parseColor("#cccccc")),
                    off, off + label2.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            ss.setSpan(new ForegroundColorSpan(tealColor),
                    off + label2.length(), off + label2.length() + id2.length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            int noteOff = off + label2.length() + id2.length() + 1;
            ss.setSpan(new ForegroundColorSpan(Color.parseColor("#aaaaaa")),
                    noteOff, text.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return ss;
    }

    private SpannableString buildIdSpanWithNote(String l1, String id1, String l2, String id2) {
        return buildIdSpan(l1, id1, l2, id2);
    }

    private void restartApp() {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE
                        : PendingIntent.FLAG_CANCEL_CURRENT;
                PendingIntent pi = PendingIntent.getActivity(this, 0, launch, flags);
                AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
                if (am != null) am.set(AlarmManager.RTC, System.currentTimeMillis() + 100, pi);
            }
        } catch (Exception ignored) {}
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    // ── FLAG WARNING DIALOG ───────────────────────────────────────────────────
    private void showFlagWarning(Runnable onConfirm, Runnable onCancel) {
        if (menuView == null) return;
        FrameLayout menuRoot = (FrameLayout) menuView;

        // Scrim layer — semi-dark, covers just the menu area (no new window)
        FrameLayout dialogHolder = new FrameLayout(this);
        View scrim = new View(this);
        scrim.setBackgroundColor(Color.parseColor("#99000000"));
        dialogHolder.addView(scrim, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(20), dp(20), dp(20));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setCornerRadius(dp(14));
        cardBg.setColor(Color.parseColor("#1a1a1a"));
        cardBg.setStroke(dp(2), Color.parseColor("#1565c0"));
        card.setBackground(cardBg);

        TextView msgTv = new TextView(this);
        msgTv.setText("Exceeding limit may Flag your id for 7 Day Ban\nProceed with caution.");
        msgTv.setTextColor(C_WHITE);
        msgTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        msgTv.setGravity(Gravity.CENTER);
        msgTv.setTypeface(fBody);
        card.addView(msgTv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams brlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        brlp.topMargin = dp(18);
        btnRow.setLayoutParams(brlp);

        TextView cancelBtn = new TextView(this);
        cancelBtn.setText("CANCEL");
        cancelBtn.setTextColor(C_WHITE);
        cancelBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        cancelBtn.setTypeface(fHeading, Typeface.BOLD);
        cancelBtn.setGravity(Gravity.CENTER);
        cancelBtn.setPadding(dp(8), dp(12), dp(8), dp(12));
        GradientDrawable cxBg = new GradientDrawable();
        cxBg.setCornerRadius(dp(8));
        cxBg.setColor(Color.parseColor("#3a3a3a"));
        cancelBtn.setBackground(cxBg);

        final TextView confirmBtn = new TextView(this);
        confirmBtn.setText("CONFIRM (10)");
        confirmBtn.setTextColor(C_WHITE);
        confirmBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        confirmBtn.setTypeface(fHeading, Typeface.BOLD);
        confirmBtn.setGravity(Gravity.CENTER);
        confirmBtn.setPadding(dp(8), dp(12), dp(8), dp(12));
        GradientDrawable cfBg = new GradientDrawable();
        cfBg.setCornerRadius(dp(8));
        cfBg.setColor(Color.parseColor("#1565c0"));
        confirmBtn.setBackground(cfBg);

        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        bl.rightMargin = dp(10);
        cancelBtn.setLayoutParams(bl);
        confirmBtn.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        btnRow.addView(cancelBtn);
        btnRow.addView(confirmBtn);
        card.addView(btnRow);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(dp(300),
                FrameLayout.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.CENTER;
        dialogHolder.addView(card, cardLp);

        FrameLayout.LayoutParams holderLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        menuRoot.addView(dialogHolder, holderLp);

        confirmBtn.setClickable(false);
        confirmBtn.setAlpha(0.4f);

        final int[] cd = {10};
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        final Runnable[] ticker = new Runnable[1];
        ticker[0] = () -> {
            if (cd[0] > 0) {
                cd[0]--;
                if (cd[0] > 0) {
                    confirmBtn.setText("CONFIRM (" + cd[0] + ")");
                    h.postDelayed(ticker[0], 1000);
                } else {
                    confirmBtn.setText("CONFIRM");
                    confirmBtn.setClickable(true);
                    confirmBtn.setAlpha(1.0f);
                }
            }
        };
        h.postDelayed(ticker[0], 1000);

        cancelBtn.setOnClickListener(v -> {
            h.removeCallbacks(ticker[0]);
            try { menuRoot.removeView(dialogHolder); } catch (Exception ig) {}
            onCancel.run();
        });
        confirmBtn.setOnClickListener(v -> {
            h.removeCallbacks(ticker[0]);
            try { menuRoot.removeView(dialogHolder); } catch (Exception ig) {}
            onConfirm.run();
        });
    }

    // ── SCREENSHOT PROTECTION ──────────────────────────────────────────────────
    private void updateHideEspWindows(boolean on) {
        // Only ever call SurfaceControl when transitioning TO active (never to false).
        // Calling setSkipScreenshot false → true on the same surface triggers SurfaceFlinger
        // recomposition which the game reads as a display resize → zoom/blink bug.
        // menuSkipActive resets in closeMenu() so reopening the menu gets a fresh surface.
        if (on) {
            if (menuView != null && menuParams != null && !menuSkipActive) {
                applySkipScreenshot(menuView, menuParams, true);
                menuSkipActive = true;
            }
            if (iconRoot != null && iconParams != null && !iconSkipActive) {
                applySkipScreenshot(iconRoot, iconParams, true);
                iconSkipActive = true;
            }
        }
    }

    private void applySkipScreenshot(View v, WindowManager.LayoutParams p, boolean on) {
        if (v == null || p == null) return;
        try {
            Object vri = View.class.getMethod("getViewRootImpl").invoke(v);
            if (vri != null) {
                Object sc = vri.getClass().getMethod("getSurfaceControl").invoke(vri);
                if (sc != null) {
                    Class<?> scClass = Class.forName("android.view.SurfaceControl");
                    Class<?> txClass = Class.forName("android.view.SurfaceControl$Transaction");
                    Object tx = txClass.getConstructor().newInstance();
                    java.lang.reflect.Method setSkip =
                            txClass.getMethod("setSkipScreenshot", scClass, boolean.class);
                    setSkip.invoke(tx, sc, on);
                    txClass.getMethod("apply").invoke(tx);
                }
            }
        } catch (Throwable ignored) {}
    }

    // ── HELPERS ───────────────────────────────────────────────────────────────
    private LinearLayout newCol(int padStart, int padEnd) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(padStart, 0, padEnd, 0);
        col.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return col;
    }

    // ── TCP SENDER ─────────────────────────────────────────────────────────────
    private final java.util.concurrent.LinkedBlockingQueue<String> cmdQueue =
            new java.util.concurrent.LinkedBlockingQueue<>();
    private volatile boolean tcpRunning = true;

    private void startTcpSender() {
        Thread t = new Thread(() -> {
            while (tcpRunning) {
                Socket s = null;
                try {
                    s = new Socket();
                    s.connect(new InetSocketAddress(TCP_HOST, TCP_PORT), 1500);
                    s.setTcpNoDelay(true);
                    s.setKeepAlive(true);
                    OutputStream out = s.getOutputStream();
                    while (tcpRunning) {
                        String cmd = cmdQueue.take();
                        try {
                            out.write((cmd + "\n").getBytes("UTF-8"));
                            out.flush();
                        } catch (Exception writeErr) {
                            cmdQueue.offer(cmd);
                            break;
                        }
                    }
                } catch (Exception connErr) {
                    try { Thread.sleep(500); } catch (Exception ignored) {}
                } finally {
                    if (s != null) try { s.close(); } catch (Exception ig) {}
                }
            }
        }, "ZkTcpSender");
        t.setDaemon(true);
        t.start();
    }

    private void sendTcp(final String cmd) {
        cmdQueue.offer(cmd);
    }

    private void sendInitialState() {
        sendTcp("line="     + (stLine   ? 1 : 0));
        sendTcp("skeleton=" + (stSkel   ? 1 : 0));
        sendTcp("health="   + (stHealth ? 1 : 0));
        sendTcp("box="      + (stBox    ? 1 : 0));
        sendTcp("distance=" + (stDist   ? 1 : 0));
        sendTcp("count="    + (stCount  ? 1 : 0));
        sendTcp("btrack="   + (stBtrack ? 1 : 0));
        sendTcp("btrange="  + stDistance);
    }

    @Override public void onDestroy() {
        super.onDestroy();
        cleanupOverlay();
    }
}
