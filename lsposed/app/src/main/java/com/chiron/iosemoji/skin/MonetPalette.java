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
 *   <li>{@code dynamic}：读 {@code android:color/system_surface_container} 等系统动态色
 *       （Android 12+；HyperOS 提供同一套资源）。首选——系统已按壁纸算好完整 scheme；</li>
 *   <li>{@code wallpaper}：系统动态色缺失时，用 {@link WallpaperManager#getWallpaperColors}
 *       主色当种子，按固定色调表生成（手册允许"系统不可用时用算法自行生成"）；</li>
 *   <li>{@code last}：本进程上次成功的种子；</li>
 *   <li>{@code default}：内置紫色种子，保证首次启动/异常时键盘照常可用。</li>
 * </ol>
 *
 * <p>角色映射按手册 §4 的 Token 表，不再把 neutral 直接当键帽色：
 * keyboardBackground←surfaceContainer、keyBackground←surfaceContainerHigh、
 * functionKey/Pressed←secondaryContainer、actionKey/强调←primary、
 * text←onSurface、functionKeyText←onSecondaryContainer。
 *
 * <p>可读性（手册 §8）：派生完再按 WCAG 收口（正文 4.5:1、图标 3:1），
 * 不达标就推黑/白，不在 UI 层打补丁。
 *
 * <p>本类不做任何 hook、不碰布局；只读系统颜色，任何异常都回退，绝不影响键盘呼出。
 */
public final class MonetPalette {

    public static final String SRC_DYNAMIC = "dynamic";
    public static final String SRC_WALLPAPER = "wallpaper";
    public static final String SRC_LAST = "last";
    public static final String SRC_DEFAULT = "default";

    /** 内置兜底种子（Material 默认紫）。 */
    private static final int DEFAULT_SEED = 0xFF6750A4;

    private static volatile String sSource = SRC_DEFAULT;
    private static volatile int sSeed = DEFAULT_SEED;
    private static volatile int sLastSeed = 0;

    private MonetPalette() {
    }

    public static SkinPreset light(Context ctx) {
        return build(ctx, false);
    }

    public static SkinPreset dark(Context ctx) {
        return build(ctx, true);
    }

    /** 诊断串（进状态卡，判断"到底取没取到系统色"）。 */
    public static String describe() {
        return "src=" + sSource + ",seed=#"
                + String.format(java.util.Locale.US, "%08X", sSeed);
    }

    private static SkinPreset build(Context ctx, boolean dark) {
        // ① 系统动态色（首选）
        int bg = sys(ctx, "system_surface_container");
        int key = sys(ctx, "system_surface_container_high");
        int keyPressed = sys(ctx, "system_secondary_container");
        int func = sys(ctx, "system_secondary_container");
        int onFunc = sys(ctx, "system_on_secondary_container");
        int text = sys(ctx, "system_on_surface");
        int accent = sys(ctx, "system_primary");

        boolean dynamic = bg != 0 && key != 0 && text != 0;
        int seed = seedFromWallpaper(ctx);

        if (dynamic) {
            sSource = SRC_DYNAMIC;
            sSeed = seed != 0 ? seed : DEFAULT_SEED;
        } else {
            // ② 壁纸主色 ③ 上次种子 ④ 内置种子
            if (seed != 0) {
                sSource = SRC_WALLPAPER;
            } else if (sLastSeed != 0) {
                sSource = SRC_LAST;
                seed = sLastSeed;
            } else {
                sSource = SRC_DEFAULT;
                seed = DEFAULT_SEED;
            }
            sSeed = seed;
            // 从种子按固定色调表推一套（不是简单反色，见手册 §5.1）
            float[] hsl = new float[3];
            Color.colorToHSV(seed, hsl);
            float hue = hsl[0];
            float chroma = Math.max(0.18f, Math.min(0.55f, hsl[1])); // 低彩度壁纸也不至于全灰
            bg = tone(hue, chroma * 0.28f, dark ? 0.10f : 0.985f);
            key = tone(hue, chroma * 0.30f, dark ? 0.22f : 1.0f);
            keyPressed = tone(hue, chroma * 0.42f, dark ? 0.32f : 0.90f);
            func = tone(hue, chroma * 0.38f, dark ? 0.26f : 0.93f);
            onFunc = tone(hue, 0.14f, dark ? 0.95f : 0.16f);
            text = tone(hue, 0.08f, dark ? 0.95f : 0.14f);
            accent = tone(hue, chroma, dark ? 0.78f : 0.42f);
        }
        sLastSeed = sSeed;

        if (keyPressed == 0) keyPressed = fix(key, bg, 1.6);
        if (func == 0) func = keyPressed;
        if (onFunc == 0) onFunc = fix(accent, func, 3.0);
        if (accent == 0) accent = keyPressed;

        // ② WCAG 收口（手册 §8.2）：正文 4.5:1，图标/强调 3:1
        int keyText = fix(text, key, 4.5);
        int keyPressedText = fix(text, keyPressed, 4.5);
        int funcText = fix(onFunc, func, 4.5);
        int popupText = fix(text, key, 4.5);
        int accentSafe = fix(accent, bg, 3.0);

        // keyPressedText / funcText / popupText 由 IosSkin.patchIni 的 ensureContrast 再兜一次，
        // 这里先把最贴近的"文字色"给出：键帽用 keyText，功能键用 funcText 的亮度方向。
        int t = (IosSkin.contrast(keyText, key) >= IosSkin.contrast(funcText, func))
                ? keyText : funcText;

        return new SkinPreset(SkinPreset.MONET,
                bg, key, keyPressed, func, tone(func, 0.5f, dark ? 0.38f : 0.86f),
                keyPressed, bg,
                t, keyPressedText, accentSafe,
                0.16f, dark ? 0x40000000 : 0x1A000000, 2f, 1f, false, true);
    }

    // ── 工具 ──────────────────────────────────────────────────────────────

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
            WallpaperColors wc =
                    wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
            if (wc == null || wc.getPrimaryColor() == null) return 0;
            int argb = wc.getPrimaryColor().toArgb();
            return (argb >>> 24) == 0 ? 0 : argb;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** HSV → 不透明 ARGB。 */
    private static int tone(float hueDeg, float sat, float value) {
        float s = Math.max(0f, Math.min(1f, sat));
        float v = Math.max(0f, Math.min(1f, value));
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
