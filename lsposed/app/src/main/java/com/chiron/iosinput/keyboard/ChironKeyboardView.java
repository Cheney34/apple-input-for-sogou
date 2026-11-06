package com.chiron.iosinput.keyboard;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.chiron.iosinput.hook.HookRegistry;
import com.chiron.iosinput.hook.TextInjector;

/**
 * MVP 阶段的"占位" iOS 键盘。
 *
 * <p>目的不是好看，而是证明三件事（见 docs/MVP_PROPOSAL.md）：
 * <ol>
 *   <li>z=3 键盘槽可以被外部 View 接管；</li>
 *   <li>接管后仍能驱动搜狗引擎出候选；</li>
 *   <li>候选点选后正常上屏。</li>
 * </ol>
 *
 * <p>高度按搜狗主题配置的比例 {@code W=1, H=0.591667}（见 assets/theme/theme_default/1080/port/py_9.ini）
 * 计算，这样 {@code onComputeInsets} 无需改动也不会遮住输入区。
 * 后续 Phase 3 的完整实现会替换本类，但保持对外接口（{@link #create(Context)}、
 * {@link #KEYBOARD_HEIGHT_RATIO}）不变。
 */
public class ChironKeyboardView extends FrameLayout {

    /** 键盘高度 / 屏幕宽度。来源：py_9.ini 的 [Keyboard] H=0.591667。 */
    public static final float KEYBOARD_HEIGHT_RATIO = 0.591667f;

    private static final String[][] ROWS = {
            {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"},
            {"a", "s", "d", "f", "g", "h", "j", "k", "l"},
            {"z", "x", "c", "v", "b", "n", "m"},
    };

    private final int mKeyHeight;

    public ChironKeyboardView(Context context) {
        super(context);
        mKeyHeight = Math.round(getResources().getDisplayMetrics().widthPixels * KEYBOARD_HEIGHT_RATIO);
        setBackgroundColor(isDark() ? 0xFF1C1C1E : 0xFFD1D3D9);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        int pad = dp(3);
        column.setPadding(pad, pad, pad, pad);
        addView(column, lp);

        for (String[] row : ROWS) {
            column.addView(buildRow(context, row), rowParams(1f));
        }
        column.addView(buildBottomRow(context), rowParams(1.15f));
    }

    private LinearLayout.LayoutParams rowParams(float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, weight);
        p.topMargin = dp(3);
        return p;
    }

    private View buildRow(Context ctx, String[] keys) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        for (String key : keys) {
            row.addView(buildKey(ctx, key, key, 1f));
        }
        return row;
    }

    private View buildBottomRow(Context ctx) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);

        row.addView(buildKey(ctx, "123", "123", 1.4f));
        row.addView(buildKey(ctx, "空格", "", 4.2f));
        row.addView(buildKey(ctx, "⌫", "backspace", 1.4f));
        row.addView(buildKey(ctx, "⏎", "enter", 1.4f));
        return row;
    }

    @SuppressLint("ClickableViewAccessibility")
    private View buildKey(Context ctx, final String label, final String action, float weight) {
        TextView key = new TextView(ctx);
        key.setText(label);
        key.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        key.setTextColor(isDark() ? Color.WHITE : 0xFF101114);
        key.setGravity(Gravity.CENTER);
        key.setBackground(roundRect(isDark() ? 0xFF3A3A3C : Color.WHITE));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, weight);
        lp.leftMargin = dp(3);
        lp.rightMargin = dp(3);
        key.setLayoutParams(lp);
        key.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                onKeyTapped(action);
            }
        });
        return key;
    }

    private void onKeyTapped(String action) {
        try {
            if ("backspace".equals(action)) {
                TextInjector.backspace();
            } else if ("enter".equals(action)) {
                TextInjector.enter();
            } else if (action.length() == 1) {
                TextInjector.type(action);
            } else {
                HookRegistry.log("MVP: key '" + action + "' not implemented yet (Phase 3)");
            }
        } catch (Throwable t) {
            // 任何失败都只记日志：不能让键盘把输入法带崩
            HookRegistry.log("key failed: " + action);
            HookRegistry.log(t);
        }
    }

    private GradientDrawable roundRect(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(6));
        return d;
    }

    private boolean isDark() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int mode = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        }
        return false;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // 忽略父布局给的 -2/-2（setKeyboardView 会强制写成 WRAP_CONTENT），
        // 宽度铺满、高度按搜狗主题比例，保证 insets 不需要额外处理。
        // 注意：必须先 super.onMeasure，再覆盖测量结果，否则会被 FrameLayout 覆盖回去。
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (width <= 0) width = getResources().getDisplayMetrics().widthPixels;
        int childWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY);
        int childHeight = MeasureSpec.makeMeasureSpec(mKeyHeight, MeasureSpec.EXACTLY);
        super.onMeasure(childWidth, childHeight);
        setMeasuredDimension(width, mKeyHeight);
    }
}
