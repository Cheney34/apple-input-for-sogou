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
 *   <li>{@code fixed}：用户自己挑的主色（设置页「莫奈主色」：粉 / 紫 / 蓝 / 绿 / 橙 / 青）。
 *       排在最前——因为不少人觉得自家壁纸的颜色不好看，需要能锁死一个主色；</li>
 *   <li>{@code dynamic}：系统动态色（{@code android:color/system_surface_container} 等，Android 12+）；</li>
 *   <li>{@code wallpaper}：系统动态色缺失时，用 {@link WallpaperManager#getWallpaperColors} 主色当种子；</li>
 *   <li>{@code last}：本进程上次成功的种子；</li>
 *   <li>{@code default}：内置紫色种子。</li>
 * </ol>
 *
 * <p>角色映射按手册 §4 Token 表；可读性按 §8.2 收口（正文 4.5:1、图标 3:1）。
 * 本类不 hook、不碰布局，任何异常都回退，绝不阻塞键盘呼出。
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
        int forced = seedValue(key);            // 用户在设置页锁死的主色

        int bg, kc, kp, func, onFunc, text, accent;

        if (forced != 0) {
            // ① 用户指定的主色：整条色阶都由它派生（不看壁纸，避免"壁纸颜色不舒服"）
            sSource = SRC_FIXED + ":" + key;
            sSeed = forced;
            float[] hsl = new float[3];
            Color.colorToHSV(forced, hsl);
            float hue = hsl[0];
            float chroma = clamp(hsl[1], 0.22f, 0.55f);
            bg = tone(hue, chroma * 0.26f, dark ? 0.10f : 0.985f);
            kc = tone(hue, chroma * 0.30f, dark ? 0.23f : 1.0f);
            kp = tone(hue, chroma * 0.40f, dark ? 0.33f : 0.905f);
            func = tone(hue, chroma * 0.34f, dark ? 0.27f : 0.94f);
            onFunc = tone(hue, 0.12f, dark ? 0.95f : 0.16f);
            text = tone(hue, 0.07f, dark ? 0.95f : 0.14f);
            accent = hueAccent(hue, chroma, dark);
        } else {
            // ② 系统动态色（首选）
            bg = sys(ctx, "system_surface_container");
            kc = sys(ctx, "system_surface_container_high");
            kp = sys(ctx, "system_secondary_container");
            func = sys(ctx, "system_secondary_container");
            onFunc = sys(ctx, "system_on_secondary_container");
            text = sys(ctx, "system_on_surface");
            accent = sys(ctx, "system_primary");

            boolean dynamic = bg != 0 && kc != 0 && text != 0;
            int wall = seedFromWallpaper(ctx);
            if (dynamic) {
                sSource = SRC_DYNAMIC;
                sSeed = wall != 0 ? wall : DEFAULT_SEED;
            } else {
                // ③ 壁纸主色 ④ 上次种子 ⑤ 内置种子
                int seed;
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
                float[] hsl = new float[3];
                Color.colorToHSV(seed, hsl);
                float hue = hsl[0];
                float chroma = clamp(hsl[1], 0.18f, 0.55f);
                bg = tone(hue, chroma * 0.28f, dark ? 0.10f : 0.985f);
                kc = tone(hue, chroma * 0.30f, dark ? 0.22f : 1.0f);
                kp = tone(hue, chroma * 0.42f, dark ? 0.32f : 0.90f);
                func = tone(hue, chroma * 0.38f, dark ? 0.26f : 0.93f);
                onFunc = tone(hue, 0.14f, dark ? 0.95f : 0.16f);
                text = tone(hue, 0.08f, dark ? 0.95f : 0.14f);
                accent = hueAccent(hue, chroma, dark);
            }
        }

        if (kp == 0) kp = fix(kc, bg, 1.6);
        if (func == 0) func = kp;
        if (onFunc == 0) onFunc = fix(accent, func, 3.0);
        if (accent == 0) accent = kp;

        // WCAG 收口（手册 §8.2）：正文 4.5:1、图标/强调 3:1
        int keyText = fix(text, kc, 4.5);
        int funcText = fix(onFunc, func, 4.5);
        int accentSafe = fix(accent, bg, 3.0);
        int t = (IosSkin.contrast(keyText, kc) >= IosSkin.contrast(funcText, func)) ? keyText : funcText;

        return new SkinPreset(SkinPreset.MONET,
                bg, kc, kp, func, tone(func, 0.5f, dark ? 0.38f : 0.86f),
                kp, bg,
                t, keyText, accentSafe,
                0.16f, dark ? 0x40000000 : 0x1A000000, 2f, 1f, false, true);
    }

    // ── 工具 ───────────────────────────────────────────────────────────────

    /** 强调色：浅色压下明度、深色提亮，保证在底色上够跳。 */
    private static int hueAccent(float hue, float chroma, boolean dark) {
        return tone(hue, clamp(chroma, 0.30f, 0.60f), dark ? 0.76f : 0.44f);
    }

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
