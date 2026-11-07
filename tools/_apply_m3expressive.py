# -*- coding: utf-8 -*-
"""把莫奈取色从 beta16 的「近中性」升级为 Material 3 Expressive（2025）色阶：
更饱和的容器色 + 更大的明度分层 + 键帽/底色最低对比度保底。"""
import io
import os

P = r"C:\Users\KOUFIK\Documents\deepseek-harness\default-workspace\apple-input-for-sogou\lsposed\app\src\main\java\com\chiron\iosemoji\skin\MonetPalette.java"
t = io.open(P, "r", encoding="utf-8", newline="").read()
nl = "\r\n" if "\r\n" in t else "\n"


def sub(old, new, tag):
    global t
    o = old.replace("\n", nl)
    n = new.replace("\n", nl)
    c = t.count(o)
    if c != 1:
        raise SystemExit("MISS %s (count=%d)" % (tag, c))
    t = t.replace(o, n)
    print("ok", tag)


# 1) 类注释：说明改成 M3 Expressive
sub(''' * <p><b>配色原则（v2.0.3-beta16 修正）</b>：底色与键帽必须是<strong>近中性</strong>的，
 * 只带一点点主色倾向（Material You 里 {@code surfaceContainer} 的色度非常低），
 * 彩色交给强调色。上一版把“主色饱和度 × 0.26”直接当底色饱和度，
 * 结果整块键盘被染成一块色布，反而不好看。
 */''',
''' * <p><b>配色原则（v2.0.3-beta18 起按 Material 3 Expressive / 2025 色阶）</b>：
 * <ul>
 *   <li>容器色（{@code surfaceContainer*} / {@code secondaryContainer}）保留<strong>明显色度</strong>，
 *       不再压到"近中性" —— beta16 压得太狠，用户反馈饱和度过低、对比度不够；</li>
 *   <li>层次靠<strong>明度分层</strong>：键帽取 {@code surfaceContainerLowest}（浅色近白 / 深色高一档），
 *       键盘底取 {@code surfaceContainer}，功能键取 {@code secondaryContainer}，
 *       并强制「键帽 vs 底色」亮度比 ≥ 1.28（浅色）/ 1.55（深色）；</li>
 *   <li>强调色取 {@code primary}，文字取 {@code onSurface} / {@code onSecondaryContainer}，
 *       最后统一走 WCAG 收口（正文 4.5:1、图标 3:1）。</li>
 * </ul>
 */''', "javadoc")

# 2) 常量：换成 Expressive 的色度比例 + 对比度保底
sub('''    // 中性底色的色度上限：再高就开始"上色布"了（见类注释）。
    private static final float BG_CHROMA_MAX = 0.10f;
    private static final float KEY_CHROMA_MAX = 0.07f;
    private static final float FUNC_CHROMA_MAX = 0.12f;''',
'''    // M3 Expressive（2025）色度档：容器色保留可见色度，键帽近中性，功能键最饱和。
    private static final float BG_CHROMA_RATIO = 0.42f;   // surfaceContainer：主色色度 × 0.42
    private static final float BG_CHROMA_MAX = 0.16f;
    private static final float KEY_CHROMA_RATIO = 0.10f;  // surfaceContainerLowest：几乎不着色
    private static final float KEY_CHROMA_MAX = 0.05f;
    private static final float FUNC_CHROMA_RATIO = 0.85f; // secondaryContainer：最饱和
    private static final float FUNC_CHROMA_MAX = 0.30f;

    /** 键帽 vs 键盘底的最低亮度比：Expressive 靠明度分层，不靠描边。 */
    private static final double KEY_MIN_LIGHT = 1.28;
    private static final double KEY_MIN_DARK = 1.55;''', "constants")

# 3) 动态色分支：优先用 lowest/highest 拉开层次，达不到保底就退回自派生
sub('''            // ② 系统动态色
            int bg = sys(ctx, "system_surface_container");
            int kc = sys(ctx, "system_surface_container_high");
            int text = sys(ctx, "system_on_surface");
            int accent = sys(ctx, "system_primary");
            int wall = seedFromWallpaper(ctx);
            boolean dynamic = bg != 0 && kc != 0 && text != 0;''',
'''            // ② 系统动态色（M3 Expressive：surfaceContainerLowest 当键帽、surfaceContainer 当底）
            int bg = sys(ctx, "system_surface_container");
            int kcHigh = sys(ctx, "system_surface_container_highest");
            int kcLowest = sys(ctx, "system_surface_container_lowest");
            int kc = kcLowest != 0 ? kcLowest : sys(ctx, "system_surface_container_low");
            if (kc == 0) kc = kcHigh;
            int text = sys(ctx, "system_on_surface");
            int accent = sys(ctx, "system_primary");
            int wall = seedFromWallpaper(ctx);
            // 系统给的底色/键帽如果拉不开（浅色系统里两者常常同色），就别用它 ——
            // 那正是用户说的"对比度太低"。这时退回自派生色阶（下面 derive）。
            boolean dynamic = bg != 0 && kc != 0 && text != 0
                    && IosSkin.contrast(kc, bg) >= (dark ? KEY_MIN_DARK * 0.85 : KEY_MIN_LIGHT * 0.85);''', "dynamic-branch")

# 4) funcPressed 一行也用 Expressive 色阶
sub('''                bg, kc2, kp, func, tone(hue, FUNC_CHROMA_MAX * 0.7f, dark ? 0.34f : 0.87f),''',
'''                bg, kc2, kp, func,
                tone(hue, Math.min(chroma * FUNC_CHROMA_RATIO * 0.85f, FUNC_CHROMA_MAX),
                        dark ? 0.36f : 0.845f),''', "funcPressed")

# 5) derive()：Expressive 色阶 + 对比度保底
sub('''    /**
     * 由主色派生一套「近中性」的键盘色阶（Material You 风格）：
     * 底色是极低色度的浅/深灰 + 一点点主色倾向，键帽再亮/暗一档，
     * 功能键比键帽更退一步，强调色保持饱和。
     *
     * @return {bg, key, keyPressed, func, onFunc, text, accent}
     */
    private static int[] derive(float hue, float chroma, boolean dark) {
        int bg = tone(hue, Math.min(chroma * 0.06f, BG_CHROMA_MAX * 0.55f), dark ? 0.105f : 0.895f);
        int key = tone(hue, Math.min(chroma * 0.04f, KEY_CHROMA_MAX * 0.5f), dark ? 0.300f : 0.995f);
        int keyPressed = tone(hue, Math.min(chroma * 0.07f, KEY_CHROMA_MAX), dark ? 0.375f : 0.845f);
        int func = tone(hue, Math.min(chroma * 0.08f, FUNC_CHROMA_MAX), dark ? 0.245f : 0.865f);
        int onFunc = tone(hue, 0.05f, dark ? 0.93f : 0.24f);
        int text = tone(hue, 0.04f, dark ? 0.96f : 0.13f);
        int accent = tone(hue, clamp(chroma, 0.45f, 0.72f), dark ? 0.74f : 0.46f);
        return new int[]{bg, key, keyPressed, func, onFunc, text, accent};
    }''',
'''    /**
     * 由主色派生一套 Material 3 Expressive（2025）键盘色阶。
     *
     * <p>角色对照：底 = {@code surfaceContainer}、键帽 = {@code surfaceContainerLowest}、
     * 按下 = {@code surfaceContainerHigh}、功能键 = {@code secondaryContainer}、
     * 功能键文字 = {@code onSecondaryContainer}、正文 = {@code onSurface}、强调 = {@code primary}。
     *
     * <p>与 beta16 的区别：色度不再压到"看不见"（容器色取主色色度的 42%，功能键 85%），
     * 层次由**明度阶梯**拉开（浅色键帽 1.00 / 底 0.888；深色键帽 0.265 / 底 0.135），
     * 并做「键帽 vs 底」亮度保底 —— 达不到就继续把底压暗，而不是加描边。
     *
     * @return {bg, key, keyPressed, func, onFunc, text, accent}
     */
    private static int[] derive(float hue, float chroma, boolean dark) {
        float bgC = Math.min(chroma * BG_CHROMA_RATIO, BG_CHROMA_MAX);
        float keyC = Math.min(chroma * KEY_CHROMA_RATIO, KEY_CHROMA_MAX);
        float funcC = Math.min(chroma * FUNC_CHROMA_RATIO, FUNC_CHROMA_MAX);

        float bgV = dark ? 0.135f : 0.888f;
        int bg = tone(hue, bgC, bgV);
        int key = tone(hue, keyC, dark ? 0.265f : 1.000f);
        int keyPressed = tone(hue, Math.min(bgC * 1.6f, 0.24f), dark ? 0.335f : 0.828f);
        int func = tone(hue, funcC, dark ? 0.300f : 0.880f);
        int onFunc = tone(hue, Math.min(funcC * 0.45f, 0.14f), dark ? 0.94f : 0.16f);
        int text = tone(hue, Math.min(bgC * 0.55f, 0.09f), dark ? 0.95f : 0.12f);
        int accent = tone(hue, clamp(chroma, 0.58f, 0.86f), dark ? 0.76f : 0.44f);

        // 明度分层保底：浅色往下压底、深色也往下压底（键帽在更亮的一侧），
        // 直到「键帽 vs 底」达到 Expressive 的最低亮度比。
        double min = dark ? KEY_MIN_DARK : KEY_MIN_LIGHT;
        for (int i = 0; i < 20 && IosSkin.contrast(key, bg) < min; i++) {
            bgV -= dark ? 0.010f : 0.016f;
            if (bgV < 0.02f) break;
            bg = tone(hue, bgC, bgV);
        }
        return new int[]{bg, key, keyPressed, func, onFunc, text, accent};
    }''', "derive")

io.open(P, "w", encoding="utf-8", newline="").write(t)
print("WROTE MonetPalette.java")
