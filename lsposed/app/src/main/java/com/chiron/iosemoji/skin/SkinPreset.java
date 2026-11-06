package com.chiron.iosemoji.skin;

import android.content.Context;
import android.content.res.Resources;
import android.os.Build;

/**
 * 皮肤预设 —— 一套配色 + 一点"画法"参数。
 *
 * <p>预设（顺序见 {@link #IDS}）：
 * <ul>
 *   <li>{@code ios}：iOS 系统键盘（灰底 + 纯白键帽 + 功能键明显更灰 + 键帽底边分隔线）；</li>
 *   <li>{@code wechat}：微信输入法观感（米灰底 #EBEBEB + 纯白键帽 + 极淡功能键 + 微信绿）；
 *       键帽是**平的**：投影只有一点点，没有 iOS 那道底边线。</li>
 *   <li>{@code google}：Gboard / Material 3（浅灰底 #F1F3F4 + 白键帽浮在上面 + Google 蓝）；</li>
 *   <li>{@code monet}：莫奈取色（Android 12+ 系统动态配色）。不只是把 neutral 拿来用，
 *       而是把 accent1 的色相**掺进**底色与键帽，并给整块底色加一层极轻的竖向渐变——
 *       这就是"氛围感"的来源：整块键盘像从系统壁纸里长出来的，而不是一块灰板。</li>
 * </ul>
 *
 * <p>本类只做「取色」，不做任何 hook，也不碰布局。
 */
public final class SkinPreset {

    public static final String IOS = "ios";
    public static final String WECHAT = "wechat";
    public static final String GOOGLE = "google";
    public static final String MONET = "monet";
    /** 幽墨（fcitx5-android Material 预设，青绿 accent #5CB5AB）。 */
    public static final String MATERIAL = "material";
    /** 幽墨 Pixel 预设（Google 蓝 #4285F4）。 */
    public static final String PIXEL = "pixel";
    /** 樱花粉（#FB7299，参考 WeType UI Enhanced 的示例主题色）。 */
    public static final String SAKURA = "sakura";

    /** 顺序即设置页里的显示顺序。 */
    public static final String[] IDS = {IOS, WECHAT, GOOGLE, MATERIAL, PIXEL, SAKURA, MONET};

    public final String id;

    // 底色与键帽
    public final int kbBg;          // 键盘 / 候选栏 / 顶部工具条底色（统一用这一个）
    public final int key;           // 字母键
    public final int keyPressed;
    public final int func;          // 功能键（123 / ⌫ / ⏎）
    public final int funcPressed;
    public final int popup;         // 长按气泡底
    public final int barBg;         // 候选栏 / 顶部工具条底色（可与键盘底色不同）

    // 文字与强调色
    public final int text;
    public final int textPressed;
    public final int accent;        // 强调色（选中候选、候选高亮、首候选）

    // 画法参数
    public final float radiusRatio; // 圆角 = 键高 × 该值
    public final int shadowColor;   // 键帽投影色（0 = 不画投影）
    public final float shadowRadius;
    public final float shadowDy;
    public final boolean edgeLine;  // 是否再画一条底边分隔（iOS 有，其余没有）
    public final boolean gradient;  // 底色是否画极轻的竖向渐变（莫奈用，营造氛围感）

    private SkinPreset(String id, int kbBg, int key, int keyPressed, int func, int funcPressed,
                       int popup, int barBg, int text, int textPressed, int accent,
                       float radiusRatio, int shadowColor, float shadowRadius, float shadowDy,
                       boolean edgeLine, boolean gradient) {
        this.id = id;
        this.kbBg = kbBg;
        this.key = key;
        this.keyPressed = keyPressed;
        this.func = func;
        this.funcPressed = funcPressed;
        this.popup = popup;
        this.barBg = barBg;
        this.text = text;
        this.textPressed = textPressed;
        this.accent = accent;
        this.radiusRatio = radiusRatio;
        this.shadowColor = shadowColor;
        this.shadowRadius = shadowRadius;
        this.shadowDy = shadowDy;
        this.edgeLine = edgeLine;
        this.gradient = gradient;
    }

    public static boolean isKnown(String id) {
        if (id == null) return false;
        for (String s : IDS) if (s.equals(id)) return true;
        return false;
    }

    public static String labelOf(String id) {
        if (WECHAT.equals(id)) return "微信";
        if (MATERIAL.equals(id)) return "幽墨 Material";
        if (PIXEL.equals(id)) return "幽墨 Pixel";
        if (SAKURA.equals(id)) return "樱花粉";
        if (GOOGLE.equals(id)) return "Google";
        if (MONET.equals(id)) return "莫奈取色";
        return "iOS";
    }

    /**
     * 取一套配色。
     *
     * @param dark 是否深色（搜狗皮肤目录 /black/ 表示深色，判断不出来就跟系统夜间模式）
     * @param ctx  仅 {@code monet} 需要（读系统动态配色），可为 null
     */
    public static SkinPreset of(String id, boolean dark, Context ctx) {
        if (WECHAT.equals(id)) return dark ? wechatDark() : wechatLight();
        if (MATERIAL.equals(id)) return dark ? materialDark() : materialLight();
        if (PIXEL.equals(id)) return dark ? pixelDark() : pixelLight();
        if (SAKURA.equals(id)) return dark ? sakuraDark() : sakuraLight();
        if (GOOGLE.equals(id)) return dark ? googleDark() : googleLight();
        if (MONET.equals(id)) return dark ? monetDark(ctx) : monetLight(ctx);
        return dark ? iosDark() : iosLight();
    }

    // ── iOS ───────────────────────────────────────────────────────────────
    // 三层对比：底色偏灰、键帽纯白、功能键明显更灰；键帽圆角 0.12、带一道淡底边
    private static SkinPreset iosLight() {
        return new SkinPreset(IOS,
                0xFFD1D4D9, 0xFFFFFFFF, 0xFFE5E5EA, 0xFFADB3BC, 0xFF9FA5AE,
                0xFFFFFFFF, 0xFFD1D4D9, 0xFF000000, 0xFF000000, 0xFF007AFF,
                0.12f, 0x1A000000, 2f, 1f, true, false);
    }

    private static SkinPreset iosDark() {
        // alpha10：底色压到近黑（#0F0F10），键帽提到 #6E6E73 —— 原来 #1C1C1E / #636366
        // 在深色屏幕上对比度只有 ≈3.2:1，键帽边界几乎看不出来。
        return new SkinPreset(IOS,
                0xFF0F0F10, 0xFF6E6E73, 0xFF87878C, 0xFF55555A, 0xFF6A6A6F,
                0xFF6E6E73, 0xFF0F0F10, 0xFFFFFFFF, 0xFFFFFFFF, 0xFF3F9BFF,
                0.12f, 0x40000000, 2f, 1f, true, false);
    }

    // ── 微信 ──────────────────────────────────────────────────────────────
    // 微信自己那套键盘的观感要点：
    //   1. 底色是**米灰**（#EBEBEB 一带），不是纯白，也不是 iOS 那种偏蓝的灰；
    //   2. 键帽纯白、**平的**——投影极淡，没有 iOS 的底边线；
    //   3. 功能键（123 / ⌫ / ⏎）只是比底色深一丁点，几乎看不出来；
    //   4. 强调色是品牌绿 #07C160，只出现在"发送/回车"和候选高亮上。
    private static SkinPreset wechatLight() {
        return new SkinPreset(WECHAT,
                0xFFEDEDED, 0xFFFFFFFF, 0xFFDCDCDC, 0xFFEDEDED, 0xFFDCDCDC,
                0xFFFFFFFF, 0xFFFFFFFF, 0xFF191919, 0xFF191919, 0xFF07C160,
                0.16f, 0x12000000, 2f, 0.5f, false, false);
    }

    private static SkinPreset wechatDark() {
        // 微信夜间是"黑灰"而不是"蓝黑"：底 #1A1A1A、键 #303030、功能键只比底深一点。
        return new SkinPreset(WECHAT,
                0xFF111111, 0xFF303030, 0xFF3C3C3C, 0xFF111111, 0xFF2A2A2A,
                0xFF303030, 0xFF232323, 0xFFE6E6E6, 0xFFE6E6E6, 0xFF07C160,
                0.16f, 0x33000000, 2f, 0.5f, false, false);
    }

    // ── Google（Gboard / Material 3）──────────────────────────────────────
    // Gboard 的关系和 iOS 相反：**底色是浅灰、键帽是白的**，键帽浮在底上（靠投影分开）；
    // 功能键几乎和底色同色，只靠图标区分；强调色 Google 蓝 #1A73E8。
    private static SkinPreset googleLight() {
        return new SkinPreset(GOOGLE,
                0xFFF1F3F4, 0xFFFFFFFF, 0xFFE3E5E8, 0xFFE8EAED, 0xFFDADCE0,
                0xFFFFFFFF, 0xFFFFFFFF, 0xFF202124, 0xFF202124, 0xFF1A73E8,
                0.14f, 0x1F000000, 2.5f, 1.5f, false, false);
    }

    private static SkinPreset googleDark() {
        // Gboard 夜间：底 #1F2023（比 Material 的 #202124 略深，避免和键帽糊在一起）、
        // 键帽 #3C4043、功能键压到 #2D2F31。
        return new SkinPreset(GOOGLE,
                0xFF1F2023, 0xFF3C4043, 0xFF4A4D51, 0xFF2D2F31, 0xFF3A3D40,
                0xFF3C4043, 0xFF2A2C2F, 0xFFE8EAED, 0xFFE8EAED, 0xFF8AB4F8,
                0.14f, 0x40000000, 2.5f, 1.5f, false, false);
    }

    // ── 莫奈取色（Android 12+ 动态配色）───────────────────────────────────
    // 只把 neutral 拿来用会"很灰、很平"（用户说的"不够氛围感"）。
    // 这里的做法：底色 = neutral1_100 与 accent1_50 混 45%，键帽 = neutral1_50 与 accent1_100 混 18%，
    // 功能键 = neutral2_200 与 accent1_100 混 30%，再叠一层竖向渐变 —— 整块键盘带上系统强调色的色相。
    private static SkinPreset monetLight(Context ctx) {
        int n50 = systemColor(ctx, "system_neutral1_50", 0xFFFFFBFF);
        int n100 = systemColor(ctx, "system_neutral1_100", 0xFFEADDFF);
        int n200 = systemColor(ctx, "system_neutral1_200", 0xFFD0BCFF);
        int n2_200 = systemColor(ctx, "system_neutral2_200", 0xFFE8DEF8);
        int a1_50 = systemColor(ctx, "system_accent1_50", 0xFFFFF7FF);
        int a1_100 = systemColor(ctx, "system_accent1_100", 0xFFEADDFF);
        int a1_200 = systemColor(ctx, "system_accent1_200", 0xFFD0BCFF);
        int text = systemColor(ctx, "system_neutral1_900", 0xFF1C1B1F);
        int accent = systemColor(ctx, "system_accent1_500", 0xFF6750A4);

        int bg = blend(n100, a1_50, 0.45f);
        int key = separate(bg, blend(n50, a1_100, 0.18f), 1.60);
        int keyPressed = blend(n200, a1_200, 0.35f);
        int func = separate(bg, blend(n2_200, a1_100, 0.30f), 1.35);
        int funcPressed = blend(n2_200, a1_200, 0.45f);

        return new SkinPreset(MONET, bg, key, keyPressed, func, funcPressed,
                key, blend(n100, a1_50, 0.62f), text, text, accent,
                0.16f, 0x1A000000, 2f, 1f, false, true);
    }

    private static SkinPreset monetDark(Context ctx) {
        int n900 = systemColor(ctx, "system_neutral1_900", 0xFF141218);
        int n800 = systemColor(ctx, "system_neutral1_800", 0xFF1D1B20);
        int n700 = systemColor(ctx, "system_neutral1_700", 0xFF2B2930);
        int n2_800 = systemColor(ctx, "system_neutral2_800", 0xFF1D1B20);
        int a1_900 = systemColor(ctx, "system_accent1_900", 0xFF21005D);
        int a1_800 = systemColor(ctx, "system_accent1_800", 0xFF4F378B);
        int text = systemColor(ctx, "system_neutral1_100", 0xFFE6E1E9);
        int accent = systemColor(ctx, "system_accent1_200", 0xFFCFBCFF);

        int bg = blend(n900, a1_900, 0.45f);
        int key = separate(bg, blend(n800, a1_800, 0.28f), 1.60);
        int keyPressed = blend(n700, a1_800, 0.35f);
        int func = separate(bg, blend(n700, a1_900, 0.35f), 1.30);
        int funcPressed = blend(n800, a1_800, 0.45f);

        return new SkinPreset(MONET, bg, key, keyPressed, func, funcPressed,
                key, blend(n800, a1_800, 0.42f), text, text, accent,
                0.16f, 0x40000000, 2f, 1f, false, true);
    }

    // ── 幽墨 Material（取自 Sraw/youmo-ime ThemePreset.MaterialLight / MaterialDark）──
    // 灰蓝底 + 近白键帽 + 青绿 accent，是 fcitx5-android 的默认观感。
    private static SkinPreset materialLight() {
        return new SkinPreset(MATERIAL,
                0xFFECEFF1, 0xFFFBFBFC, 0xFFEDEFF0, 0xFFDFE2E4, 0xFFD3D7D9,
                0xFFD9DBDD, 0xFFE4E7E9, 0xFF37474F, 0xFF37474F, 0xFF5CB5AB,
                0.12f, 0x1F000000, 2f, 1f, false, false);
    }

    private static SkinPreset materialDark() {
        return new SkinPreset(MATERIAL,
                0xFF263238, 0xFF404A50, 0xFF4A555C, 0xFF313C42, 0xFF3A464C,
                0xFF3C474C, 0xFF21272B, 0xFFD9DBDC, 0xFFD9DBDC, 0xFF6EACA8,
                0.12f, 0x33000000, 2f, 1f, false, false);
    }

    // ── 幽墨 Pixel（ThemePreset.PixelLight / PixelDark）────────────────────
    // 纯白键帽 + 浅灰底，键帽边界几乎只能靠投影辨认，Google 蓝 accent。
    private static SkinPreset pixelLight() {
        return new SkinPreset(PIXEL,
                0xFFEEEEEE, 0xFFFFFFFF, 0xFFF2F2F2, 0xFFE1E1E1, 0xFFD5D5D5,
                0xFFEEEEEE, 0xFFEEEEEE, 0xFF212121, 0xFF212121, 0xFF4285F4,
                0.14f, 0x1F000000, 2.5f, 1.5f, false, false);
    }

    private static SkinPreset pixelDark() {
        return new SkinPreset(PIXEL,
                0xFF2D2D2D, 0xFF464646, 0xFF505050, 0xFF373737, 0xFF414141,
                0xFF3A3A3A, 0xFF373737, 0xFFFAFAFA, 0xFFFAFAFA, 0xFF5E97F6,
                0.14f, 0x33000000, 2.5f, 1.5f, false, false);
    }

    // ── 樱花粉（accent #FB7299，参考 NEORUAA/WeType_UI_Enhanced 的示例主题色）──
    private static SkinPreset sakuraLight() {
        return new SkinPreset(SAKURA,
                0xFFFFF1F5, 0xFFFFFFFF, 0xFFFDE7EF, 0xFFFFE0EA, 0xFFFFD0DF,
                0xFFFFFFFF, 0xFFFFFFFF, 0xFF241A1E, 0xFF241A1E, 0xFFFB7299,
                0.13f, 0x1A000000, 2f, 1f, false, false);
    }

    private static SkinPreset sakuraDark() {
        return new SkinPreset(SAKURA,
                0xFF241A1E, 0xFF3A2C33, 0xFF46353D, 0xFF2E2228, 0xFF3A2C33,
                0xFF3A2C33, 0xFF2E2228, 0xFFF7EDF1, 0xFFF7EDF1, 0xFFFB7299,
                0.13f, 0x40000000, 2f, 1f, false, false);
    }
    // ── 工具 ──────────────────────────────────────────────────────────────

    /** 两色按比例混合（{@code t=0} 取 a，{@code t=1} 取 b），保留 a 的 alpha。 */
    public static int blend(int a, int b, float t) {
        float k = Math.max(0f, Math.min(1f, t));
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bgc = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = Math.round(ar + (br - ar) * k);
        int g = Math.round(ag + (bgc - ag) * k);
        int bl = Math.round(ab + (bb - ab) * k);
        return ((a >>> 24) << 24) | (r << 16) | (g << 8) | bl;
    }

    /**
     * 保证 {@code fg} 与 {@code bg} 至少 min:1 的对比度（莫奈取色混色后可能贴太近）。
     * 依次尝试：原色 → 往白推 → 往黑推，取第一个达标的；都不达标就取对比度最大的。
     */
    private static int separate(int bg, int fg, double min) {
        if (IosSkin.contrast(fg, bg) >= min) return fg;
        int[] tries = {
                blend(fg, 0xFFFFFFFF, 0.35f), blend(fg, 0xFFFFFFFF, 0.7f), 0xFFFFFFFF,
                blend(fg, 0xFF000000, 0.35f), blend(fg, 0xFF000000, 0.7f), 0xFF000000
        };
        int best = fg;
        double bestC = IosSkin.contrast(fg, bg);
        for (int c : tries) {
            double v = IosSkin.contrast(c, bg);
            if (v >= min) return c;
            if (v > bestC) { bestC = v; best = c; }
        }
        return best;
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
