package com.chiron.iosemoji.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * iOS 分组列表里的一行。
 *
 * <p>三种形态：纯文字行（带 ›）、开关行、按钮行。
 * 行高 44dp，左右 16dp 内边距，和 iOS 设置页一致。
 */
public final class IosRow {

    private IosRow() {
    }

    public static LinearLayout clickable(Context ctx, String title, String subtitle,
                                         View.OnClickListener onClick) {
        LinearLayout row = base(ctx);
        LinearLayout texts = textColumn(ctx, title, subtitle);
        row.addView(texts, weight());
        row.addView(chevron(ctx));
        if (onClick != null) {
            row.setOnClickListener(onClick);
            row.setClickable(true);
        }
        return row;
    }

    public static LinearLayout switchRow(Context ctx, String title, String subtitle,
                                         boolean checked, IosSwitch.OnChangeListener listener) {
        LinearLayout row = base(ctx);
        LinearLayout texts = textColumn(ctx, title, subtitle);
        row.addView(texts, weight());
        IosSwitch sw = new IosSwitch(ctx);
        sw.setChecked(checked);
        sw.setOnChangeListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = IosUi.dp(ctx, 8);
        row.addView(sw, lp);
        // 点整行也能切换，符合 iOS 习惯
        row.setOnClickListener(v -> sw.performClick());
        return row;
    }

    public static LinearLayout value(Context ctx, String title, String value) {
        LinearLayout row = base(ctx);
        row.addView(textColumn(ctx, title, null), weight());
        TextView tv = IosUi.text(ctx, value, 15f, IosUi.label2(ctx), false);
        tv.setSingleLine(true);
        row.addView(tv);
        return row;
    }

    public static LinearLayout button(Context ctx, String title, int color,
                                      View.OnClickListener onClick) {
        LinearLayout row = base(ctx);
        row.setGravity(Gravity.CENTER);
        TextView tv = IosUi.text(ctx, title, 17f, color, false);
        tv.setGravity(Gravity.CENTER);
        row.addView(tv);
        if (onClick != null) {
            row.setOnClickListener(onClick);
            row.setClickable(true);
        }
        return row;
    }

    public static LinearLayout base(Context ctx) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padH = IosUi.dp(ctx, 16);
        int padV = IosUi.dp(ctx, 11);
        row.setPadding(padH, padV, padH, padV);
        row.setMinimumHeight(IosUi.dp(ctx, 44));
        row.setBackgroundColor(0x00000000);
        return row;
    }

    public static LinearLayout textColumn(Context ctx, String title, String subtitle) {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = IosUi.text(ctx, title, 17f, IosUi.label(ctx), false);
        col.addView(t);
        if (subtitle != null && subtitle.length() > 0) {
            TextView s = IosUi.text(ctx, subtitle, 13f, IosUi.label2(ctx), false);
            s.setPadding(0, IosUi.dp(ctx, 2), 0, 0);
            col.addView(s);
        }
        return col;
    }

    public static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    public static TextView chevron(Context ctx) {
        TextView tv = IosUi.text(ctx, "›", 20f, IosUi.label2(ctx), false);
        tv.setPadding(IosUi.dp(ctx, 6), 0, 0, 0);
        return tv;
    }
}
