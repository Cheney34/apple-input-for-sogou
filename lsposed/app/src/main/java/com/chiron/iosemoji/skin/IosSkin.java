package com.chiron.iosemoji.skin;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;

import java.util.Map;

/**
 * iOS 外观映射表 —— **纯数据 + 纯算法**，不做任何 hook。
 *
 * <p>为什么这样做（而不是继续注入自绘键盘）：
 * 搜狗键盘的布局、命中区、九宫格比例全部由它自己的皮肤 ini 驱动（{@code py_9.ini} /
 * {@code cands.ini} / {@code images_config.ini} / {@code image_list.ini}），
 * 我们自己在外面盖一层 View 会把它算好的 insets、按键命中、悬浮态全部打乱 ——
 * 这正是"键盘样式卡 bug"的根因。所以改成：**只改它皮肤的颜色与图片**，
 * 布局一个像素都不动。
 *
 * <p>本类提供两类改动，都被 {@link com.chiron.iosemoji.hook.ThemeSkinHook} 调用：
 * <ol>
 *   <li>{@link #patchIni(String, Map)}：解析出来的 ini 段落（键值对）就地改值；</li>
 *   <li>{@link #recolor(String, Bitmap)}：把皮肤里的位图按 iOS 配色重上色
 *       （**保持原始尺寸与 alpha 形状**，所以九宫格拉伸参数不受影响）。</li>
 * </ol>
 *
 * <p>一切改动都可回退：调用方先判断开关，关掉即返回 false / null，搜狗保持原样。
 */
public final class IosSkin {

    // ── iOS 系统键盘配色（浅色 / 深色）────────────────────────────────────
    public static final int LIGHT_KB_BG = 0xFFD1D4D9;   // 键盘底色
    public static final int LIGHT_KEY = 0xFFFFFFFF;      // 字母键
    public static final int LIGHT_KEY_PRESSED = 0xFFE5E5EA;
    public static final int LIGHT_FUNC = 0xFFADB3BC;     // 功能键（123 / ⌫ / ⏎）
    public static final int LIGHT_FUNC_PRESSED = 0xFF9EA4AD;
    public static final int LIGHT_POPUP = 0xFFFFFFFF;

    public static final int DARK_KB_BG = 0xFF28282B;
    public static final int DARK_KEY = 0xFF636366;
    public static final int DARK_KEY_PRESSED = 0xFF7C7C80;
    public static final int DARK_FUNC = 0xFF4A4A4D;
    public static final int DARK_FUNC_PRESSED = 0xFF5C5C60;
    public static final int DARK_POPUP = 0xFF4A4A4D;

    private IosSkin() {
    }

    /** 该 ini 路径是否属于「深色皮肤」目录（搜狗用 black/ 表示深色，white/ 表示浅色）。 */
    public static boolean isDarkPath(String path) {
        return path != null && path.contains("/black/");
    }

    // ── 1. ini 段落改值 ───────────────────────────────────────────────────

    /**
     * 就地把搜狗皮肤 ini 里的颜色改成 iOS 配色。
     *
     * <p>只改「文字/强调色」这类语义明确的键，**不动任何 W / H / PADDINGS / ROWS**：
     * 尺寸一动就会连锁影响 insets 与命中区，那正是我们要避免的。
     *
     * @param path  ini 的绝对/资产路径（用来判断深浅色与文件类型）
     * @param sections {@code 段名 -> (键 -> 值)}，来自 {@code com.sogou.theme.parse.entity.b#b()}
     * @return 是否有改动（用于日志/统计）
     */
    public static boolean patchIni(String path, Map<String, Map<String, String>> sections) {
        if (sections == null || sections.isEmpty()) return false;
        boolean dark = isDarkPath(path);
        boolean changed = false;

        // 强调色：搜狗默认橙（FF6933）→ iOS 蓝（007AFF）；文字色统一到 iOS 的近黑/近白
        Map<String, String> def = sections.get("DEFAULT_TEXT_COLORS");
        if (def != null) {
            changed |= put(def, "COLOR_FIRST_DEFAULT_THEME", dark ? "0A84FF" : "007AFF");
            changed |= put(def, "COLOR_FUNCTION_THEME", dark ? "FFFFFF" : "000000");
            changed |= put(def, "COLOR_FUNCTION_PRESSED_THEME", dark ? "FFFFFF" : "000000");
            changed |= put(def, "COLOR_LATIN_THEME", dark ? "FFFFFF" : "000000");
            changed |= put(def, "COLOR_PRESSED_THEME", dark ? "FFFFFF" : "000000");
            changed |= put(def, "COLOR_POPUP_LONGPRESS_THEME", dark ? "000000" : "FFFFFF");
        }

        // 长按气泡里的文字色：搜狗蓝 → iOS 蓝
        changed |= putColor(sections, "COMMON_TEXT_POPUP_COLOR", "TEXT_COLOR", dark ? 0xFF0A84FF : 0xFF007AFF);
        changed |= putColor(sections, "COMMON_TEXT_POPUP_COLORS", "TEXT_COLOR", dark ? 0xFF0A84FF : 0xFF007AFF);
        changed |= putColor(sections, "COMMON_TEXT_POPUP_COLORS", "TEXT_COLOR_PRESSED", dark ? 0xFF0A84FF : 0xFF007AFF);

        // 候选栏：iOS 的拼音/候选不画底色块（只有一个浅色下划提示），把整块底色去掉
        Map<String, String> composing = sections.get("ComposingView");
        if (composing != null) {
            changed |= put(composing, "BG_COLOR", "0x00000000");
        }
        Map<String, String> cloud = sections.get("CloudView");
        if (cloud != null) {
            changed |= put(cloud, "BG_COLOR", "0x00000000");
        }
        return changed;
    }

    private static boolean put(Map<String, String> map, String key, String value) {
        if (map == null || key == null) return false;
        String old = map.get(key);
        if (old == null || value.equalsIgnoreCase(old)) {
            // 键不存在就不新增（避免给不认识的段塞值）
            return false;
        }
        map.put(key, value);
        return true;
    }

    private static boolean putColor(Map<String, Map<String, String>> sections,
                                    String section, String key, int argb) {
        Map<String, String> s = sections.get(section);
        if (s == null) return false;
        String hex = String.format("0x%08X", argb);
        return put(s, key, hex);
    }

    // ── 2. 位图重上色 ─────────────────────────────────────────────────────

    /**
     * 该图片名（不含目录）是否需要重上色；需要则返回目标颜色，否则返回 0。
     *
     * <p>只处理「键盘底色 / 字母键 / 功能键 / 长按气泡」这几类最影响观感的图；
     * 其它（图标、动效帧、表情）一律不动，避免把界面搞花。
     */
    public static int targetColor(String fileName, boolean dark) {
        if (fileName == null) return 0;
        String n = fileName;
        int slash = n.lastIndexOf('/');
        if (slash >= 0) n = n.substring(slash + 1);
        if (!n.endsWith(".png") && !n.endsWith(".webp")) return 0;

        boolean pressed = n.contains("_p.") || n.contains("_press") || n.endsWith("_p.png");

        // 键盘整体底色
        if (n.equals("bg.png") || n.equals("bg1.png") || n.equals("bg_cands_bottom.png")
                || n.equals("bg_cloud_top.png") || n.equals("bg_cloud_bottom.png")
                || n.equals("bg_composing_top.png") || n.equals("bg_composing_bottom.png")) {
            return dark ? DARK_KB_BG : LIGHT_KB_BG;
        }
        // 字母键 / 数字键
        if (n.startsWith("key_qwerty") || n.equals("key.png") || n.equals("key_n.png")
                || n.equals("key_p.png") || n.startsWith("key_us")) {
            if (pressed) return dark ? DARK_KEY_PRESSED : LIGHT_KEY_PRESSED;
            return dark ? DARK_KEY : LIGHT_KEY;
        }
        // 功能键（搜狗用 key_function*；有的皮肤直接复用 key.png，故上面已覆盖常态）
        if (n.startsWith("key_function") || n.startsWith("key_func")) {
            if (pressed) return dark ? DARK_FUNC_PRESSED : LIGHT_FUNC_PRESSED;
            return dark ? DARK_FUNC : LIGHT_FUNC;
        }
        // 长按气泡
        if (n.startsWith("popup") || n.startsWith("bg_popup")) {
            return dark ? DARK_POPUP : LIGHT_POPUP;
        }
        return 0;
    }

    /**
     * 把位图重上色为 {@code color}，**保留原始尺寸与 alpha 形状**。
     *
     * <p>做法：先铺一层目标色，再用原图的 alpha 做 {@code DST_IN} 遮罩 ——
     * 圆角、内阴影、边框的透明度全部沿用原图，所以看起来仍是搜狗自己的键帽，
     * 只是换成了 iOS 的灰白配色（九宫格拉伸参数也因此完全不变）。
     */
    public static Bitmap recolor(Bitmap src, int color) {
        if (src == null || src.isRecycled()) return null;
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= 0 || h <= 0) return null;
        try {
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(color);
            canvas.drawRect(0f, 0f, w, h, paint);
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
            canvas.drawBitmap(src, 0f, 0f, paint);
            paint.setXfermode(null);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 日志用：本次改了哪些内容。 */
    public static String describe() {
        return "iOS skin: iOS 蓝强调色 + 去候选底色块 + 键帽/键盘底色重上色（仅颜色，不动布局）";
    }
}
