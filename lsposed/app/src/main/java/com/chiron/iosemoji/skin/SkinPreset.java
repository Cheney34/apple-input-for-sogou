package com.chiron.iosemoji.skin;

import android.content.Context;
import android.content.res.Resources;
import android.os.Build;

/**
 * 皮肤预设 —— 一套配色 + 一点"画法"参数。
 *
 * <p>四个预设：
 * <ul>
 *   <li>{@code ios}：iOS 系统键盘（灰底 + 纯白键帽 + 功能键更灰）；</li>
 *   <li>{@code wechat}：微信风（浅灰底 + 白键帽 + 微信绿强调色，功能键很淡）；</li>
 *   <li>{@code google}：Gboard / Material 3（白底 + 浅灰悬浮键帽 + Google 蓝）；</li>
 *   <li>{@code monet}：莫奈取色（Android 12+ 从系统动态配色里取 neutral/accent 色阶，
 *       取不到就退回一组 Material You 兜底色）。</li>
 * </ul>
 *
 * <p>本类只做「取色」，不做任何 hook，也不碰布局。
 */
public final class SkinPreset {

    public static final String IOS = "ios";
    public static final String WECHAT = "wechat";
    public static final String GOOGLE = "google";
    public static final String MONET = "monet";

    /** 顺序即设置页里的显示顺序。 */
    public static final String[] IDS = {IOS, WECHAT, GOOGLE, MONET};

    public final String id;

    // 底色与键帽
    public final int kbBg;          // 键盘/候选栏底色
    public final int key;           // 字母键
    public final int keyPressed;
    public final int func;          // 功能键（123 / ⌫ / ⏎）
    public final int funcPressed;
    public final int popup;         // 长按气泡底

    // 文字与强调色
    public final int text;
    public final int textPressed;
    public final int accent;        // 强调色（选中候选、候选高亮）

    // 画法参数
    public final float radiusRatio; // 圆角 = 键高 × 该值
    public final int shadowColor;   // 键帽投影色（0 = 不画投影）
    public final float shadowRadius;
    public final float shadowDy;
    public final boolean edgeLine;  // 是否再画一条底边分隔（iOS 有）

    private SkinPreset(String id, int kbBg, int key, int keyPressed, int func, int funcPressed,
                       int popup, int text, int textPressed, int accent,
                       float radiusRatio, int shadowColor, float shadowRadius, float shadowDy,
                       boolean edgeLine) {
        this.id = id;
        this.kbBg = kbBg;
        this.key = key;
        this.keyPressed = keyPressed;
        this.func = func;
        this.funcPressed = funcPressed;
        this.popup = popup;
        this.text = text;
        this.textPressed = textPressed;
        this.accent = accent;
        this.radiusRatio = radiusRatio;
        this.shadowColor = shadowColor;
        this.shadowRadius = shadowRadius;
        this.shadowDy = shadowDy;
        this.edgeLine = edgeLine;
    }

    public static boolean isKnown(String id) {
        if (id == null) return false;
        for (String s : IDS) if (s.equals(id)) return true;
        return false;
    }

    public static String labelOf(String id) {
        if (WECHAT.equals(id)) return "微信";
        if (GOOGLE.equals(id)) return "Google";
        if (MONET.equals(id)) return "莫奈取色";
        return "iOS";
    }

    /**
     * 取一套配色。
     *
     * @param dark 是否深色（搜狗皮肤目录 /black/ 表示深色）
     * @param ctx  仅 {@code monet} 需要（读系统动态配色），可为 null
     */
    public static SkinPreset of(String id, boolean dark, Context ctx) {
        if (WECHAT.equals(id)) return dark ? wechatDark() : wechatLight();
        if (GOOGLE.equals(id)) return dark ? googleDark() : googleLight();
        if (MONET.equals(id)) return dark ? monetDark(ctx) : monetLight(ctx);
        return dark ? iosDark() : iosLight();
    }

    // ── iOS ───────────────────────────────────────────────────────────────
    // 三层对比：底色偏灰、键帽纯白、功能键明显更灰；键帽圆角 0.12、带一道淡底边
    private static SkinPreset iosLight() {
        return new SkinPreset(IOS,
                0xFFD1D4D9, 0xFFFFFFFF, 0xFFE5E5EA, 0xFFADB3BC, 0xFF9FA5AE,
                0xFFFFFFFF, 0xFF000000, 0xFF000000, 0xFF007AFF,
                0.12f, 0x1A000000, 2f, 1f, true);
    }

    private static SkinPreset iosDark() {
        return new SkinPreset(IOS,
                0xFF1C1C1E, 0xFF636366, 0xFF7C7C80, 0xFF4A4A4D, 0xFF5C5C60,
                0xFF636366, 0xFFFFFFFF, 0xFFFFFFFF, 0xFF0A84FF,
                0.12f, 0x33000000, 2f, 1f, true);
    }

    // ── 微信 ──────────────────────────────────────────────────────────────
    // 微信的观感是「很淡」：底几乎白、功能键比字母键只深一点点、强调色是品牌绿
    private static SkinPreset wechatLight() {
        return new SkinPreset(WECHAT,
                0xFFF2F2F2, 0xFFFFFFFF, 0xFFE6E6E6, 0xFFE9E9E9, 0xFFDCDCDC,
                0xFFFFFFFF, 0xFF191919, 0xFF191919, 0xFF07C160,
                0.10f, 0x12000000, 1.5f, 1f, true);
    }

    private static SkinPreset wechatDark() {
        return new SkinPreset(WECHAT,
                0xFF1E1E1E, 0xFF2C2C2C, 0xFF3A3A3A, 0xFF343434, 0xFF424242,
                0xFF2C2C2C, 0xFFEDEDED, 0xFFEDEDED, 0xFF07C160,
                0.10f, 0x33000000, 1.5f, 1f, true);
    }

    // ── Google（Gboard / Material 3）──────────────────────────────────────
    // 白底 + 浅灰键帽（键帽比底色深一档，跟 iOS 正好相反），强调色 Google 蓝
    private static SkinPreset googleLight() {
        return new SkinPreset(GOOGLE,
                0xFFFFFFFF, 0xFFF1F3F4, 0xFFE3E5E8, 0xFFE8EAED, 0xFFDADCE0,
                0xFFFFFFFF, 0xFF202124, 0xFF202124, 0xFF1A73E8,
                0.14f, 0x0F000000, 2f, 1f, false);
    }

    private static SkinPreset googleDark() {
        return new SkinPreset(GOOGLE,
                0xFF202124, 0xFF3C4043, 0xFF4A4D51, 0xFF303134, 0xFF3C4043,
                0xFF3C4043, 0xFFE8EAED, 0xFFE8EAED, 0xFF8AB4F8,
                0.14f, 0x33000000, 2f, 1f, false);
    }

    // ── 莫奈取色（Android 12+ 动态配色）───────────────────────────────────
    // 取系统 neutral1/neutral2 做底与键帽、accent1 做强调色；取不到就用 Material You 兜底
    private static SkinPreset monetLight(Context ctx) {
        int bg = systemColor(ctx, "system_neutral1_100", 0xFFFDFCFF);
        int key = systemColor(ctx, "system_neutral1_50", 0xFFFFFFFF);
        int keyPressed = systemColor(ctx, "system_neutral1_200", 0xFFE6E1E9);
        int func = systemColor(ctx, "system_neutral2_200", 0xFFE8E0EC);
        int funcPressed = systemColor(ctx, "system_neutral2_300", 0xFFD9D2DE);
        int text = systemColor(ctx, "system_neutral1_900", 0xFF1C1B1F);
        int accent = systemColor(ctx, "system_accent1_500", 0xFF6750A4);
        return new SkinPreset(MONET, bg, key, keyPressed, func, funcPressed,
                key, text, text, accent,
                0.16f, 0x12000000, 2f, 1f, false);
    }

    private static SkinPreset monetDark(Context ctx) {
        int bg = systemColor(ctx, "system_neutral1_900", 0xFF1C1B1F);
        int key = systemColor(ctx, "system_neutral1_800", 0xFF2B2830);
        int keyPressed = systemColor(ctx, "system_neutral1_700", 0xFF3A3742);
        int func = systemColor(ctx, "system_neutral2_800", 0xFF2F2A33);
        int funcPressed = systemColor(ctx, "system_neutral2_700", 0xFF3D3742);
        int text = systemColor(ctx, "system_neutral1_100", 0xFFE6E1E9);
        int accent = systemColor(ctx, "system_accent1_200", 0xFFCFBCFF);
        return new SkinPreset(MONET, bg, key, keyPressed, func, funcPressed,
                key, text, text, accent,
                0.16f, 0x33000000, 2f, 1f, false);
    }

    /** 读 framework 的动态配色资源（`android:color/system_accent1_500` 这类）。 */
    private static int systemColor(Context ctx, String name, int fallback) {
        if (Build.VERSION.SDK_INT < 31) return fallback;
        try {
            Resources res = ctx != null && ctx.getResources() != null
                    ? ctx.getResources() : Resources.getSystem();
            int id = res.getIdentifier(name, "color", "android");
            if (id == 0) return fallback;
            return res.getColor(id, null);
        } catch (Throwable t) {
            return fallback;
        }
    }
}
