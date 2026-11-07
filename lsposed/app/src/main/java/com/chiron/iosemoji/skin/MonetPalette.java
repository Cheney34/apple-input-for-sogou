package com.chiron.iosemoji.skin;

import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.os.Build;

/**
 * 莫奈（Monet）动态取色引擎 —— 按《Keyboard Monet 动态取色系统开发手册》分层：
 * 取色 → 角色映射 → Token（{@link SkinPreset}）→ 渲染（IosSkin / 搜狗皮肤层）。
 *
 * <p>取色优先级（手册 §2.3）：
 * <ol>
 *   <li>{@code fixed}：用户自己挑的主色（设置页「莫奈主色」：粉 / 紫 / 蓝 / 绿 / 橙 / 青）；</li>
 *   <li>{@code dynamic}：系统动态色（{@code android:color/system_surface_container} 等，Android 12+）；</li>
 *   <li>{@code wallpaper}：系统动态色缺失时用壁纸主色当种子；</li>
 *   <li>{@code last}：本进程上次成功的种子；</li>
 *   <li>{@code default}：内置紫色种子。</li>
 * </ol>
 *
 * <p><b>配色原则（v2.0.3-beta16 修正）</b>：底色与键帽必须是<strong>近中性</strong>的，
 * 只带一点点主色倾向（Material You 里 {@code surfaceContainer} 的色度非常低），
 * 彩色交给强调色。上一版把“主色饱和度 × 0.26”直接当底色饱和度，
 * 结果整块键盘被染成一块色布，反而不好看。
 */
public final class MonetPalette {

    public static final String SRC_FIXED = "fixed";
    public static final String SRC_DYNAMIC = "dynamic";
    public static final String SRC_WALLPAPER = "wallpaper";
    public static final String SRC_LAST = "last";
    public static final String SRC_DEFAULT = "default";

    /** 可选主色 key（第一个是"自动"）。 */
    public static final String[] SEED_IDS = {"auto", "pink", "purple", "blue", "green", "orange", "teal"};
    public static final String DEFAULT_SEED_KEY = "auto";

    private static final int[] SEED_VALUES = {
            0,                 // auto
            0xFFF06292,        // pink   樱花粉
            0xFF9575CD,        // purple 薰衣草紫
            0xFF64B5F6,        // blue   天空蓝
            0xFF66BB6A,        // green  抹茶绿
            0xFFFFA726,        // orange 蜜橘橙
            0xFF26A69A         // teal   湖青
    };

    private static final String[] SEED_LABELS = {
            "自动（跟随系统 / 壁纸）", "樱花粉", "薰衣草紫", "天空蓝", "抹茶绿", "蜜橘橙", "湖青"
    };

    /** 内置兜底种子（Material 默认紫）。 */
    private static final int DEFAULT_SEED = 0xFF6750A4;

    // 中性底色的色度上限：再高就开始"上色布"了（见类注释）。
    private static final float BG_CHROMA_MAX = 0.10f;
    private static final float KEY_CHROMA_MAX = 0.07f;
    private static final float FUNC_CHROMA_MAX = 0.12f;

    private static volatile String sSource = SRC_DEFAULT;
    private static volatile int sSeed = DEFAULT_SEED;

    private MonetPalette() {
    }

    // ── 主色档位 ───────────────────────────────────────────────────────────

    public static boolean isKnownSeedKey(String key) {
        return indexOfSeed(key) >= 0;
    }

    /** key → 固定 ARGB；{@code auto} 或未知返回 0。 */
    public static int seedValue(String key) {
        int i = indexOfSeed(key);
        return i < 0 ? 0 : SEED_VALUES[i];
    }

    public static String seedLabel(String key) {
        int i = indexOfSeed(key);
        return i < 0 ? SEED_LABELS[0] : SEED_LABELS[i];
    }

    public static String[] seedLabels() {
        return SEED_LABELS.clone();
    }

    private static int indexOfSeed(String key) {
        if (key == null) return -1;
        for (int i = 0; i < SEED_IDS.length; i++) {
            if (SEED_IDS[i].equalsIgnoreCase(key.trim())) return i;
        }
        return -1;
    }

    /** 当前配置的主色 key（非法值回落 auto）。 */
    public static String configuredSeedKey() {
        try {
            String k = com.chiron.iosemoji.config.ModuleConfig.monetSeed();
            return isKnownSeedKey(k) ? k : DEFAULT_SEED_KEY;
        } catch (Throwable t) {
            return DEFAULT_SEED_KEY;
        }
    }

    // ── 对外 ───────────────────────────────────────────────────────────────

    public static SkinPreset light(Context ctx) {
        return build(ctx, false);
    }

    public static SkinPreset dark(Context ctx) {
        return build(ctx, true);
    }

    /** 诊断串（进状态卡，判断"到底用了哪一路颜色"）。 */
    public static String describe() {
        return "src=" + sSource + ",seed=#"
                + String.format(java.util.Locale.US, "%08X", sSeed);
    }

    // ── 主流程 ─────────────────────────────────────────────────────────────

    private static SkinPreset build(Context ctx, boolean dark) {
        String key = configuredSeedKey();
        int forced = seedValue(key);

        int seed;
        float hue;
        float chroma;

        if (forced != 0) {
            // ① 用户锁死的主色：完全不看壁纸
            sSource = SRC_FIXED + ":" + key;
            sSeed = forced;
            seed = forced;
        } else {
            // ② 系统动态色
            int bg = sys(ctx, "system_surface_container");
            int kc = sys(ctx, "system_surface_container_high");
            int text = sys(ctx, "system_on_surface");
            int accent = sys(ctx, "system_primary");
            int wall = seedFromWallpaper(ctx);
            boolean dynamic = bg != 0 && kc != 0 && text != 0;

            if (dynamic) {
                sSource = SRC_DYNAMIC;
                sSeed = accent != 0 ? accent : (wall != 0 ? wall : DEFAULT_SEED);
                // 系统已给完整 scheme：底色/键帽直接用系统值（本身就很中性），只补功能键与文字
                int kp = sys(ctx, "system_secondary_container");
                int func = kp != 0 ? kp : kc;
                int onFunc = sys(ctx, "system_on_secondary_container");
                int t = text != 0 ? text : 0xFF1C1B1F;
                int acc = accent != 0 ? fix(accent, bg, 3.0) : fix(kc, bg, 3.0);
                return new SkinPreset(SkinPreset.MONET,
                        bg, kc, kp != 0 ? kp : kc, func,
                        tone(0f, 0f, dark ? 0.32f : 0.88f),
                        kp != 0 ? kp : kc, bg,
                        t, t, acc,
                        0.16f, dark ? 0x40000000 : 0x1A000000, 2f, 1f, false, true);
            }

            // ③ 壁纸主色 ④ 上次种子 ⑤ 内置种子
            if (wall != 0) {
                sSource = SRC_WALLPAPER;
                seed = wall;
            } else if (sSeed != 0 && !SRC_DEFAULT.equals(sSource)) {
                sSource = SRC_LAST;
                seed = sSeed;
            } else {
                sSource = SRC_DEFAULT;
                seed = DEFAULT_SEED;
            }
            sSeed = seed;
        }

        float[] hsl = new float[3];
        Color.colorToHSV(seed, hsl);
        hue = hsl[0];
        // 主色只决定"倾向"，不决定"染色强度"（见类注释）
        chroma = clamp(hsl[1], 0.25f, 0.75f);

        int[] c = derive(hue, chroma, dark);
        int bg = c[0], kc2 = c[1], kp = c[2], func = c[3], onFunc = c[4], text = c[5], accent = c[6];

        // WCAG 收口（手册 §8.2）：正文 4.5:1、图标/强调 3:1
        int keyText = fix(text, kc2, 4.5);
        int funcText = fix(onFunc, func, 4.5);
        int accentSafe = fix(accent, bg, 3.0);
        int t = (IosSkin.contrast(keyText, kc2) >= IosSkin.contrast(funcText, func)) ? keyText : funcText;

        return new SkinPreset(SkinPreset.MONET,
                bg, kc2, kp, func, tone(hue, FUNC_CHROMA_MAX * 0.7f, dark ? 0.34f : 0.87f),
                kp, bg,
                t, keyText, accentSafe,
                0.16f, dark ? 0x40000000 : 0x1A000000, 2f, 1f, false, true);
    }

    /**
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
    }

    // ── 工具 ───────────────────────────────────────────────────────────────

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** 读系统动态色；不存在或异常返回 0。 */
    private static int sys(Context ctx, String name) {
        if (Build.VERSION.SDK_INT < 31) return 0;
        try {
            Resources res = (ctx != null && ctx.getResources() != null)
                    ? ctx.getResources() : Resources.getSystem();
            int id = res.getIdentifier(name, "color", "android");
            if (id == 0) return 0;
            return res.getColor(id, null);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 壁纸主色（API 27+）；拿不到返回 0，绝不抛出。 */
    private static int seedFromWallpaper(Context ctx) {
        if (ctx == null || Build.VERSION.SDK_INT < 27) return 0;
        try {
            WallpaperManager wm = WallpaperManager.getInstance(ctx);
            if (wm == null) return 0;
            WallpaperColors wc = wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
            if (wc == null || wc.getPrimaryColor() == null) return 0;
            int argb = wc.getPrimaryColor().toArgb();
            return (argb >>> 24) == 0 ? 0 : argb;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** HSV → 不透明 ARGB。 */
    private static int tone(float hueDeg, float sat, float value) {
        float s = clamp(sat, 0f, 1f);
        float v = clamp(value, 0f, 1f);
        return Color.HSVToColor(new float[]{hueDeg, s, v}) | 0xFF000000;
    }

    /** 把 fg 推到与 bg 至少 min:1（先推黑，再推白）。 */
    private static int fix(int fg, int bg, double min) {
        if (IosSkin.contrast(fg, bg) >= min) return fg;
        int[] tries = {
                SkinPreset.blend(fg, 0xFF000000, 0.35f), SkinPreset.blend(fg, 0xFF000000, 0.7f), 0xFF000000,
                SkinPreset.blend(fg, 0xFFFFFFFF, 0.35f), SkinPreset.blend(fg, 0xFFFFFFFF, 0.7f), 0xFFFFFFFF
        };
        int best = fg;
        double bestC = IosSkin.contrast(fg, bg);
        for (int c : tries) {
            double v = IosSkin.contrast(c, bg);
            if (v >= min) return c;
            if (v > bestC) {
                bestC = v;
                best = c;
            }
        }
        return best;
    }
}
