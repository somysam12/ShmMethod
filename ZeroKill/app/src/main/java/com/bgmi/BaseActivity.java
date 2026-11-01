package com.bgmi;

import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatButton;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;


@SuppressWarnings({"unused","deprecation"})
public class BaseActivity extends AppCompatActivity {

    private BottomSheetDialog sheet;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    protected void showSheet(String title, String msg,
                              String okLabel, Runnable onOk,
                              String cancelLabel, Runnable onCancel) {
        dismissSheet();
        sheet = new BottomSheetDialog(this);
        sheet.setCancelable(false);
        sheet.setContentView(R.layout.bottom_sheet_dialog_layout);
        if (sheet.getWindow() != null)
            sheet.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        TextView _t = sheet.findViewById(R.id.title);
        if (_t != null) { _t.setText(title); _t.setTextColor(0xFFFFC021); }
        TextView _m = sheet.findViewById(R.id.msg);
        if (_m != null) { _m.setText(msg); _m.setTextColor(0xFFD4A840); }
        AppCompatButton ok = sheet.findViewById(R.id.btn);
        ok.setText(okLabel);
        ok.setOnClickListener(v -> onOk.run());
        AppCompatButton cancel = sheet.findViewById(R.id.btn_cancle);
        cancel.setText(cancelLabel);
        cancel.setOnClickListener(v -> onCancel.run());
        sheet.show();
    }

    protected void showUpdateSheet(String title, String msg,
                                    String updateLabel, Runnable onUpdate,
                                    String laterLabel, Runnable onLater) {
        // Legacy - redirect to mandatory
        showMandatoryUpdateSheet(title, msg, updateLabel, onUpdate);
    }

    protected void showMandatoryUpdateSheet(String title, String msg,
                                             String updateLabel, Runnable onUpdate) {
        dismissSheet();
        sheet = new BottomSheetDialog(this);
        sheet.setCancelable(false); // Cannot dismiss by back/outside tap
        sheet.setContentView(R.layout.bottom_update);

        TextView tvTitle = sheet.findViewById(R.id.title);
        TextView tvMsg   = sheet.findViewById(R.id.msg);
        if (tvTitle != null) tvTitle.setText(title);
        if (tvMsg   != null) tvMsg.setText(msg);

        // Set GameZone badge as icon
        android.widget.ImageView icon = sheet.findViewById(R.id.dialog_icon);
        if (icon != null) {
            icon.setImageResource(R.drawable.zk_logo);
            icon.setBackgroundResource(0);
        }

        // "Update Now" button
        MaterialButton updateBtn = sheet.findViewById(R.id.updateee);
        if (updateBtn != null) {
            updateBtn.setText(updateLabel);
            updateBtn.setOnClickListener(v -> onUpdate.run());
        }

        // Hide "Later" button entirely
        AppCompatButton laterBtn = sheet.findViewById(R.id.btn_cancle);
        if (laterBtn != null) laterBtn.setVisibility(View.GONE);

        // "OK/Skip" button = CRASH (force update)
        AppCompatButton skipBtn = sheet.findViewById(R.id.btn);
        if (skipBtn != null) {
            skipBtn.setText("OK");
            skipBtn.setVisibility(View.VISIBLE);
            skipBtn.setOnClickListener(v -> {
                // Mandatory update — crash app so user is forced to update
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(0);
            });
        }

        sheet.show();
    }

    protected void dismissSheet() {
        try {
            if (sheet != null && sheet.isShowing()) {
                sheet.dismiss();
                sheet = null;
            }
        } catch (Exception ignored) {}
    }
}
