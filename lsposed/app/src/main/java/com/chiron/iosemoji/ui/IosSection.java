package com.chiron.iosemoji.ui;

import android.animation.LayoutTransition;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * 可折叠分组（手风琴）。模块 App 的每一个分列都用它包起来。
 *
 * <p>参考 iOS「设置」的分组视觉 + Future-Keyboard / AndroidLiquidGlass / SogouGlass 的
 * 「液态玻璃」卡片：半透明渐变底 + 极细高光边，叠在页面底色上有种毛玻璃的层次感
 * （纯 GradientDrawable 层叠，不依赖 RenderEffect，任何机型都能画）。
 *
 * <p>交互约定（解决「操作逻辑不够好」的核心）：
 * <ul>
 *   <li>点标题行 = 展开 / 收起，箭头跟着转；</li>
 *   <li>每个分组的展开状态写入 {@code chiron_ui} 偏好，下次进来保持上次的样子；</li>
 *   <li>标题行右侧有一行 <b>实时摘要</b>（如「已连接 · 小米定制版」「iOS · 圆角 自动」），
 *       不用展开就能看出这个分组当前是什么状态；</li>
 *   <li>内容用 {@link LayoutTransition} 过渡，展开/收起不是硬切。</li>
 * </ul>
 */
public final class IosSection {

    private static final String PREFS = "chiron_ui";
    private static final String KEY_PREFIX = "sec_";

    private final Context ctx;
    private final String id;
    private final SharedPreferences prefs;

    private final LinearLayout root;
    private final TextView titleView;
    private final TextView summaryView;
    private final TextView chevronView;
    private final LinearLayout card;

    private boolean expanded;

    public static IosSection create(Context ctx, String id, String titleText, boolean defaultExpanded) {
        return new IosSection(ctx, id, titleText, defaultExpanded);
    }

    private IosSection(Context ctx, String id, String titleText, boolean defaultExpanded) {
        this.ctx = ctx;
        this.id = id;
        this.prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.expanded = prefs.getBoolean(KEY_PREFIX + id, defaultExpanded);

        root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rootLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rootLp.leftMargin = IosUi.dp(ctx, 16);
        rootLp.rightMargin = IosUi.dp(ctx, 16);
        rootLp.topMargin = IosUi.dp(ctx, 6);
        root.setLayoutParams(rootLp);

        LayoutTransition lt = new LayoutTransition();
        lt.setDuration(LayoutTransition.APPEARING, 170);
        lt.setDuration(LayoutTransition.DISAPPEARING, 150);
        lt.setDuration(LayoutTransition.CHANGING, 170);
        root.setLayoutTransition(lt);

        // ── 标题行（可点）──────────────────────────────────────────────
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(IosUi.dp(ctx, 6), IosUi.dp(ctx, 12), IosUi.dp(ctx, 6), IosUi.dp(ctx, 7));
        header.setClickable(true);
        header.setFocusable(true);

        titleView = IosUi.text(ctx, titleText, 15f, IosUi.label(ctx), true);
        header.addView(titleView, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        summaryView = IosUi.text(ctx, "", 12.5f, IosUi.label2(ctx), false);
        summaryView.setMaxLines(1);
        summaryView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        summaryView.setPadding(IosUi.dp(ctx, 8), 0, IosUi.dp(ctx, 4), 0);
        header.addView(summaryView);

        chevronView = IosUi.text(ctx, "›", 20f, IosUi.label2(ctx), false);
        chevronView.setPadding(IosUi.dp(ctx, 2), 0, 0, 0);
        header.addView(chevronView);
        chevronView.setRotation(expanded ? 90f : 0f);
        root.addView(header);

        // ── 内容卡（玻璃底）────────────────────────────────────────────
        card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(IosGlass.cardBg(ctx));
        card.setClipToOutline(true);
        card.setElevation(IosUi.dp(ctx, 0.5f));
        root.addView(card, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        card.setVisibility(expanded ? View.VISIBLE : View.GONE);

        header.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggle();
            }
        });
    }

    public View view() {
        return root;
    }

    /** 往分组里加内容（就是原来的 card.addView）。 */
    public LinearLayout content() {
        return card;
    }

    public LinearLayout card() {
        return card;
    }

    /** 标题行右侧的实时摘要，例如「已连接 · 小米定制版」。 */
    public void setSummary(String text) {
        if (summaryView == null) return;
        summaryView.setText(text == null ? "" : text);
    }

    public String id() {
        return id;
    }

    public boolean isExpanded() {
        return expanded;
    }

    public void setExpanded(boolean value, boolean animate) {
        expanded = value;
        prefs.edit().putBoolean(KEY_PREFIX + id, value).apply();
        if (animate) {
            chevronView.animate().rotation(value ? 90f : 0f).setDuration(150).start();
        } else {
            chevronView.setRotation(value ? 90f : 0f);
        }
        card.setVisibility(value ? View.VISIBLE : View.GONE);
    }

    public void toggle() {
        setExpanded(!expanded, true);
    }

    public void expand() {
        setExpanded(true, true);
    }

    public void collapse() {
        setExpanded(false, true);
    }

    /** 供顶部「全部展开 / 全部收起」调用。 */
    public static void setAll(List<IosSection> sections, boolean expand, boolean animate) {
        if (sections == null) return;
        for (int i = 0; i < sections.size(); i++) {
            IosSection s = sections.get(i);
            if (s != null) s.setExpanded(expand, animate);
        }
    }

    /** 玻璃卡片底：半透明渐变 + 一圈发丝高光。 */
    static final class IosGlass {
        private IosGlass() {
        }

        static LayerDrawable cardBg(Context ctx) {
            boolean dark = IosUi.isDark(ctx);
            int top = dark ? 0x2EFFFFFF : 0xF2FFFFFF;
            int bottom = dark ? 0x14FFFFFF : 0xD6FFFFFF;

            GradientDrawable fill = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
            fill.setShape(GradientDrawable.RECTANGLE);
            fill.setCornerRadius(IosUi.dp(ctx, 16));
            fill.setGradientType(GradientDrawable.LINEAR_GRADIENT);

            GradientDrawable stroke = new GradientDrawable();
            stroke.setShape(GradientDrawable.RECTANGLE);
            stroke.setCornerRadius(IosUi.dp(ctx, 16));
            stroke.setColor(0x00000000);
            stroke.setStroke(Math.max(1, IosUi.dp(ctx, 0.6f)),
                    dark ? 0x33FFFFFF : 0x40FFFFFF);

            LayerDrawable ld = new LayerDrawable(new android.graphics.drawable.Drawable[]{fill, stroke});
            return ld;
        }
    }
}
