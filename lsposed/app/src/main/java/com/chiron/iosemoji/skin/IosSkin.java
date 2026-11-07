package com.chiron.iosemoji.skin;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 皮肤引擎 —— **纯数据 + 纯算法**，不做任何 hook。
 *
 * <p>为什么走皮肤层（而不是注入自绘键盘）：搜狗键盘的布局、命中区、九宫格比例全部由它自己的
 * 皮肤 ini 驱动（{@code py_9.ini} / {@code cands.ini} / {@code images_config.ini} /
 * {@code image_list.ini}），外面盖一层 View 会打乱它算好的 insets 与命中区。
 *
 * <p>配色不再是写死的一套，而是由 {@link SkinPreset} 决定（iOS / 微信 / Google / 莫奈取色）。
 * 本类只负责"怎么画"，"用什么色"由预设提供：
 * <ol>
 *   <li>{@link #patchIni(String, Map, SkinPreset)}：把解析出来的 ini 段落就地改值；</li>
 *   <li>{@link #kindOf(String)} / {@link #colorOf(String, SkinPreset)}：按位图文件名分类并取色；</li>
 *   <li>{@link #synth(Bitmap, int, int, SkinPreset)}：**按预设规格重新绘制**该位图
 *       （圆角、投影边、底边分离线都自己画），而不是给搜狗原图刷一层色。</li>
 * </ol>
 *
 * <p>为什么是"重绘"而不是"重上色"：搜狗默认皮肤的键帽圆角明显小于 iOS（近似直角小圆角），
 * 直接给原图刷色会把这个形状一并保留下来，看起来就不像 iOS。重绘时只沿用原图的**不透明包围盒**
 * （= 键帽位置与大小，保证九宫格拉伸参数继续有效），圆角半径、描边、投影边全部按预设比例自算。
 */
public final class IosSkin {

    public static final int KIND_NONE = 0;
    /** 整块底色（键盘背景 / 候选栏底 / 顶部条底）：不加圆角，直接平铺。 */
    public static final int KIND_FILL = 1;
    /** 键帽（字母键 / 功能键 / 数字键）：按预设圆角重绘 + 底边分隔。 */
    public static final int KIND_KEY = 2;
    /** 长按气泡：圆角更大。 */
    public static final int KIND_POPUP = 3;
    /**
     * 强调键：回车 / 发送 / 确认键 —— 铺 accent 圆角底，再把键上原有字形抠成对比色盖回去
     * （微信风格：发送键是绿的）。
     *
     * <p>为什么单独一类：isProtected 原本保护 enter / return，H41 直接跳过就等于这块永远是
     * 搜狗原来的灰；但也不能把 key / func 那层配色推给它们 —— iOS / Gboard / 莫奈的发送键
     * 是跟功能键同色的，不该变绿。所以只有 accentEnter=true 的预设才走这一类。
     */
    public static final int KIND_ACCENT = 4;

    /**
     * 键帽圆角增益（v2.1.3）：取完「原图自带圆角」与「预设比例圆角」的较大值之后，再乘这个倍数。
     *
     * <p><b>为什么乘在最终值上，而不是逐套调 {@link SkinPreset#radiusRatio}</b>：
     * 实际生效半径 = {@code max(detectRadius(src), 键高 × radiusRatio)}。各键尺寸不同，
     * 有的取到"原图圆角"这一路、有的取到"预设比例"这一路，逐套调比例会出现
     * 「大键变圆了、小键没动」的断层；乘在最终值上，所有键按同一比例变圆，
     * 键帽 / 长按气泡 / 强调键之间的层级关系（气泡 1.5× 于键帽）也原样保留 —— 这才是"不失去协调"。
     *
     * <p>1.5 = 相对 v2.1.2 及以前圆润 +50%（之前键帽圆角大约落在键高的 16%~25%，
     * 提升后约 24%~34%，与微信风格那颗 0.19 的 squircle 处在同一条曲率线上）。
     */
    public static final float RADIUS_BOOST = 1.5f;

    /**
     * 圆角上限（占键帽短边的比例）。
     *
     * <p>0.46 的含义：再往上就是"胶囊"（短边的一半 = 0.5 时两侧完全半圆），
     * 留 4% 的直边，方键帽看起来仍是"圆角矩形"而不是"药丸"，这是协调性的下限也是上限。
     */
    private static final float RADIUS_CAP = 0.46f;

    private IosSkin() {
    }

    /** 该 ini 路径是否属于「深色皮肤」目录（搜狗用 black/ 表示深色，white/ 表示浅色）。 */
    public static boolean isDarkPath(String path) {
        return path != null && path.contains("/black/");
    }

    /**
     * 是否按深色配色处理（alpha10 修正）。
     *
     * <p>只看路径会判断错：很多资源（默认主题 zip 里的、第三方皮肤里的、缓存目录里的）
     * 路径里既没有 {@code /black/} 也没有 {@code /white/}，以前一律当浅色处理 ——
     * 于是夜间模式下出现「白字涂在白键帽上」这种对比度极差的情况。
     * 现在的顺序：路径明确 black → 深色；明确 white → 浅色；都不明确 → 跟系统夜间模式走。
     */
    public static boolean isDark(String path, Context ctx) {
        if (path != null) {
            if (path.contains("/black/")) return true;
            if (path.contains("/white/")) return false;
        }
        if (ctx == null) return false;
        try {
            Configuration cfg = ctx.getResources().getConfiguration();
            return (cfg.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }

    // ── 0. 对比度工具 ─────────────────────────────────────────────────────

    private static final AtomicInteger TXT_FIXED = new AtomicInteger();

    /** 因对比度不足而被改写的文字/图标颜色次数（诊断用）。 */
    public static int contrastFixes() {
        return TXT_FIXED.get();
    }

    /** WCAG 对比度（1.0–21.0）。用来避免「深色模式下白字压白键」这类看不清的情况。 */
    public static double contrast(int fg, int bg) {
        double a = relLuminance(fg);
        double b = relLuminance(bg);
        double hi = Math.max(a, b);
        double lo = Math.min(a, b);
        return (hi + 0.05) / (lo + 0.05);
    }

    private static double relLuminance(int argb) {
        return 0.2126 * linear((argb >> 16) & 0xFF)
                + 0.7152 * linear((argb >> 8) & 0xFF)
                + 0.0722 * linear(argb & 0xFF);
    }

    private static double linear(int c) {
        double s = c / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    /**
     * 保证前景在给定底色上看得清：不足 {@code min} 时换成黑或白里更好的那个。
     *
     * <p>这是 alpha10 对「深色模式对比度不够」的直接处理 —— 不再无条件给深色皮肤配白字，
     * 而是按真实底色算一遍。
     */
    /** 被刷成强调色的回车 / 发送键数量（诊断用）。 */
    private static final AtomicInteger ACCENT_KEYS = new AtomicInteger();
    /** 强调键上保住原字形的次数（远小于 accentKeys 说明字形被涂掉了）。 */
    private static final AtomicInteger GLYPH_KEPT = new AtomicInteger();

    /** 刷成强调色的回车 / 发送键数量。 */
    public static int accentKeys() {
        return ACCENT_KEYS.get();
    }

    /** 强调键保住原字形的次数。 */
    public static int accentGlyphs() {
        return GLYPH_KEPT.get();
    }

    public static int ensureContrast(int fg, int bg, double min) {
        double now = contrast(fg, bg);
        if (now >= min) return fg;
        double black = contrast(0xFF000000, bg);
        double white = contrast(0xFFFFFFFF, bg);
        if (Math.max(black, white) <= now) return fg;
        TXT_FIXED.incrementAndGet();
        return black >= white ? 0xFF000000 : 0xFFFFFFFF;
    }

    // ── 1. ini 段落改值 ───────────────────────────────────────────────────

    /**
     * 就地把搜狗皮肤 ini 里的颜色改成预设配色。
     *
     * <p>只改「文字色 / 强调色 / 底色」这类语义明确的键，**不动任何 W / H / PADDINGS / ROWS**：
     * 尺寸一动就连锁影响 insets 与命中区。
     */
    public static boolean patchIni(String path, Map<String, Map<String, String>> sections, SkinPreset p) {
        if (sections == null || sections.isEmpty() || p == null) return false;
        boolean changed = false;

        // 按键文字色：搜狗默认是深灰 35373B → 预设的文字色。
        // alpha10：不再无条件用预设的那一个文字色，而是按**键帽底色**算一遍对比度
        // （深色皮肤里如果键帽本身偏亮，白字就会糊成一片）。
        int keyText = ensureContrast(p.text, p.key, 3.0);
        int funcText = ensureContrast(p.text, p.func, 3.0);
        int keyTextPressed = ensureContrast(p.textPressed, p.keyPressed, 3.0);
        int accentOnBar = ensureContrast(p.accent, p.kbBg, 3.0);
        Map<String, String> def = sections.get("DEFAULT_TEXT_COLORS");
        if (def != null) {
            changed |= put(def, "COLOR_FIRST_DEFAULT_THEME", hexNoAlpha(accentOnBar));
            changed |= put(def, "COLOR_FUNCTION_THEME", hexNoAlpha(funcText));
            changed |= put(def, "COLOR_FUNCTION_PRESSED_THEME", hexNoAlpha(p.textPressed));
            changed |= put(def, "COLOR_LATIN_THEME", hexNoAlpha(keyText));
            changed |= put(def, "COLOR_LATIN_PRESSED_THEME", hexNoAlpha(keyTextPressed));
            changed |= put(def, "COLOR_PRESSED_THEME", hexNoAlpha(keyTextPressed));
            changed |= put(def, "COLOR_POPUP_LONGPRESS_THEME", hexNoAlpha(ensureContrast(p.text, p.popup, 3.0)));
            changed |= put(def, "COLOR_NUMBER_THEME", hexNoAlpha(keyText));
            changed |= put(def, "COLOR_SYMBOL_THEME", hexNoAlpha(keyText));
            changed |= put(def, "COLOR_SHIFT_THEME", hexNoAlpha(funcText));
        }

        // 长按气泡文字：未选中用正文色、选中用强调色（气泡底是键帽色，必须保证对比度）
        int popupText = ensureContrast(p.text, p.popup, 3.0);
        changed |= putColor(sections, "COMMON_TEXT_POPUP_COLOR", "TEXT_COLOR", popupText);
        changed |= putColor(sections, "COMMON_TEXT_POPUP_COLORS", "TEXT_COLOR", popupText);
        changed |= putColor(sections, "COMMON_TEXT_POPUP_COLORS", "TEXT_COLOR_PRESSED", accentOnBar);

        // 候选栏 / 拼音区 / 顶部工具条：**底色统一成键盘底色**（不是透明）——
        // 搜狗默认是浅灰 + 灰底块，跟键盘底色不一致，看起来"上面那条没跟着一起画"。
        //
        // alpha12：不再只盯 8 个写死的段名，而是把 cands.ini 里**所有**候选/工具条类段
        // （CandidateView / CandidateWordView / CandidateTabView / FirstCandidateContainer /
        // More_Cands_Bar / Translate_Bar / SymbolGridView …）一起统一，否则总有一条漏网。
        int barText = ensureContrast(p.text, p.kbBg, 3.0);
        for (String name : sections.keySet()) {
            if (!isBarSection(name)) continue;
            changed |= unifyBg(sections, name, p.kbBg);
            changed |= putColor(sections, name, "TEXT_COLOR", barText);
        }

        // 候选选中项：强调色（iOS 蓝 / 微信绿 / Google 蓝 / 莫奈 accent）
        changed |= putColor(sections, "CloudView", "TEXT_HIGHLIGHT_COLOR", accentOnBar);
        changed |= putColor(sections, "CandidateBar", "TEXT_HIGHLIGHT_COLOR", accentOnBar);
        changed |= putColor(sections, "CloudView", "TEXT_HIGHLIGHT_STYLE", accentOnBar);

        // 回车 / 发送段：微信预设把「发送」刷成强调色（iOS / Google / 莫奈 / 搜狗经典保持原样）。
        // ini 也要改，因为有些皮肤的回车键底是「底色图 + 文字」，只走位图那条路拿不到字。
        if (p.accentEnter) {
            int onAccent = ensureContrast(0xFFFFFFFF, p.accent, 1.75);
            for (String name : sections.keySet()) {
                if (!isAccentSection(name)) continue;
                changed |= unifyBg(sections, name, p.accent);
                changed |= putColor(sections, name, "TEXT_COLOR", onAccent);
                changed |= putColor(sections, name, "TEXT_COLOR_PRESSED", onAccent);
            }
        }

        return changed;
    }

    /**
     * 这一段是不是「候选栏 / 顶部工具条 / 拼音区」这类**需要跟键盘底色统一**的段。
     *
     * <p>判据是段名里的关键词，而不是写死清单：这套皮肤的 ini 有 300+ 个段，
     * 候选相关就有 CandidateView / CandidateWordView / CandidateTabView / CandidateGridView /
     * FirstCandidate* / CandidateViewWeixin / CandidateViewDoubleLines / FloatCandidateViewContainer …
     * 写死清单永远会漏，所以按语义匹配。
     */
    /**
     * 这张平铺底图属于「候选栏 / 顶部工具条」还是「键盘底」。
     *
     * <p>微信那套皮肤里候选栏是**白底**、键盘底是米灰，两者不同色；iOS/Gboard 则基本同色，
     * 所以底色要分开取值，不能一张 {@code p.kbBg} 铺到底。</p>
     */
    private static boolean isBarImage(String fileName) {
        if (fileName == null) return false;
        String n = fileName.toLowerCase(Locale.ROOT);
        int slash = n.lastIndexOf('/');
        if (slash >= 0) n = n.substring(slash + 1);
        return n.startsWith("candidate") || n.startsWith("cand_bg") || n.startsWith("bg_cand")
                || n.startsWith("first_cand") || n.startsWith("more_cands")
                || n.startsWith("function_bg") || n.startsWith("float_function")
                || n.startsWith("cloud_bg") || n.startsWith("bg_cloud")
                || n.startsWith("bg_composing") || n.startsWith("composing_bg");
    }
    private static boolean isBarSection(String name) {
        if (name == null || name.isEmpty()) return false;
        String s = name.toLowerCase();
        // 候选（含 tab / grid / 首候选 / 双行 / 微信样式 / 悬浮）
        if (s.contains("candidate")) return true;
        // 明确属于"条"的段
        return s.equals("composingview") || s.equals("cloudview") || s.equals("floatwindow")
                || s.equals("headerview") || s.equals("keyboardheader") || s.equals("smartbar")
                || s.equals("toolbarview") || s.equals("more_cands_bar")
                || s.equals("translate_bar") || s.equals("symbolgridview")
                || s.equals("keyboardswitchcontainer") || s.equals("platformcontainer")
                || s.equals("editview") || s.equals("customfunctionview")
                || s.equals("searchbar") || s.equals("passive_text_view");
    }

    /** 段落若存在就把它的底色类键统一成 {@code bgColor}。 */
    private static boolean unifyBg(Map<String, Map<String, String>> sections, String section, int bgColor) {
        Map<String, String> s = sections.get(section);
        if (s == null) return false;
        boolean changed = false;
        changed |= put(s, "BG_COLOR", String.format("0x%08X", bgColor));
        changed |= put(s, "BG_COLOR_PRESSED", String.format("0x%08X", bgColor));
        return changed;
    }

    private static boolean put(Map<String, String> map, String key, String value) {
        if (map == null || key == null) return false;
        String old = map.get(key);
        if (old == null) return false; // 键不存在就不新增，避免给不认识的段塞值
        if (value.equalsIgnoreCase(old)) return false;
        map.put(key, value);
        return true;
    }

    private static boolean putColor(Map<String, Map<String, String>> sections,
                                    String section, String key, int argb) {
        Map<String, String> s = sections.get(section);
        if (s == null) return false;
        return put(s, key, String.format("0x%08X", argb));
    }

    private static String hexNoAlpha(int argb) {
        return String.format("%06X", argb & 0xFFFFFF);
    }

    // ── 2. 位图分类 ───────────────────────────────────────────────────────

    /**
     * 该图片需要怎么改：{@link #KIND_FILL} 平铺底色 / {@link #KIND_KEY} 键帽 /
     * {@link #KIND_POPUP} 气泡 / {@link #KIND_NONE} 不动。
     *
     * <p><b>alpha9 起改为严格白名单</b>：alpha8 用过「名字里带 key / 以 btn_ 开头就算键帽」的宽规则，
     * 结果把**图标**也当键帽刷成了色块（设置齿轮、旗帜/语言图标这类）。图标是"透明底 + 单色字形"，
     * 亮度很均匀，任何基于"看起来像键帽"的判断都救不了它 —— 只能靠名字排除 + 覆盖率兜底。
     */
    public static int kindOf(String fileName) {
        if (fileName == null) return KIND_NONE;
        String n = baseName(fileName).toLowerCase();
        if (!n.endsWith(".png") && !n.endsWith(".webp")) return KIND_NONE;

        // 气泡（要先判，避免 popup bg 被当成普通底色）
        if (n.startsWith("popup") || n.startsWith("bg_popup")) return KIND_POPUP;

        // 整块底色：显式白名单，而且**放在 isProtected 之前**。
        //
        // alpha12：候选栏 / 顶部工具条那一片的底色图叫 candidate_bg*.png、first_cand_bottom.png、
        // more_cands_bar_bg.png、function_bg.png —— 旧规则里写的是 "bg_candidate"（顺序反了），
        // 一个都没匹配上；再加上 "more"/"tool" 在黑名单里，more_cands_bar_bg 还会被误杀。
        // 结果就是「键盘换了色，上面那条候选/工具条还是搜狗原来的灰」。
        if (isBgImage(n)) return KIND_FILL;

        // 回车 / 发送 / 确认键（仅 accentEnter 预设命中）：必须排在 isProtected 之前，
        // 否则 enter / return 会被保护名单吃掉，永远是搜狗原来的灰。
        if (isAccentKey(n)) return KIND_ACCENT;

        if (isProtected(n)) return KIND_NONE;

        // 键帽：startsWith("key") 白名单（key_qwerty / key_us / key_n / key_function …）
        if (n.startsWith("key_qwerty") || n.startsWith("key_us") || n.startsWith("key_uk")
                || n.startsWith("key_number") || n.startsWith("key_digit")
                || n.startsWith("key_char") || n.startsWith("key_symbol")
                || n.startsWith("key_normal") || n.startsWith("key_pressed")
                || n.startsWith("key_function") || n.startsWith("key_func")
                || n.startsWith("key_space") || n.startsWith("key_bg")
                || n.equals("key.png") || n.equals("key_n.png") || n.equals("key_p.png")) {
            return KIND_KEY;
        }
        return KIND_NONE;
    }

    /**
     * 图标 / 菜单 / 语言标志这类**不是键帽**的资源，一律不碰。
     *
     * <p>用户反馈的两处具体症状就落在这里：键盘上的「设置菜单」齿轮图标，
     * 以及语言/地区那面「旗帜」图标 —— 它们都是透明底单色字形，
     * 一旦被当成键帽平铺，就会变成一块纯色方块。
     */
    /**
     * 「整块底色」白名单 —— 从这套皮肤的 ini 里逐个抠出来的真实文件名。
     *
     * <p>来源：`cands.ini` 里各段的 `BG_IMAGE` / `BG_IMAGE_BOTTOM` / `BG_IMAGES`，
     * 经 `image_list.ini` 映射到 png（如 `CandBg_Normal → candidate.png`）。
     * 这些图必须**整张贴底色**：它们就是候选栏、顶部工具条、拼音区、更多候选面板的底。
     */
    private static boolean isBgImage(String n) {
        if (n.equals("bg.png") || n.equals("bg1.png")) return true;
        // 键盘底 / 候选栏底 / 拼音区底 / 云候选底
        if (n.startsWith("bg_cands") || n.startsWith("bg_cloud") || n.startsWith("bg_composing")) return true;
        // 候选栏：candidate_bg / candidate_bg_land / candidate_bg_tab / candidate_bg_weixin / candidate_bg_2lines
        if (n.startsWith("candidate_bg")) return true;
        // 候选格子底色（每格一张，CandBg_Normal → candidate.png / candidate_n / candidate_p）
        if (n.equals("candidate.png") || n.equals("candidate_n.png") || n.equals("candidate_p.png")) return true;
        // 候选 tab
        if (n.startsWith("candidate_tab")) return true;
        // 首候选条底
        if (n.startsWith("first_cand_bottom")) return true;
        // 更多候选 / 符号面板的底
        if (n.startsWith("more_cands_bar_bg") || n.startsWith("more_cands_filter_bg")
                || n.startsWith("more_cands_filteritems_bg") || n.startsWith("more_candis_grid")) return true;
        // 顶部工具条 / 功能键条 / 悬浮工具条
        if (n.startsWith("function_bg") || n.startsWith("float_function") || n.startsWith("float_function_grey"))
            return true;
        if (n.startsWith("cloud_bg") || n.startsWith("blank_shadow_bg")) return true;
        if (n.startsWith("bg_container_key")) return true;
        // 兜底：显式的 *_bg / bg_* 命名（header / smartbar / toolbar）
        if (n.startsWith("bg_header") || n.startsWith("bg_smart") || n.startsWith("bg_top")
                || n.startsWith("bg_toolbar") || n.startsWith("toolbar_bg") || n.startsWith("smartbar_bg"))
            return true;
        return n.startsWith("cand_bg") || n.startsWith("bg_candidate");
    }

    public static boolean isProtected(String fileName) {
        if (fileName == null) return false;
        String n = baseName(fileName).toLowerCase();
        String[] bad = {
                "icon", "logo", "menu", "setting", "gear", "flag", "globe", "lang",
                "voice", "mic", "search", "more", "smile", "emoji", "face", "doutu",
                "arrow", "back", "shift", "delete", "enter", "return", "space_icon",
                "switch", "hide", "expand", "collapse", "tool", "theme", "skin",
                "preview", "anim", "lottie", "loading", "progress", "cursor",
                "sogou", "dot", "indicator", "badge", "tips", "guide"
        };
        for (String s : bad) {
            if (n.contains(s)) return true;
        }
        return false;
    }

    private static String baseName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /**
     * 「名字没在清单里，但看着就是键帽」的兜底判断。
     *
     * <p><b>alpha9 起已停用（不要再接回来）</b>：图标是「透明底 + 单色字形」，
     * 文件名里同样会带 {@code key}／{@code btn_}（例如 {@code btn_key_menu.png}、
     * {@code key_flag_lang.png}），按名字兜底会把这些图标判成键帽刷成纯色方块 ——
     * 用户看到的「设置齿轮图标」「语言/地区旗帜图标显示错乱」就是这么来的。
     * 现在只认 {@link #kindOf} 的严格白名单，宁可少改几张，也不能把图标涂了。
     *
     * @deprecated alpha9 起不参与任何判断，保留只为说明踩过的坑。
     */
    @Deprecated
    public static boolean looksLikeKey(String fileName) {
        if (fileName == null) return false;
        String n = baseName(fileName).toLowerCase();
        if (!n.endsWith(".png") && !n.endsWith(".webp")) return false;
        if (n.startsWith("popup") || n.startsWith("bg_popup")) return false;
        if (isProtected(n)) return false;
        return n.startsWith("key_qwerty") || n.startsWith("key_us") || n.equals("key.png");
    }

    /** 该图片是否属于「功能键」配色（比字母键更灰 / 更淡）。 */
    public static boolean isFuncKey(String fileName) {
        if (fileName == null) return false;
        String n = baseName(fileName);
        return n.startsWith("key_function") || n.startsWith("key_func") || n.contains("function");
    }

    /** 该图片是否用于"按下"态。 */
    public static boolean isPressed(String fileName) {
        if (fileName == null) return false;
        String n = baseName(fileName);
        return n.contains("_p.") || n.contains("_press") || n.endsWith("_p.png") || n.contains("_down");
    }

    /** 该位图应当填的目标颜色；{@code KIND_NONE} 时返回 0。 */
    public static int colorOf(String fileName, SkinPreset p) {
        return colorOfKind(fileName, kindOf(fileName), p);
    }

    /**
     * 按**已判定**的 kind 取目标颜色。
     *
     * <p>为什么要多这个方法：{@code ThemeSkinHook} 里可能用 {@link #looksLikeKey} 把名字不认识的图
     * 兜底判成 {@code KIND_KEY}；如果这里再调一次 {@link #kindOf} 就会得到 {@code KIND_NONE} 并返回 0，
     * 那些图就永远改不上（alpha6 的真 bug：键帽读到了却一张没重画）。
     */
    public static int colorOfKind(String fileName, int kind, SkinPreset p) {
        if (p == null) return 0;
        if (kind == KIND_NONE) return 0;
        boolean pressed = isPressed(fileName);
        boolean func = isFuncKey(fileName);
        switch (kind) {
            case KIND_FILL:
                // v2.0.3-beta8：条与键盘底同色，不再区分。
        return p.kbBg;
            case KIND_POPUP:
                return p.popup;
            case KIND_ACCENT:
                // 只有 accentEnter 预设（目前只有微信）才给颜色；返回 0 时调用方
                // （ThemeSkinHook）会跳过，等于回到「动作键完全不动」的旧行为。
                if (!p.accentEnter) return 0;
                return pressed ? darken(p.accent, 0.12f) : p.accent;
            case KIND_KEY:
            default:
                if (func) return pressed ? p.funcPressed : p.func;
                return pressed ? p.keyPressed : p.key;
        }
    }

    // ── 3. 按预设规格重绘位图 ─────────────────────────────────────────────

    /**
     * 重绘位图。
     *
     * <ul>
     *   <li>{@code KIND_FILL}：整张铺目标色（键盘底 / 候选栏底 / 顶部条底）；</li>
     *   <li>{@code KIND_KEY}：在**原图不透明包围盒**内画圆角矩形（半径 = 高度 × 预设比例），
     *       带投影与底边分隔 —— 键帽位置与尺寸仍是搜狗的（九宫格参数不变），但形状是预设的；</li>
     *   <li>{@code KIND_POPUP}：同上，圆角更大。</li>
     * </ul>
     *
     * @return 新的位图；失败返回 null（调用方保持原样）
     */
    public static Bitmap synth(Bitmap src, int kind, int color, SkinPreset p) {
        if (src == null || src.isRecycled() || p == null) return null;
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= 0 || h <= 0) return null;
        try {
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setStyle(Paint.Style.FILL);

            if (kind == KIND_FILL) {
                // 莫奈取色：底色不是一块死板的灰，而是一层极轻的竖向渐变
                // （顶部略提亮 6%、底部略压暗 5%），这就是"氛围感"的来源。
                if (p.gradient && h > 4) {
                    paint.setShader(new LinearGradient(0f, 0f, 0f, h,
                            lighten(color, 0.06f), darken(color, 0.05f), Shader.TileMode.CLAMP));
                    canvas.drawRect(0f, 0f, w, h, paint);
                    paint.setShader(null);
                } else {
                    paint.setColor(color);
                    canvas.drawRect(0f, 0f, w, h, paint);
                }
                return out;
            }

            Rect box = opaqueBounds(src);
            if (box == null || box.width() <= 0 || box.height() <= 0) {
                paint.setColor(color);
                canvas.drawRect(0f, 0f, w, h, paint);
                return out;
            }

            // 强调键（回车 / 发送）：铺 accent 圆角底，再把原图字形抠出来改成对比色盖回去。
            // 必须排在 hasInk / opaqueRatio 之前 —— 那两个判据是「有字形就不动」，
            // 而强调键恰恰要「留住字形、只换底」。
            if (kind == KIND_ACCENT) {
                int gA = Math.max(1, Math.round(box.height() * 0.02f));
                RectF rA = new RectF(box.left + gA, box.top + gA, box.right - gA, box.bottom - gA);
                if (rA.width() < 2f || rA.height() < 2f) rA = new RectF(box);
                float radA = Math.max(2f, Math.min(rA.height() * p.radiusRatio * RADIUS_BOOST,
                        Math.min(rA.width(), rA.height()) * RADIUS_CAP));
                paint.setColor(color);
                canvas.drawRoundRect(rA, radA, radA, paint);
                Bitmap glyph = glyphMask(src);
                if (glyph != null) {
                    int ink = ensureContrast(0xFFFFFFFF, color, 1.75);
                    Paint gp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                    gp.setColorFilter(new PorterDuffColorFilter(ink, PorterDuff.Mode.SRC_IN));
                    canvas.drawBitmap(glyph, 0f, 0f, gp);
                    glyph.recycle();
                    GLYPH_KEPT.incrementAndGet();
                }
                ACCENT_KEYS.incrementAndGet();
                return out;
            }

            // 原图里已经画了图标/文字的图（例如带 ⌫ 的删除键、带 ⏎ 的回车键）不要动：
            // 平铺一层颜色会把图标一起刷掉。这类图保持搜狗原样，观感反而正常。
            if (kind != KIND_FILL && hasInk(src, box)) {
                return null;
            }

            // 覆盖率兜底（alpha9 新增）：真正的键帽图不透明像素几乎填满包围盒（一般 ≥85%）；
            // 图标/字形（设置齿轮、语言与地区旗帜等）只占其中一部分。名字万一没被
            // isProtected 拦住，就靠这一步保命 —— 宁可这张不改，也不能把图标涂成方块。
            if (kind != KIND_FILL && opaqueRatio(src, box) < 0.85f) {
                return null;
            }

            // 「满幅」判据（alpha10 新增）：九宫格键帽图是**满幅出血**的（不透明区几乎等于整张图），
            // 而图标/字形图四周一定留透明边。名字里同时带 key 和 emoji/face 的图最容易误判，
            // 这里用几何再挡一次，保证「Emoji 图标显示不全」这类问题不会复现。
            if (kind == KIND_KEY || kind == KIND_POPUP) {
                boolean fullBleed = box.width() >= w * 0.85f && box.height() >= h * 0.85f;
                if (!fullBleed) {
                    return null;
                }
            }

            // 留出键缝：搜狗原图的 bbox 常常把整格都占满，直接铺色会让相邻键粘在一起。
            // 这里按高度取 2% 作为内缩，宽度方向再收敛一点，iPad/iPhone 都有可见缝隙。
            int gap = Math.max(1, Math.round(box.height() * 0.02f));
            RectF rect = new RectF(box.left + gap, box.top + gap, box.right - gap, box.bottom - gap);
            if (rect.width() < 2f || rect.height() < 2f) {
                rect = new RectF(box);
            }

            // 圆角：优先用搜狗自己这张图的实际圆角（保证形状不对不上），
            // 但它本身偏方 —— 取「原图圆角」与「预设规格圆角」的较大值，既不变形也不方。
            // v2.1.3：这个最终值再乘 RADIUS_BOOST（整体圆润 +50%），并以短边 RADIUS_CAP 封顶，
            // 保证小键（功能键 / 数字键）不会被撑成胶囊，各键曲率仍是一条连续曲线。
            int detected = detectRadius(src, box);
            float spec = rect.height() * ((kind == KIND_POPUP) ? p.radiusRatio * 1.5f : p.radiusRatio);
            float radius = Math.min(Math.max(detected, spec) * RADIUS_BOOST,
                    Math.min(rect.width(), rect.height()) * RADIUS_CAP);
            radius = Math.max(2f, radius);

            // 投影：iOS 键帽只有极淡的一层落影（没有描边），Material 3 用 tonal 阴影。
            // 阴影画在 rect 之外也在位图范围内，正好落在留出的键缝里。
            if (Color_alpha(p.shadowColor) > 0) {
                paint.setColor(color);
                paint.setShadowLayer(p.shadowRadius, 0f, p.shadowDy, p.shadowColor);
                canvas.drawRoundRect(rect, radius, radius, paint);
                paint.clearShadowLayer();
            }

            paint.setColor(color);
            canvas.drawRoundRect(rect, radius, radius, paint);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 不透明像素在包围盒内的占比（抽稀采样，步长随尺寸自适应）。
     *
     * <p>用途：区分「实心键帽」与「透明底图标/字形」。键帽接近 1.0，图标通常明显低于 0.85。
     */
    private static float opaqueRatio(Bitmap src, Rect box) {
        int bw = box.width();
        int bh = box.height();
        if (bw <= 0 || bh <= 0) return 0f;
        int step = Math.max(1, Math.min(bw, bh) / 64);
        int total = 0;
        int opaque = 0;
        for (int y = box.top; y < box.bottom; y += step) {
            for (int x = box.left; x < box.right; x += step) {
                total++;
                if (android.graphics.Color.alpha(src.getPixel(x, y)) > 8) opaque++;
            }
        }
        return total == 0 ? 0f : (float) opaque / (float) total;
    }

    /**
     * 从原图 alpha 通道估算这张资源自己的圆角半径。
     *
     * <p>做法：在包围盒顶部若干行里，从左边向内找第一个不透明像素，这个水平内缩量就约等于圆角半径。
     * 取多行最大值更稳（避开抗锯齿最上一行的误差），最后夹到 <code>[0, 高度/2]</code>。
     */
    private static int detectRadius(Bitmap src, Rect box) {
        int h = box.height();
        if (h <= 2) return 0;
        int maxProbe = Math.max(1, Math.min(h / 2, 24));
        int best = 0;
        for (int dy = 1; dy <= maxProbe; dy += 2) {
            int y = box.top + dy;
            if (y >= box.bottom) break;
            int inset = 0;
            for (int x = box.left; x < box.right; x++) {
                if (((src.getPixel(x, y) >>> 24) & 0xFF) > 24) break;
                inset++;
            }
            if (inset > best) best = inset;
            if (inset == 0) break;
        }
        return Math.min(best, h / 2);
    }

    /**
     * 包围盒内部是否含「墨」（图标 / 文字 / 明显的分隔线）。
     *
     * <p>判据：统计盒内亮度的中位数，再看有多少像素明显偏离它。键帽本体是几乎单色的，
     * 带图标的键会有一小片对比强烈的像素。超过 2% 就认为有墨，这张图原样返回。
     * 完全单色的图最多只会在边缘有几像素抗锯齿，远低于阈值。
     */
    private static boolean hasInk(Bitmap src, Rect box) {
        int total = 0;
        int deviant = 0;
        int[] hist = new int[16];
        int stepX = Math.max(1, box.width() / 48);
        int stepY = Math.max(1, box.height() / 48);
        for (int y = box.top; y < box.bottom; y += stepY) {
            for (int x = box.left; x < box.right; x += stepX) {
                int px = src.getPixel(x, y);
                if (((px >>> 24) & 0xFF) <= 24) continue;
                int lum = luminance(px);
                hist[Math.min(15, lum >> 4)]++;
                total++;
            }
        }
        if (total < 32) return false;
        int medianBucket = 0;
        int acc = 0;
        for (int i = 0; i < 16; i++) {
            acc += hist[i];
            if (acc * 2 >= total) {
                medianBucket = i;
                break;
            }
        }
        int median = medianBucket * 16 + 8;
        for (int i = 0; i < 16; i++) {
            if (Math.abs(i * 16 + 8 - median) >= 40) deviant += hist[i];
        }
        return deviant * 100 > total * 2;
    }

    /**
     * 这张图是不是「回车 / 发送 / 确认」键的底图。
     *
     * <p>只看名字，并刻意排除带 icon 的那批（icon_enter 是箭头图标，平铺成色块就废了），
     * 以及语音 / 表情里也叫 *_send 的键。宁可漏改几张，也不能把图标涂了。
     */
    public static boolean isAccentKey(String fileName) {
        if (fileName == null) return false;
        String n = baseName(fileName).toLowerCase(Locale.ROOT);
        if (!n.endsWith(".png") && !n.endsWith(".webp")) return false;
        if (n.startsWith("icon") || n.contains("_icon") || n.contains("icon_")) return false;
        if (n.contains("voice") || n.contains("mic") || n.contains("smile")) return false;
        return n.contains("enter") || n.contains("return") || n.contains("send")
                || n.contains("confirm");
    }

    /** 这一段是不是「回车 / 发送 / 确认」的 ini 段（候选 / 气泡段先排除，避免误伤）。 */
    private static boolean isAccentSection(String name) {
        if (name == null || name.isEmpty()) return false;
        String s = name.toLowerCase(Locale.ROOT);
        if (s.contains("candidate") || s.contains("cloud") || s.contains("popup")) return false;
        return s.contains("enter") || s.contains("send") || s.contains("confirm");
    }

    /**
     * 把图片里的「字形」抠成蒙版：白字 + 透明底。
     *
     * <p>先统计不透明像素的亮度直方图取中位数，再以中位数为界二分 —— 比阈值亮的归「字形」
     * （输出白色、保留自身 alpha），比阈值暗的归「底」（透明）。整张纯色或字形像素太少时
     * 返回 null，调用方就只铺底色、不盖字。
     */
    private static Bitmap glyphMask(Bitmap src) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= 0 || h <= 0) return null;
        int n = w * h;
        int[] px = new int[n];
        src.getPixels(px, 0, w, 0, 0, w, h);

        int[] hist = new int[32];
        int opaque = 0;
        for (int c : px) {
            if ((c >>> 24) <= 32) continue;
            hist[Math.min(31, luminance(c) >> 3)]++;
            opaque++;
        }
        if (opaque < 16) return null;
        int acc = 0;
        int median = 0;
        for (int i = 0; i < 32; i++) {
            acc += hist[i];
            if (acc * 2 >= opaque) {
                median = i * 8 + 4;
                break;
            }
        }

        int[] out = new int[n];
        int ink = 0;
        for (int i = 0; i < n; i++) {
            int c = px[i];
            int a = c >>> 24;
            if (a <= 32) continue;
            if (luminance(c) >= median) {
                out[i] = (a << 24) | 0x00FFFFFF;
                ink++;
            }
        }
        if (ink < 8) return null;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        b.setPixels(out, 0, w, 0, 0, w, h);
        return b;
    }

    private static int luminance(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (int) (0.299f * r + 0.587f * g + 0.114f * b);
    }

    private static int Color_alpha(int argb) {
        return (argb >>> 24) & 0xFF;
    }

    private static int edgeColor(int color, SkinPreset p) {
        if (color == p.key) return darken(p.key, 0.14f);
        if (color == p.keyPressed) return darken(p.keyPressed, 0.10f);
        if (color == p.func) return darken(p.func, 0.14f);
        if (color == p.funcPressed) return darken(p.funcPressed, 0.10f);
        return color;
    }

    private static int darken(int argb, float factor) {
        int a = (argb >>> 24) & 0xFF;
        int r = (int) (((argb >> 16) & 0xFF) * (1f - factor));
        int g = (int) (((argb >> 8) & 0xFF) * (1f - factor));
        int b = (int) ((argb & 0xFF) * (1f - factor));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** 往白色方向提亮（莫奈渐变用）。 */
    private static int lighten(int argb, float factor) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        r = Math.round(r + (255 - r) * factor);
        g = Math.round(g + (255 - g) * factor);
        b = Math.round(b + (255 - b) * factor);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * 求原图的不透明包围盒（= 搜狗键帽的实际绘制区域）。
     *
     * <p>用抽稀扫描（步长按尺寸自适应，最多 64×64 次采样）保证在 hook 回调里也不拖慢首帧。
     */
    private static Rect opaqueBounds(Bitmap src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int stepX = Math.max(1, w / 64);
        int stepY = Math.max(1, h / 64);
        int left = w, top = h, right = -1, bottom = -1;
        for (int y = 0; y < h; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                if (((src.getPixel(x, y) >>> 24) & 0xFF) > 8) {
                    if (x < left) left = x;
                    if (x > right) right = x;
                    if (y < top) top = y;
                    if (y > bottom) bottom = y;
                }
            }
        }
        if (right < 0 || bottom < 0) return null;
        right = Math.min(w - 1, right + stepX);
        bottom = Math.min(h - 1, bottom + stepY);
        return new Rect(left, top, right + 1, bottom + 1);
    }

    /** 日志用。 */
    public static String describe(SkinPreset p) {
        String name = p == null ? "?" : SkinPreset.labelOf(p.id);
        return name + " 皮肤：键帽/键盘底/功能键/气泡按预设重绘（圆角+投影+底边）"
                + " + 候选栏与顶部条底色统一 + 文字色对比度调整（布局不动）";
    }
}
