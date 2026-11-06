package com.chiron.iosemoji.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 模块界面的 iOS 视觉基础件（纯原生 View，不依赖 AppCompat/Material）。
 *
 * <p>只做三件事：取色（跟随深色模式）、造圆角背景、造文字。
 * 所有界面都用代码搭，不用 XML —— 少一层资源依赖，也方便单独调间距。
 */
public final class IosUi {

    public static final int BLUE = 0xFF007AFF;
    public static final int GREEN = 0xFF34C759;
    public static final int RED = 0xFFFF3B30;
    public static final int ORANGE = 0xFFFF9500;

    private IosUi() {
    }

    public static boolean isDark(Context ctx) {
        int mode = ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    /** 页面底色（iOS 分组列表底）。 */
    public static int bg(Context ctx) {
        return isDark(ctx) ? 0xFF000000 : 0xFFF2F2F7;
    }

    /** 卡片底色。 */
    public static int cardColor(Context ctx) {
        return isDark(ctx) ? 0xFF1C1C1E : 0xFFFFFFFF;
    }

    /** 分割线。 */
    public static int separator(Context ctx) {
        return isDark(ctx) ? 0x2E545458 : 0x2E3C3C43;
    }

    /** 主文字。 */
    public static int label(Context ctx) {
        return isDark(ctx) ? 0xFFFFFFFF : 0xFF000000;
    }

    /** 次要文字 / 分组标题。 */
    public static int label2(Context ctx) {
        return isDark(ctx) ? 0xFF98989F : 0xFF6C6C70;
    }

    public static int dp(Context ctx, float value) {
        return Math.round(value * ctx.getResources().getDisplayMetrics().density);
    }

    public static GradientDrawable rounded(Context ctx, int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(dp(ctx, radiusDp));
        return d;
    }

    public static GradientDrawable cardBg(Context ctx) {
        return rounded(ctx, cardColor(ctx), 12f);
    }

    public static GradientDrawable filledButton(Context ctx, int color) {
        return rounded(ctx, color, 12f);
    }

    public static TextView text(Context ctx, String value, float sizeSp, int color, boolean bold) {
        TextView tv = new TextView(ctx);
        tv.setText(value);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    /** 页面大标题（iOS 的 large title）。 */
    public static TextView largeTitle(Context ctx, String value) {
        TextView tv = text(ctx, value, 30f, label(ctx), true);
        tv.setPadding(0, 0, 0, 0);
        return tv;
    }

    /** 分组标题：小号、灰色、带左右缩进。 */
    public static TextView sectionHeader(Context ctx, String value) {
        TextView tv = text(ctx, value, 13f, label2(ctx), false);
        tv.setPadding(dp(ctx, 28), dp(ctx, 18), dp(ctx, 28), dp(ctx, 6));
        return tv;
    }

    /** 分组脚注。 */
    public static TextView footer(Context ctx, String value) {
        TextView tv = text(ctx, value, 13f, label2(ctx), false);
        tv.setPadding(dp(ctx, 28), dp(ctx, 7), dp(ctx, 28), 0);
        tv.setLineSpacing(dp(ctx, 2), 1f);
        return tv;
    }

    /** 卡片容器：圆角 + 白底 + 内部竖向排列。 */
    public static LinearLayout card(Context ctx) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(cardBg(ctx));
        return box;
    }

    /** 卡片内的分割线（左侧留出图标宽度，和 iOS 一致）。 */
    public static View separator(Context ctx, int insetLeftDp) {
        View v = new View(ctx);
        v.setBackgroundColor(separator(ctx));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(ctx, 0.5f)));
        lp.leftMargin = dp(ctx, insetLeftDp);
        v.setLayoutParams(lp);
        return v;
    }

    public static View space(Context ctx, float heightDp) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, heightDp)));
        return v;
    }

    /** 卡片左右外边距。 */
    public static LinearLayout.LayoutParams cardParams(Context ctx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(ctx, 20);
        lp.rightMargin = dp(ctx, 20);
        return lp;
    }

    /** 状态胶囊：一个圆点 + 一段文字。 */
    public static LinearLayout pill(Context ctx) {
        LinearLayout pill = new LinearLayout(ctx);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(ctx, 10), dp(ctx, 5), dp(ctx, 12), dp(ctx, 5));
        GradientDrawable d = rounded(ctx, isDark(ctx) ? 0xFF1C1C1E : 0xFFFFFFFF, 20f);
        pill.setBackground(d);
        return pill;
    }

    public static View dot(Context ctx, int color) {
        View dot = new View(ctx);
        int size = dp(ctx, 8);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.rightMargin = dp(ctx, 6);
        dot.setLayoutParams(lp);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        dot.setBackground(d);
        return dot;
    }

    public static int alpha(int color, float factor) {
        int a = Math.round(Color.alpha(color) * factor);
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color));
    }
}
