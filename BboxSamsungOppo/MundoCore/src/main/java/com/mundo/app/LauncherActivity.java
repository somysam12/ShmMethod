package com.mundo.app;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;

import android.webkit.WebView;
import androidx.annotation.Nullable;

import com.mundo.MundoCore;
import com.mundo.R;
import com.mundo.utils.Slog;
import android.util.Log;
import android.widget.ImageView;
import android.widget.TextView;
import android.view.animation.OvershootInterpolator;
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
        super.onCreate(savedInstanceState);

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

        setContentView(R.layout.activity_launcher);

        // ===== Loading WebView (Lottie) =====
        WebView web = findViewById(R.id.web_loading);
        web.getSettings().setJavaScriptEnabled(true);
        web.setBackgroundColor(Color.TRANSPARENT);
        String html =
                "<!DOCTYPE html><html><head><meta charset='utf-8'/>" +
                "<style>" +
                "body{margin:0;background:transparent;overflow:hidden;}" +
                ".bar{width:100%;height:6px;background:rgba(255,255,255,0.15);" +
                "border-radius:6px;position:relative;}" +
                ".bar:before{content:'';position:absolute;left:-40%;width:40%;" +
                "height:100%;background:linear-gradient(90deg,transparent,#00E5FF,#00FF9C,transparent);" +
                "animation:slide 1.2s infinite ease-in-out;border-radius:6px;}" +
                "@keyframes slide{0%{left:-40%;}100%{left:100%;}}" +
                "</style></head><body>" +
                "<div class='bar'></div>" +
                "</body></html>";

        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);

        // ===== App Icon =====
        Drawable icon = packageInfo.applicationInfo.loadIcon(MundoCore.getPackageManager());

        ImageView iconView = findViewById(R.id.iv_icon);
        iconView.setImageDrawable(icon);

        // ===== App Name =====
        TextView nameView = findViewById(R.id.tv_app_name);
        if (nameView != null) {
            CharSequence label = packageInfo.applicationInfo.loadLabel(MundoCore.getPackageManager());
            nameView.setText(label);
            nameView.setAlpha(0f);
            nameView.animate().alpha(1f).setDuration(400).start();
        }
        // ===== Icon Animation =====
        iconView.setScaleX(0.7f);
        iconView.setScaleY(0.7f);
        iconView.setAlpha(0f);
        iconView.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(450).setInterpolator(new OvershootInterpolator()).start();

        // ===== Launch App =====
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
            finish();
        }
    }
}
