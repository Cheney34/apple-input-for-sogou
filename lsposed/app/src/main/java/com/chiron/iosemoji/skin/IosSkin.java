package com.chiron.iosemoji.skin;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;

import com.chiron.iosemoji.config.ModuleConfig;

import java.util.LinkedHashMap;
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
     * 键帽圆角目标区间（v2.1.5）：**把每套皮肤的比例都拉进这一带**，而不是统一乘一个倍数。
     *
     * <p><b>为什么不再乘倍数</b>：v2.1.3 是「最终半径 ×1.5」，可各套预设的 {@link SkinPreset#radiusRatio}
     * 本来就不一样（搜狗经典 0.10、iOS 0.12、Google 0.14、微信 0.19），乘完得到 0.15 / 0.18 / 0.21 / 0.29 ——
     * 同一台机器上换皮肤，圆润度会跟着皮肤跳；搜狗经典那套乘完只有 15%，用户观感就是"这套没生效"。
     *
     * <p><b>v2.1.4 为什么也没变化</b>：区间对了，但半径还写着 {@code max(detectRadius(), spec)}，
     * 而探测上限只有 24px，小键的 spec 恰好 ≤ 24，于是被锁死；大键也只从 36px 挪到 41px（差 5px）。
     * v2.1.5 起半径只由本区间决定，原图不再参与。
     *
     * <p>现在每套皮肤都落在键高的 34%~38%，保留一点点预设之间的相对差（0.34 / 0.35 / 0.38），
     * 但不会再出现"这套圆、那套方"。
     */
    private static final float RATIO_MIN = 0.34f;
    private static final float RATIO_MAX = 0.38f;

    /**
     * 放大系数：预设比例 × 2.9 刚好把 0.10~0.14 这一段推进目标区间
     * （0.10→0.29 被 0.34 兜住、0.12→0.35、0.13→0.38、0.14→0.40 被 0.38 截住、0.19→0.38）。
     */
    private static final float RATIO_GAIN = 2.9f;

    /**
     * 圆角上限（占键帽短边的比例）—— 纯几何保险，不是设计值。
     *
     * <p>0.5 就是"胶囊"（短边一半，两侧完全半圆），超过它算出来的圆角会互相吃掉，
     * 画出来是一坨畸形。所以滑块给到 50% 时正好停在这条线上：
     * 用户往右拉到底，就是这颗键能有的最圆形态，不再被一个更小的常量提前挡住。
     *
     * <p>v2.1.7 及以前这里是 0.48，意味着滑块 48%~50% 完全无效 ——
     * 用户拉到 50% 却和 48% 一模一样，这本身就是"滑块没用"的一种。
     */
    private static final float RADIUS_CAP = 0.5f;

    /** 长按气泡比键帽再圆一点（×1.2），并封顶 0.42，避免气泡变成药丸。 */
    private static final float POPUP_EXTRA = 1.2f;
    private static final float POPUP_CAP = 0.42f;

    /**
     * 诊断用：最近一次重绘的「比例/像素」。
     *
     * <p>为什么要它：装机后如果觉得"圆角没生效"，看状态卡的 {@code skinr=} 就知道是
     * ①图根本没重画（{@code skimg=调用/0}）、还是 ②重画了但比例没到位 —— 这两种情况的修法完全不同。
     */
    private static volatile String sLastRadius = "-";

    public static String lastRadius() {
        return sLastRadius;
    }

    /**
     * 该键帽这一张图实际该用多少"圆角占键高的比例"。
     *
     * <p>v2.1.6 起设置页的「键帽圆角」滑块优先：
     * <ol>
     *   <li>用户设了 10%~50% → 直接用它（参考 WeType UI Enhanced 的 {@code keyCornerRadius}）；</li>
     *   <li>设成 0（自动）→ 走预设区间 {@code clamp(radiusRatio × 2.9, 0.34, 0.38)}。</li>
     * </ol>
     * 长按气泡始终比键帽再圆一档（×1.2），所以层级关系在两种模式下手感一致。
     *
     * @param kind {@link #KIND_POPUP} 时比键帽再圆一档
     */
    private static float targetRatio(SkinPreset p, int kind) {
        int user = ModuleConfig.keyRadiusPercent();
        float eff;
        if (user > 0) {
            eff = user / 100f;
        } else {
            eff = Math.max(RATIO_MIN, Math.min(RATIO_MAX, p.radiusRatio * RATIO_GAIN));
        }
        if (kind == KIND_POPUP) eff = Math.min(POPUP_CAP, eff * POPUP_EXTRA);
        return eff;
    }

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

    /**
     * 被「抽掉底层」的按键段数（v2.1.9 诊断）。
     *
     * <p>{@code UNDER_CLEARED} = 真的把 {@code BG_COLOR} 打成透明的段数；
     * {@code UNDER_SEEN} = 有底图但段里压根没有 {@code BG_COLOR} 这个键的段数
     * （这种只能靠 synth() 里「先铺键盘底色」那层兜住）。
     *
     * <p>状态卡上读 {@code skinb=已透明/无此键}：两个都是 0 说明这段代码没跑到任何按键段，
     * 底层是别的形式（另一张底图 / 另一个图层），下一步就得去 hook 那一层而不是继续调半径。
     */
    private static final AtomicInteger UNDER_CLEARED = new AtomicInteger();
    private static final AtomicInteger UNDER_SEEN = new AtomicInteger();

    /** 被抽掉底色的按键段数（诊断用）。 */
    public static String underCleared() {
        return UNDER_CLEARED.get() + "/" + UNDER_SEEN.get();
    }

    /**
     * 九宫格的主键段名。
     *
     * <p>这 9 个是九宫格**独有**的段名（26 键用 {@code Key_Q} / {@code Key_W} …），
     * 它们在自己那份布局文件里**没有 {@code BG_IMAGES}** —— 26 键的每个键都显式写了
     * {@code KeyBg_Default_Latin}，九宫格一颗都没写。没有 BG_IMAGES 就不加载底图，
     * 按键用一块纯色填充 —— 这就是「改 key.png、调圆角、加高光，九宫格全都纹丝不动」
     * 的最终答案：<b>它根本没有那张图</b>。
     *
     * <p>刻意不包含 {@code Key_Enter}（该是强调色）、{@code Key_Space_py9}
     * （宽度是一半屏，套九宫格切片会被拉成一条长条）、{@code Key_Digits}（已指定
     * {@code KeyBg_Function}）—— 这几个保持原样更稳。
     */
    private static final String[] NINE_KEY_SECTIONS = {
            "Key_PunctPY", "Key_ABC", "Key_DEF", "Key_GHI", "Key_JKL",
            "Key_MNO", "Key_PQRS", "Key_TUV", "Key_WXYZ",
    };

    /** 本轮给九宫格补过 BG_IMAGES 的段数（诊断用）。 */
    private static final AtomicInteger NINE_FIXED = new AtomicInteger();

    public static int nineKeyFixed() {
        return NINE_FIXED.get();
    }

    /**
     * 还需不需要「整格铺键盘底色」这层兜底（v2.2.1）。
     *
     * <p>v2.1.9 无条件铺，结果在深色模式下变成一颗颗<b>比背景略浅的小方块</b> ——
     * 因为默认皮肤的键盘底是 {@code BG_IMAGE=Background} 那张**图片**，
     * 而 {@code p.kbBg} 只是个近似纯色，铺上去必然对不上（浅色模式下色差小，看不出来）。
     *
     * <p>所以改成有条件：
     * <ul>
     *   <li>{@code UNDER_CLEARED > 0}：patchIni 至少清成功过一段 → 底下已经没东西了，
     *       再铺色只会制造方块，<b>不铺</b>；</li>
     *   <li>{@code UNDER_CLEARED == 0 && UNDER_SEEN > 0}：看到了按键段、段里有底图，
     *       但段里压根没有 {@code BG_COLOR} 这个键（清不掉）→ 底下确实有东西，<b>铺</b>；</li>
     *   <li>两个都是 0：这一段代码没跑到任何按键段，情况不明 → 也<b>不铺</b>，
     *       免得无端多出一块色。</li>
     * </ul>
     */
    private static boolean coverNeeded() {
        return UNDER_CLEARED.get() == 0 && UNDER_SEEN.get() > 0;
    }

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
    /** 九宫格这类「带字形键帽」保住字形的次数（v2.2.2）。 */
    private static final AtomicInteger INK_KEPT = new AtomicInteger();

    /** 刷成强调色的回车 / 发送键数量。 */
    public static int accentKeys() {
        return ACCENT_KEYS.get();
    }

    /** 强调键保住原字形的次数。 */
    public static int accentGlyphs() {
        return GLYPH_KEPT.get();
    }

    /** 带字形键帽（九宫格）保住字形的次数。 */
    public static int inkKeys() {
        return INK_KEPT.get();
    }

    /**
     * 这张皮肤图里**本来就画了字形**吗（九宫格的数字 + ABC）。
     *
     * <p>H41 用它决定要不要把这颗键交给 H71 落屏重画：H71 只会画「底 + 玻璃 + 高光」，
     * 不认识字形，交过去等于把数字擦掉。带字形的键留在位图那条路上 ——
     * {@code synth()} 已经把「保留字形、只换底」做完了，画面上一样是圆角 + 玻璃 + 高光。
     */
    public static boolean hasBakedGlyph(Bitmap src) {
        if (src == null || src.isRecycled()) return false;
        try {
            Rect box = opaqueBounds(src);
            return box != null && hasInk(src, box);
        } catch (Throwable t) {
            return false;
        }
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

        // 键帽底层（v2.1.9）：[Key] / [Key_ABC] / [Key_Enter] 这类段自带一层实心底色，
        // 它画在 H41 重画的圆角位图**下面**，形状是方的，而且不跟着预设走
        // —— 圆角画成透明的四个角露出来的就是它，"怎么调半径都没变化"的真正原因。
        // 这里把它打成全透明，让键帽的轮廓只由那张圆角位图决定。
        //
        // 只在段里确实声明了底图（BG_IMAGES / BG_IMAGE 非空）时才动：
        // 没有底图的段是纯色块（"这颗键的形就是这块颜色"），抽掉颜色整颗键会消失，
        // 宁可保持原样 —— 那种段由 synth() 里的「先铺键盘底色」兜住。
        // 九宫格的主键补底图（v2.3.2）：这 9 个段在 py_9.ini 里没有 BG_IMAGES，
        // 没有底图 = 不加载 key.png = 一块纯色。所以 26 键早就跟着预设走了，
        // 九宫格却永远是那个死平的灰块，圆角/玻璃/高光一样都落不下去。
        // 放在清 BG_COLOR 之前：补完之后它们就有底图了，下面那条规则会顺带把
        // 段里可能残留的方形实心底色抽掉。
        for (String name : NINE_KEY_SECTIONS) {
            Map<String, String> s = sections.get(name);
            if (s == null) continue;
            if (hasBgImage(s)) continue;
            s.put("BG_IMAGES", "KeyBg_Default");
            NINE_FIXED.incrementAndGet();
            changed = true;
        }

        for (String name : sections.keySet()) {
            if (!isKeySection(name)) continue;
            Map<String, String> s = sections.get(name);
            if (!hasBgImage(s)) continue;
            boolean cleared = put(s, "BG_COLOR", "0x00000000");
            cleared |= put(s, "BG_COLOR_PRESSED", "0x00000000");
            if (cleared) UNDER_CLEARED.incrementAndGet();
            else UNDER_SEEN.incrementAndGet();
        }

        return changed;
    }

    /**
     * 这一段是不是「按键」段：{@code [Key]} / {@code [Key_ABC]} / {@code [Key_Enter]} …
     *
     * <p>要排掉 {@code [Keyboard]}（整块键盘容器，它的 BG_IMAGE=Background 是键盘底，
     * 打成透明会把整个键盘底抽掉，直接黑屏一片）。
     */
    private static boolean isKeySection(String name) {
        if (name == null) return false;
        String s = name.toLowerCase(Locale.ROOT);
        if (s.startsWith("keyboard")) return false;
        return s.equals("key") || s.startsWith("key_");
    }

    /** 段里是否声明了底图 —— 有底图才说明"形"是由图片画的，底色可以安全抽掉。 */
    private static boolean hasBgImage(Map<String, String> s) {
        if (s == null) return false;
        return nonEmpty(s.get("BG_IMAGES")) || nonEmpty(s.get("BG_IMAGE"))
                || nonEmpty(s.get("BG_IMAGES_PRESSED")) || nonEmpty(s.get("BG_IMAGE_PRESSED"));
    }

    private static boolean nonEmpty(String v) {
        if (v == null) return false;
        String t = v.trim();
        return t.length() > 0 && !"0".equals(t) && !"none".equalsIgnoreCase(t);
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
        // v2.2.0：九宫格（py_9 / big_9）用的键帽图不在上面那份白名单里 ——
        // 反馈「九键的键帽没被改到」就是这里漏的：九键的图名是 key_9 / key_big / key_abc 之类，
        // 一个都没命中，于是 H41 直接 return，连登记都没登记，落屏时自然什么都画不出来。
        // 这里放开成「key 开头就算键帽」，但仍然排在 isBgImage / isAccentKey / isProtected 之后，
        // 后面还有 hasInk / opaqueRatio(≥0.85) / 满幅 三道几何闸门兜着 ——
        // 图标是"透明底 + 单色字形"，过不了那三道门，不会被刷成色块。
        if (n.startsWith("key") && !n.startsWith("keyboard")) return KIND_KEY;
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
     * 名字没认出来、但<b>几何上就是键帽</b>的兜底判定（v2.2.1，专治九宫格）。
     *
     * <p>为什么要有这一步：九键（九宫格）的键帽图名是各渠道自己起的，可能是
     * {@code key_9}、{@code py9_key}、{@code num_normal} 甚至没规律的名字。
     * 名字对不上时 {@link #kindOf} 返回 {@code KIND_NONE}，H41 直接 return ——
     * 连重绘都没有，自然也没有液态玻璃和边缘高光（用户反馈的「九键依旧没有任何玻璃和高光」）。
     *
     * <p>判据跟 {@link #synth} 里那三道闸门完全一致，只多一条尺寸/长宽比：
     * <ol>
     *   <li><b>满幅</b>：不透明区 ≥ 图片的 85%（键帽是出血图，图标四周一定留透明边）；</li>
     *   <li><b>不透明率 ≥ 0.85</b>：实心色块（键帽 ≈ 1.0，字形/图标明显更低）；</li>
     *   <li><b>尺寸像键</b>：16px 以上、长宽比 0.45~3.2（排除整条底栏那种大长图）。</li>
     * </ol>
     *
     * <p><b>不再要求「无字形」</b>（v2.2.2 改）：九宫格的键帽图把数字和 ABC 烧在图里，
     * 旧判据要求无字形，等于直接把九键全部排除在外。带字形的键帽现在走
     * {@code synth()} 的「保留字形、只换底」那条路。
     *
     * <p>图标是「透明底 + 单色字形」，过不了第 1 条（包围盒远小于整张图）；
     * 整条候选栏底是长条形，过不了第 3 条。所以这条兜底不会把图标刷成色块 ——
     * alpha8 那次的教训（设置齿轮、语言旗帜被涂掉）靠的是几何而不是名字。
     */
    public static boolean looksLikeKeyCap(Bitmap src) {
        if (src == null || src.isRecycled()) return false;
        try {
            int w = src.getWidth();
            int h = src.getHeight();
            if (w < 16 || h < 16) return false;
            float ar = (float) w / (float) h;
            if (ar < 0.45f || ar > 3.2f) return false;
            Rect box = opaqueBounds(src);
            if (box == null || box.width() <= 0 || box.height() <= 0) return false;
            float ratio = opaqueRatio(src, box);
            boolean fullBleed = box.width() >= w * 0.85f && box.height() >= h * 0.85f;
            // 满幅，或者「不满幅但矩形非常实心」（九宫格键帽自带透明外边距的情况）
            return fullBleed ? ratio >= 0.85f : ratio >= 0.95f;
        } catch (Throwable t) {
            return false;
        }
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

            // v2.1.7：两项新效果（移植自 WeType UI Enhanced）。每张位图只读一次配置，
            // 避免在几百张图的重绘里反复走 XSharedPreferences。
            final boolean glassOn = ModuleConfig.glassEnabled();
            final boolean rimOn = ModuleConfig.edgeHighlightEnabled();
            final boolean darkOn = isDarkPreset(p);

            if (kind == KIND_FILL) {
                // 液态玻璃：整块底色半透明，下面的（模糊后的）背景才透得上来。
                // 透明度见 glassAlpha()：没挂上澎湃模糊时只做"有一点点透"，避免一片灰黑。
                int fillColor = glassOn ? withAlphaScale(color, glassAlpha(KIND_FILL)) : color;
                // 莫奈取色：底色不是一块死板的灰，而是一层极轻的竖向渐变
                // （顶部略提亮 6%、底部略压暗 5%），这就是"氛围感"的来源。
                if (p.gradient && h > 4) {
                    paint.setShader(new LinearGradient(0f, 0f, 0f, h,
                            lighten(fillColor, 0.06f), darken(fillColor, 0.05f), Shader.TileMode.CLAMP));
                    canvas.drawRect(0f, 0f, w, h, paint);
                    paint.setShader(null);
                } else {
                    paint.setColor(fillColor);
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

            // ── 底层兜底（v2.1.9 铺满 → v2.2.1 改成有条件）──────────────────────
            //
            // 这里铺的是一层**猜测**的键盘底色：默认皮肤的键盘底是 BG_IMAGE=Background
            // 那张图片，p.kbBg 只是它的近似纯色。浅色模式下两者差得少，看不出；
            // 深色模式下就变成一颗颗比背景略浅的方块（用户反馈的那个「淡灰色小方块」）。
            //
            // 所以只在 patchIni 确实清不掉 BG_COLOR 时才铺（判据见 coverNeeded()）。
            // 清得掉的时候，圆角外的四个角透出来的是搜狗自己的键盘底图 —— 那才是对的。
            if (coverNeeded()) {
                final int under = glassOn ? withAlphaScale(p.kbBg, glassAlpha(KIND_FILL)) : p.kbBg;
                paint.setShader(null);
                paint.setColor(under);
                canvas.drawRect(0f, 0f, w, h, paint);
            }

            // 强调键（回车 / 发送）：铺 accent 圆角底，再把原图字形抠出来改成对比色盖回去。
            // 必须排在 hasInk / opaqueRatio 之前 —— 那两个判据是「有字形就不动」，
            // 而强调键恰恰要「留住字形、只换底」。
            if (kind == KIND_ACCENT) {
                int gA = Math.max(1, Math.round(box.height() * 0.02f));
                RectF rA = new RectF(box.left + gA, box.top + gA, box.right - gA, box.bottom - gA);
                if (rA.width() < 2f || rA.height() < 2f) rA = new RectF(box);
                float radA = Math.max(2f, Math.min(rA.height() * targetRatio(p, KIND_KEY),
                        Math.min(rA.width(), rA.height()) * RADIUS_CAP));
                if (glassOn) {
                    drawGlassSurface(canvas, rA, radA,
                            withAlphaScale(color, glassAlpha(KIND_ACCENT)), darkOn);
                } else {
                    paint.setColor(color);
                    canvas.drawRoundRect(rA, radA, radA, paint);
                }
                if (rimOn) {
                    drawRim(canvas, rA, radA, darkOn);
                }
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

            // 图里自带字形/图标（⌫、⏎、**九宫格键帽上烧进去的数字和字母**）怎么处理？
            //
            // v2.1.9 以前一律「不动」—— 因为平铺一层颜色会把字形一起刷掉。
            // 但九宫格的键帽图恰恰是**带字形**的：26 键的字母是搜狗按 TEXT_COLOR 单独画的，
            // 而九键的数字 + ABC 是烧在位图里的。于是这条「有字形就不动」的规则
            // 让九键走进了死胡同：一张都不重绘 ⇒ 没有圆角、没有玻璃、没有高光。
            // 用户反复反馈的「九宫格没有变化」，根因就是这一行 return。
            //
            // v2.2.2 起：键帽 / 气泡这两类改成「保留字形、只换底」——
            // 画完圆角面 + 边缘高光后，把原图里的字形（inkMask，保留原色）原样叠回去。
            // 图标不在这条路上：它们的不透明区远小于整张图，过不了下面的「满幅」闸门。
            final boolean inked = hasInk(src, box);
            final float ratio2 = opaqueRatio(src, box);

            // 覆盖率兜底（alpha9）：真正的键帽图不透明像素几乎填满包围盒（一般 ≥85%）；
            // 图标/字形只占其中一部分。带字形的键帽整张是实心的，覆盖率照样很高，所以放行。
            if (!inked && kind != KIND_FILL && ratio2 < 0.85f) {
                return null;
            }

            // 「满幅」判据（alpha10）：键帽图是**满幅出血**的（不透明区≈整张图），
            // 图标四周一定留透明边。这条同时是「带字形」那类的守门人。
            //
            // v2.2.2 补了个例外：九宫格的键比 26 键大得多，图上常常自带一圈透明外边距
            // （那是键与键之间的缝），包围盒只占整图 70%~85% —— 旧判据直接把九键全部挡掉。
            // 现在「不满幅但**矩形非常实心**（≥0.95）」也放行：键帽是一整块实心矩形，
            // 而圆形/星形图标在包围盒内只占 70%~80%，过不了这一关。
            if (kind == KIND_KEY || kind == KIND_POPUP) {
                boolean fullBleed = box.width() >= w * 0.85f && box.height() >= h * 0.85f;
                if (!fullBleed && ratio2 < 0.95f) {
                    return null;
                }
            } else if (inked) {
                // 底色图 / 其它类型带字形：仍旧不动（整块刷色会把字涂掉）
                return null;
            }

            // 留出键缝：搜狗原图的 bbox 常常把整格都占满，直接铺色会让相邻键粘在一起。
            // 这里按高度取 2% 作为内缩，宽度方向再收敛一点，iPad/iPhone 都有可见缝隙。
            int gap = Math.max(1, Math.round(box.height() * 0.02f));
            RectF rect = new RectF(box.left + gap, box.top + gap, box.right - gap, box.bottom - gap);
            if (rect.width() < 2f || rect.height() < 2f) {
                rect = new RectF(box);
            }

            // 圆角：**完全由规格决定**，不再和「原图探测值」取 max。
            //
            // v2.1.3 / v2.1.4 失效的真正原因就在这里：detectRadius() 的探测步长上限是
            // maxProbe = min(键高/2, 24)，所以 detected 几乎恒等于 24；而
            // radius = max(detected, spec) 一旦 spec ≤ 24，结果就被死死锁在 24，
            // 无论 RATIO_MIN/MAX 怎么调都不动 —— 小键（键高 70~80）两版算出来完全相同，
            // 大键也只是 36px → 41px（差 5px），肉眼当然"没有变化"。
            //
            // 现在的规则：半径 = 键高 × targetRatio()。原图只用来定位轮廓（opaqueBounds），
            // 不参与半径计算，于是每套皮肤、每种键都严格落进 34%~38% 这一带。
            float ratio = targetRatio(p, kind);
            float shortSide = Math.min(rect.width(), rect.height());
            float radius = rect.height() * ratio;
            // v2.1.8：删掉「短边 < 56px 就强推 0.44」那条兜底。
            // 它本意是救被拉伸绘制的小图，实际后果是**把小键和滑块一起架空**：
            // 只要位图短边 < 56，半径恒等于 0.44×键高，滑块 10%~44% 拉出来完全一样 ——
            // 用户看到的就是"调整模块没用"。宁可小图上圆角偏小（那也只是保守），
            // 也不能让用户的每一次拖动都落空。
            radius = Math.max(2f, Math.min(radius, shortSide * RADIUS_CAP));
            if (kind == KIND_KEY) {
                sLastRadius = String.format(Locale.US, "%.2f/%dpx/%dx%d", ratio,
                        Math.round(radius), Math.round(rect.width()), Math.round(rect.height()));
            }

            // 投影：iOS 键帽只有极淡的一层落影（没有描边），Material 3 用 tonal 阴影。
            // 阴影画在 rect 之外也在位图范围内，正好落在留出的键缝里。
            if (Color_alpha(p.shadowColor) > 0) {
                paint.setColor(color);
                paint.setShadowLayer(p.shadowRadius, 0f, p.shadowDy, p.shadowColor);
                canvas.drawRoundRect(rect, radius, radius, paint);
                paint.clearShadowLayer();
            }

            // 键帽本体：玻璃模式下用「半透明 + 竖向 tint 渐变」，否则维持实心平涂。
            // 画法差异只在"面"，圆角/位置与 v2.1.6 完全一致。
            if (glassOn) {
                drawGlassSurface(canvas, rect, radius,
                        withAlphaScale(color, glassAlpha(kind)), darkOn);
            } else {
                paint.setColor(color);
                canvas.drawRoundRect(rect, radius, radius, paint);
            }

            // 边缘高光：最后画，永远落在最上层（和 WeType 把高光 drawable 叠在 surface 之上一致）
            if (rimOn) {
                drawRim(canvas, rect, radius, darkOn);
            }

            // 带字形的键帽（九宫格）：把原图里的字形按原色叠回去 —— 只换了底，字还在。
            if (inked) {
                Bitmap ink = inkMask(src);
                if (ink != null) {
                    Paint ip = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                    canvas.drawBitmap(ink, 0f, 0f, ip);
                    ink.recycle();
                    INK_KEPT.incrementAndGet();
                } else {
                    // 抠不出字形（阈值没命中）—— 那就别冒险换底，保持原样
                    if (out != null && !out.isRecycled()) out.recycle();
                    return null;
                }
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    // ── 3.4 「空白键面」重画（v2.3.1）──────────────────────────────────────
    //
    // 这一节是给 **盘上重绘** 用的：把 key.png / key_qwerty.png 这类**空白键面位图**
    // 直接换成 iOS 键帽，再写回磁盘。搜狗自己会把它读上来、做九宫格切片、当成键底画 ——
    // 我们只管把这张图的像素做对，不参与它的任何绘制流程。
    //
    // 为什么又换赛道：前三版都是「拦绘制」，可九宫格的键底到底经哪一层落屏，
    // 从外部看始终有歧义（H41 的 assets 路 / H72 的 Drawable 路 / 容器底板 / 按下态覆盖）。
    // 拦错一层，整版就是"没有变化"。改文件没有这个问题 —— 文件是唯一的输入，
    // 而且一份文件同时管住 26 键、九宫格、功能键、按下态和长按气泡。
    //
    // 九宫格切片的约束（直接决定下面的画法，别乱改）：
    //   images_config.ini  [key_png] IMAGE=key.png,28,30,38,40 TYPE=1
    //   → 横向固定 28 / 30，纵向固定 38 / 50，只有宽 2px、高 2px 的十字会被拉伸。
    //   所以 ① 圆角不能越过左侧 28px（否则弧线被拉成斜边）；
    //        ② 渐变不能跨过 y=38..40（否则画面上会出现一道横向色阶断层）。

    /**
     * 圆角占「键面宽度」的比例（0.12~0.55）。
     *
     * <p>盘上重绘的源图只有 48~54px 宽，却要铺到 100~150px 的键上（约 1.9 倍），
     * 所以**只看比例**，不看像素：比例对，放大后手感才对。
     * 滑块 10%~50% 映射到 0.12~0.55（从"接近原样"到"胶囊"），自动档落在 0.335。
     */
    public static float keyRadiusFrac(SkinPreset p) {
        int user = ModuleConfig.keyRadiusPercent();
        float t = user > 0 ? Math.max(0f, Math.min(1f, (user - 10) / 40f)) : 0.53f;
        return 0.22f + 0.30f * t;
    }

    /**
     * 把一张**空白键面**重画成 iOS 键帽（保留原图尺寸与透明外边距）。
     *
     * <p>不做任何"这是不是键帽"的判断：调用方已经按文件名认准了。
     * 这批图（key.png / key_qwerty.png / key_func.png …）都是同一张空面，
     * 圆角、玻璃、高光全都由这里给。
     *
     * @param pressed 按下态：整体压暗一档，且不画落影
     * @return 新的位图；失败返回 null（调用方保持原样）
     */
    public static Bitmap paintKeyFace(Bitmap src, int color, SkinPreset p, boolean pressed) {
        if (src == null || src.isRecycled() || p == null) return null;
        final int w = src.getWidth();
        final int h = src.getHeight();
        if (w < 8 || h < 8) return null;
        try {
            Rect box = opaqueBounds(src);
            if (box == null || box.width() < 4 || box.height() < 4) box = new Rect(0, 0, w, h);

            final boolean glass = ModuleConfig.glassEnabled();
            final boolean rim = ModuleConfig.edgeHighlightEnabled();

            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);

            float gap = Math.max(1f, box.height() * 0.018f);
            RectF rect = new RectF(box.left + gap, box.top + gap, box.right - gap, box.bottom - gap);
            if (rect.width() < 4f || rect.height() < 4f) rect = new RectF(box);

            float maxR = Math.min(rect.width(), rect.height()) * 0.5f;
            float frac = keyRadiusFrac(p);
            float radius = Math.max(2f, Math.min(rect.width() * frac, maxR));
            sLastRadius = String.format(Locale.US, "%.2f/%dpx/%dx%d",
                    frac, Math.round(radius), Math.round(rect.width()), Math.round(rect.height()));

            Path face = new Path();
            face.addRoundRect(rect, radius, radius, Path.Direction.CW);

            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setStyle(Paint.Style.FILL);

            // 落影：只在键缝里那一圈，深色模式下很淡，浅色模式下是 iOS 的那种"贴纸感"
            if (!pressed && Color_alpha(p.shadowColor) > 0) {
                paint.setColor(withAlphaScale(color, 0.9f));
                paint.setShadowLayer(p.shadowRadius, 0f, p.shadowDy, p.shadowColor);
                canvas.drawPath(face, paint);
                paint.clearShadowLayer();
            }

            // 键面本体：不透明 + 极轻的竖向渐变（上亮下暗）。
            // 不透明是有意的 —— 预设给的 key 色就是这颗键该有的颜色，
            // 半透明会把预设稀释掉（之前 26 键看起来比预设暗两档就是这个原因）。
            // 渐变也刻意做得很轻（6% / 3%）：九宫格只拉伸中间那 2px，渐变一强，
            // 拉伸带两侧就会显出横向色阶。真要"更亮"，靠的是键面整体提亮，不是靠渐变。
            int base = pressed ? darken(color, 0.07f) : color;
            paint.setShader(new LinearGradient(0f, rect.top, 0f, rect.bottom,
                    lighten(base, glass ? 0.06f : 0.02f),
                    darken(base, glass ? 0.03f : 0.02f), Shader.TileMode.CLAMP));
            canvas.drawPath(face, paint);
            paint.setShader(null);

            // 顶部受光 + 底部压暗：之前只在「液态玻璃」开启时才画，而且只有 0x22（13%），
            // 这么淡的一层贴到 60×90 的小图上、再被九宫格放大 5 倍，屏幕上基本是看不见的。
            // 现在改成「边缘高光或玻璃任一开启」就画，强度提到 0x48（28%）。
            //
            // 上界 24% 高度这条**别改大**：
            //   ① 九宫格纵向拉伸带在 y=38..50（键面内约 41%~57%），跨过去会被拉成一大片；
            //   ② 更实际的原因是显示比例 —— 九宫格纵向要拉长 2 倍多，源图上 24% 的受光带
            //      落到屏幕上只剩顶部 12% 左右，再大就变成"整颗键蒙了一层雾"而不是"顶部受光"。
            final boolean darkFace = isDarkPreset(p);
            if (glass || rim) {
                int save = canvas.save();
                canvas.clipPath(face);
                Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
                float yEnd = rect.top + rect.height() * 0.24f;
                sheen.setShader(new LinearGradient(0f, rect.top, 0f, yEnd,
                        darkFace ? 0x48FFFFFF : 0x26FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
                canvas.drawRect(rect.left, rect.top, rect.right, yEnd, sheen);

                float yStart = rect.bottom - rect.height() * 0.26f;
                sheen.setShader(new LinearGradient(0f, yStart, 0f, rect.bottom,
                        0x00000000, darkFace ? 0x33000000 : 0x1A000000, Shader.TileMode.CLAMP));
                canvas.drawRect(rect.left, yStart, rect.right, rect.bottom, sheen);
                canvas.restoreToCount(save);
            }

            if (rim) drawFaceEdge(canvas, face, rect, darkFace);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 键面内沿的两道边：上沿亮线（受光）+ 下沿暗线（厚度）。
     *
     * <p>两条都**裁在轮廓里侧**画，所以九宫格怎么切都不会把它切到键外去。
     * 上沿只画顶部 16% 那段，越往下自然淡出 —— 这就是 iOS 键帽最容易被认出来的那一笔。
     *
     * <p>深色键面给 0xC4（77%），浅色键面给 0x9E（62%）：白键上再叠纯白只会糊成一片，
     * 62% 刚好让边缘"脆"起来又不发亮。早先这里是 0x66（40%），放大后基本看不见。
     */
    private static void drawFaceEdge(Canvas canvas, Path face, RectF rect, boolean darkFace) {
        float sw = Math.max(1f, rect.height() * 0.022f) * 2f; // 裁掉外侧一半，所以给两倍
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(sw);

        int save = canvas.save();
        canvas.clipPath(face);
        canvas.clipRect(rect.left, rect.top, rect.right, rect.top + rect.height() * 0.14f);
        p.setColor(darkFace ? 0xC4FFFFFF : 0x9EFFFFFF);
        canvas.drawPath(face, p);
        canvas.restoreToCount(save);

        save = canvas.save();
        canvas.clipPath(face);
        canvas.clipRect(rect.left, rect.bottom - rect.height() * 0.10f, rect.right, rect.bottom);
        p.setColor(darkFace ? 0x38000000 : 0x24000000);
        canvas.drawPath(face, p);
        canvas.restoreToCount(save);
    }

    // ── 3.5 真实画布上的键帽（v2.2.0）──────────────────────────────────────
    //
    // 为什么要有这一节：前六版都只改「搜狗喂过来的那张位图」，而那张图最终画到屏幕上
    // 还要经过 ① 九宫格切边（images_config.ini 的 IMAGE=key.png,28,30,38,40）② 底下的
    // 容器/底板图层 ③ 各种拉伸。任何一层不配合，圆角、玻璃、高光就全部消失 ——
    // 用户看到的永远是"改了但没变化"。
    //
    // 现在的做法：**不再指望那张位图**。H41 重画完之后把 (位图 → 键帽参数) 登记一下，
    // 由 {@code KeyCapDrawHook} 在 Canvas.drawBitmap 真正落屏的那一刻，用登记好的参数
    // 重新画一遍键帽：先把这一格涂满键盘底色（底下垫什么都盖住），再画圆角面/玻璃/高光，
    // 最后跳过原始绘制。看到的像素 100% 是我们画的，不再取决于搜狗怎么摆图层。

    /**
     * 一张键帽「落屏时」需要的全部参数。
     *
     * <p>为什么要在 H41 那一刻快照下来，而不是每次绘制时现算：
     * {@code Canvas.drawBitmap} 每秒会被调几百次，里面不能读 SharedPreferences，
     * 也不该再走一遍预设解析。参数在重画时算一次，之后就是纯数值绘制。
     *
     * <p>滑块/预设变化时 {@link #capOf} 会重新算 —— 前提是
     * {@code ThemeSkinHook.dropCaches()} 把搜狗缓存清掉、位图重新走一遍 H41。
     */
    public static final class Cap {
        public final int kind;
        public final int color;
        public final int kbBg;
        public final boolean dark;
        public final boolean glass;
        public final boolean rim;
        /** 圆角占键高的比例（已含滑块 / 预设区间）。 */
        public final float ratio;
        /**
         * 画键帽之前要不要先把整格铺成键盘底色。
         *
         * <p>v2.3.0：这条只在「位图那条路」（H41/H71）上用 —— 那边我们拿到的是搜狗的一张图，
         * 底下可能还垫着它自己的方形实心底，清不掉就得盖。
         * 而「Drawable 那条路」（H72）里我们替换的<b>就是</b>这一格的背景本身，
         * 底下是搜狗的键盘底图，再铺一层近似色只会把背景画成一块方块（深色模式那个淡灰方块）。
         */
        public final boolean under;

        Cap(int kind, int color, int kbBg, boolean dark, boolean glass, boolean rim,
                float ratio, boolean under) {
            this.kind = kind;
            this.color = color;
            this.kbBg = kbBg;
            this.dark = dark;
            this.glass = glass;
            this.rim = rim;
            this.ratio = ratio;
            this.under = under;
        }

        /** 复制一份、改成「不铺底色」—— Drawable 那条路要用。 */
        public Cap noUnder() {
            if (!under) return this;
            return new Cap(kind, color, kbBg, dark, glass, rim, ratio, false);
        }
    }

    /** 把「这张位图该长什么样」打包成落屏参数。 */
    public static Cap capOf(int kind, int color, SkinPreset p) {
        if (p == null) return null;
        try {
            return new Cap(kind, color, p.kbBg, isDarkPreset(p),
                    ModuleConfig.glassEnabled(), ModuleConfig.edgeHighlightEnabled(),
                    targetRatio(p, kind), coverNeeded());
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 在真实画布上画一颗键（落屏路径）。
     *
     * <p>{@code dst} 是搜狗自己算出来的落点，所以九宫格几何、行列偏移、横竖屏
     * 全部照旧，只把「长什么样」换成我们的。
     *
     * <p><b>为什么带缓存</b>：边缘高光是 4 层 {@link BlurMaskFilter} 内阴影，
     * 一颗键现画要几十条 path + 4 次模糊。键盘一屏三四十颗键、每次重绘都要来一遍，
     * 直接掉帧。所以按「尺寸 + 参数」渲一次到小位图，之后就是一次 drawBitmap
     * —— 每颗键只付一次模糊的代价，滑块拖到哪档都是新的 key、各渲一次。
     *
     * @param alpha 原始绘制用的透明度（按压缩放动画会用到），255 = 不透明
     */
    public static void drawCapOn(Canvas canvas, RectF dst, Cap cap, int alpha) {
        if (canvas == null || dst == null || cap == null) return;
        float w = dst.width();
        float h = dst.height();
        if (w < 6f || h < 6f) return;

        int iw = Math.max(1, Math.round(w));
        int ih = Math.max(1, Math.round(h));
        Bitmap cached = capBitmap(iw, ih, cap);
        if (cached != null) {
            Paint bp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
            bp.setAlpha(alpha);
            // 注意：这张缓存图不在 H71 的登记表里，所以不会再次被拦截，不会递归。
            canvas.drawBitmap(cached, null, dst, bp);
        } else {
            renderCap(canvas, dst, cap, alpha);
        }

        if (cap.kind == KIND_KEY) {
            float gap = Math.max(1f, h * 0.02f);
            float radius = Math.max(2f, Math.min((h - gap * 2f) * cap.ratio,
                    Math.min(w - gap * 2f, h - gap * 2f) * RADIUS_CAP));
            sLastRadius = String.format(Locale.US, "%.2f/%dpx/%dx%d(实绘)", cap.ratio,
                    Math.round(radius), iw, ih);
        }
    }

    /** 键帽渲染缓存：key = 尺寸 + 全部绘制参数。上限按张数算，键帽位图很小，不会撑爆内存。 */
    private static final int CAP_CACHE_MAX = 48;
    private static final LinkedHashMap<String, Bitmap> CAP_CACHE =
            new LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
                    if (size() <= CAP_CACHE_MAX) return false;
                    Bitmap b = eldest.getValue();
                    if (b != null && !b.isRecycled()) b.recycle();
                    return true;
                }
            };

    private static String capKey(int w, int h, Cap cap) {
        return w + "x" + h + "|" + cap.kind
                + "|" + Integer.toHexString(cap.color) + "|" + Integer.toHexString(cap.kbBg)
                + "|" + (cap.dark ? 1 : 0) + (cap.glass ? 1 : 0) + (cap.rim ? 1 : 0)
                + (cap.under ? "u" : "")
                + "|" + Math.round(cap.ratio * 100f);
    }

    private static Bitmap capBitmap(int w, int h, Cap cap) {
        String key = capKey(w, h, cap);
        try {
            synchronized (CAP_CACHE) {
                Bitmap hit = CAP_CACHE.get(key);
                if (hit != null && !hit.isRecycled()) return hit;
            }
        } catch (Throwable ignored) {
            // 查缓存失败就走现画，不影响正确性
        }
        Bitmap out = null;
        try {
            out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            renderCap(new Canvas(out), new RectF(0f, 0f, w, h), cap, 255);
        } catch (Throwable t) {
            if (out != null && !out.isRecycled()) out.recycle();
            return null;
        }
        try {
            synchronized (CAP_CACHE) {
                CAP_CACHE.put(key, out);
            }
        } catch (Throwable ignored) {
            // 放不进缓存也没关系，图还是能用的
        }
        return out;
    }

    /** 换肤 / 换圆角后调用：缓存里的是旧参数渲出来的，必须整批丢掉。 */
    public static void clearCapCache() {
        try {
            synchronized (CAP_CACHE) {
                for (Bitmap b : CAP_CACHE.values()) {
                    if (b != null && !b.isRecycled()) b.recycle();
                }
                CAP_CACHE.clear();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 真正画一颗键（无缓存版本，也被 {@link #capBitmap} 用来渲染缓存图）。 */
    private static void renderCap(Canvas canvas, RectF dst, Cap cap, int alpha) {
        float w = dst.width();
        float h = dst.height();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);

        // ① 这一格要不要先铺一层键盘底色 —— 只在 patchIni 清不掉按键段 BG_COLOR 时才铺。
        //
        //    v2.2.0 是无条件铺的，于是深色模式下每颗键都比背景略浅一块：
        //    键盘底是图片、p.kbBg 是近似纯色，两层叠出来的差值就是那个「淡灰色小方块」。
        //    现在不铺的时候，圆角外直接透出搜狗自己的键盘底（图），既没有方块，
        //    液态玻璃也是真的"透过去"，而不是透在一层我们自己涂的假底色上。
        if (coverNeeded() && cap.under) {
            final int under = cap.glass ? withAlphaScale(cap.kbBg, glassAlpha(KIND_FILL)) : cap.kbBg;
            p.setColor(applyAlpha(under, alpha));
            canvas.drawRect(dst, p);
        }

        // ② 键帽本体：留出与 synth() 相同的键缝，再按比例画圆角。
        float gap = Math.max(1f, h * 0.02f);
        RectF rect = new RectF(dst.left + gap, dst.top + gap, dst.right - gap, dst.bottom - gap);
        if (rect.width() < 2f || rect.height() < 2f) rect = new RectF(dst);
        float shortSide = Math.min(rect.width(), rect.height());
        float radius = Math.max(2f, Math.min(rect.height() * cap.ratio, shortSide * RADIUS_CAP));

        if (cap.glass) {
            drawGlassSurface(canvas, rect, radius,
                    applyAlpha(withAlphaScale(cap.color, glassAlpha(cap.kind)), alpha), cap.dark);
        } else {
            p.setColor(applyAlpha(cap.color, alpha));
            canvas.drawRoundRect(rect, radius, radius, p);
        }

        // ③ 边缘高光（和位图路径同一套 4 层 inset 阴影）
        if (cap.rim) {
            drawRim(canvas, rect, radius, cap.dark);
        }
    }

    /** 把 0~255 的外部透明度乘到颜色上（落屏时按压缩放动画要用）。 */
    private static int applyAlpha(int argb, int alpha) {
        if (alpha >= 255) return argb;
        if (alpha <= 0) alpha = 0;
        int a = (int) (((argb >>> 24) & 0xFF) * (alpha / 255f));
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    // ── 4. 边缘高光 + 液态玻璃（移植自 WeType UI Enhanced）────────────────────
    //
    // 参照实现：
    //   graphics/WeTypeBloomStrokeDrawable.kt  ← 边缘高光（4 层 CSS inset box-shadow）
    //   graphics/WeTypeHyperMaterial.kt        ← 液态玻璃（澎湃 View 隐藏 API）
    //
    // 为什么这里能照搬：那 4 层阴影就是纯 Canvas 的"画一圈内阴影"，跟 Compose / 视图树
    // 无关；我们重绘键帽时正好有一块空的 Bitmap Canvas，位置、圆角都已知，
    // 所以数值可以一比一搬过来（只有单位要从 dp 换成位图 px，见 dpScale）。

    /** 一层 CSS 风格的 inset box-shadow；长度单位是 dp。 */
    private static final class InsetShadow {
        final float ox;
        final float oy;
        final float blur;
        final float spread;
        final int color;

        InsetShadow(float ox, float oy, float blur, float spread, int color) {
            this.ox = ox;
            this.oy = oy;
            this.blur = blur;
            this.spread = spread;
            this.color = color;
        }
    }

    /**
     * 与 {@code WeTypeBloomStrokeDrawable.BOX_SHADOWS} 完全一致（顺序 = CSS 层叠顺序，先列的先画、盖在上面）。
     *
     * <pre>
     *   inset 2px 2px 0.25px -1.5px rgba(255,255,255,0.70)   ← 上沿那道 1px 亮线
     *   inset 1px 1px 2px   0     rgba(255,255,255,0.80)     ← 左上方向的柔光
     *   inset -1px -1px 2px 0     rgba(255,255,255,0.60)     ← 右下方向的柔光（更弱）
     *   inset 0 0 10px 0.5px      rgba(0,0,0,0.09)           ← 一圈极淡的内暗角，玻璃的"厚度"
     * </pre>
     */
    private static final InsetShadow[] RIM_SHADOWS = {
            new InsetShadow(2f, 2f, 0.25f, -1.5f, 0xB3FFFFFF),
            new InsetShadow(1f, 1f, 2f, 0f, 0xCCFFFFFF),
            new InsetShadow(-1f, -1f, 2f, 0f, 0x99FFFFFF),
            new InsetShadow(0f, 0f, 10f, 0.5f, 0x18000000),
    };

    /** 夜间键帽本来就暗，同样的白色高光会过曝；WeType 用 0.25 倍压下来，照抄。 */
    private static final float RIM_DARK_SCALE = 0.25f;

    /**
     * BlurMaskFilter 的 radius 与 CSS blur 的 sigma 换算。
     *
     * <p>Android 的 BlurMaskFilter 半径对应的 sigma ≈ 0.5773×radius，而 CSS blur 的 sigma = blur/2，
     * 两式相等得 radius ≈ 0.866×blur —— 与 WeType 的 {@code CSS_BLUR_TO_MASK_RADIUS} 同一个数。
     */
    private static final float CSS_BLUR_TO_MASK_RADIUS = 0.8660f;

    /**
     * 澎湃背景模糊是否真的挂上了（由 {@code HyperGlassHook} 回写）。
     *
     * <p>**只用于诊断**（状态卡 {@code blur=}）。玻璃透明度不读它 ——
     * 位图解码与 View attach 的先后顺序不保证，见 {@link #glassAlpha(int)} 的说明。
     */
    private static volatile boolean sBlurReady;

    public static void setBlurReady(boolean ready) {
        sBlurReady = ready;
    }

    public static boolean blurReady() {
        return sBlurReady;
    }

    /**
     * 位图 px ↔ dp 的换算比例。
     *
     * <p>WeType 的高光长度写死 dp（2dp 亮线、2dp 柔光、10dp 内暗角）。我们的画布是位图，
     * 真机字母键位图约 96×132px 对应 44dp，即 3.0 px/dp；按 {@code 键高/44} 自适应换算，
     * 键帽被九宫格拉伸时高光的"视觉厚度"也保持一致。
     */
    private static float dpScale(RectF rect) {
        return Math.max(1.6f, Math.min(4.2f, rect.height() / 44f));
    }

    /** 键帽是否为深色（夜间）—— 决定高光要不要乘 0.25。 */
    private static boolean isDarkPreset(SkinPreset p) {
        return luminance(p.kbBg) < 110;
    }

    /**
     * 当前是否夜间（供 {@code HyperGlassHook} 选玻璃 tint 用）。
     *
     * <p>用系统 uiMode 而不是猜配色：玻璃的 blend colors 与深浅色是绑定的，
     * 猜错会出现"深色键盘配浅色 tint"这种发灰的组合。
     */
    public static boolean isDarkNow(Context ctx) {
        if (ctx == null) return false;
        try {
            return (ctx.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 画边缘高光：把 4 层 inset 阴影依次画进圆角矩形内部。
     *
     * <p>每层阴影的几何与 CSS 一致：内阴影 = 「整块」减去「向内缩 spread、再偏移 (ox,oy) 的洞」，
     * 只保留这圈边缘带，因而阴影永远贴着内侧，不会溢出键帽轮廓。
     */
    private static void drawRim(Canvas canvas, RectF rect, float radius, boolean dark) {
        float dp = dpScale(rect);
        Path content = new Path();
        content.addRoundRect(rect, radius, radius, Path.Direction.CW);
        float alphaScale = dark ? RIM_DARK_SCALE : 1f;

        int save = canvas.save();
        canvas.clipPath(content);
        for (InsetShadow s : RIM_SHADOWS) {
            int color = withAlphaScale(s.color, alphaScale);
            if (Color_alpha(color) == 0) continue;

            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            float maskRadius = s.blur * dp * CSS_BLUR_TO_MASK_RADIUS;
            if (maskRadius > 0.05f) {
                paint.setMaskFilter(new BlurMaskFilter(maskRadius, BlurMaskFilter.Blur.NORMAL));
            }
            Path band = insetBand(rect, radius, s.ox * dp, s.oy * dp, s.spread * dp);
            if (band != null) {
                canvas.drawPath(band, paint);
            }
        }
        canvas.restoreToCount(save);
    }

    /**
     * 构造一层内阴影的填充区域：整块矩形 **减去** 洞（缩小 spread 并偏移后的圆角矩形）。
     *
     * <p>与 WeType 的 {@code buildInsetShadowPath} 同一套做法。返回 null 表示这层不必画。
     */
    private static Path insetBand(RectF rect, float radius, float ox, float oy, float spread) {
        RectF hole = new RectF(rect);
        hole.inset(spread, spread);
        if (hole.width() <= 0f || hole.height() <= 0f) {
            // 洞被挤没了：整块都在阴影里，直接铺满内容区（外层的 clip 会收住轮廓）
            Path all = new Path();
            all.addRoundRect(rect, radius, radius, Path.Direction.CW);
            return all;
        }
        float holeRadius = Math.max(0f, radius - spread);
        Path holePath = new Path();
        holePath.addRoundRect(hole, holeRadius, holeRadius, Path.Direction.CW);
        holePath.offset(ox, oy);

        // 覆盖矩形要足够大：偏移 + 模糊都可能把阴影带推到内容区之外，外层 clip 会裁掉多余部分
        float guard = Math.abs(ox) + Math.abs(oy) + 64f;
        RectF cover = new RectF(rect);
        cover.inset(-guard, -guard);
        Path fill = new Path();
        fill.addRect(cover, Path.Direction.CW);
        if (!fill.op(holePath, Path.Op.DIFFERENCE)) {
            return null;
        }
        return fill;
    }

    /**
     * 把颜色 alpha 乘一个系数（0~1）。玻璃面半透明、夜间高光衰减都走这里。
     *
     * <p>注意只动 alpha，RGB 一律保留 —— 改配色不是这一步的职责。
     */
    private static int withAlphaScale(int argb, float scale) {
        int a = Math.round(Color_alpha(argb) * Math.max(0f, Math.min(1f, scale)));
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    /**
     * 画"液态玻璃"键帽：竖向 tint 渐变 + 半透明。
     *
     * <p>WeType 的玻璃是交给澎湃合成器实时取背景的，我们这边没有视图层可挂，所以做同一件事的
     * 另一半：把面做成**半透明 + 上亮下暗的 tint**（对应它 {@code setMiBackgroundBlendColors}
     * 的三段同色系叠加），下面的背景（有 {@code HyperGlassHook} 时是实时模糊）就会透上来。
     */
    private static void drawGlassSurface(Canvas canvas, RectF rect, float radius, int color,
                                         boolean dark) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(rect.left, rect.top, rect.left, rect.bottom,
                lighten(color, dark ? 0.10f : 0.07f),
                darken(color, dark ? 0.04f : 0.07f), Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, radius, radius, paint);
        paint.setShader(null);
    }

    /**
     * 玻璃面的透明度（**固定值，不依赖模糊是否挂上**）。
     *
     * <p>为什么不按 {@link #sBlurReady} 分两档：皮肤位图是在"键盘第一次搭起来"时解码的，
     * 而模糊要等 View attach 之后才能开 —— 两者顺序不保证。若透明度取决于模糊状态，
     * 第一次弹键盘必然是"兜底档"，之后又不会重解码，用户看到的就是"开了没反应"。
     * 固定透明度以后，开了就是开了，与先后顺序无关。
     *
     * <p>数值取向：键帽 0.74、底色 0.66 —— 比 WeType 的 50% tint 保守一点（我们的字色是
     * 搜狗按 ini 画的，透太多会影响正文对比度），但足以让下面的模糊背景透上来。
     */
    private static float glassAlpha(int kind) {
        switch (kind) {
            case KIND_FILL:
                return 0.66f;
            case KIND_POPUP:
                return 0.88f;
            case KIND_ACCENT:
                return 0.82f;
            case KIND_KEY:
            default:
                return 0.74f;
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
     * <p><b>v2.1.5 起不再参与半径计算</b> —— 保留仅作参照。原因：{@code maxProbe} 上限是 24px，
     * 搜狗键帽图的真实圆角都超过这个数，于是探测值恒等于 24，把 {@code radius} 锁死在 24，
     * 导致 v2.1.3 / v2.1.4 调整 {@code radiusRatio} 完全没有效果。要重新启用，必须先
     * 把探测上限改成 {@code 键高/2} 并接受"原图更圆时整排会跟着变"。
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

    /**
     * 把「图里原本画上去的字形」抠出来，<b>保留原色</b>（v2.2.2）。
     *
     * <p>和 {@link #glyphMask} 的区别：glyphMask 取的是「比中位数更亮」的像素 ——
     * 那是给 ⌫ / ⏎ 这种「深底亮图标」准备的、最后还要重新染色（SRC_IN）的掩膜。
     * 九宫格键帽上的数字是**深字压浅底**，方向正好相反，而且不能染色（染了就跟底色撞）。
     *
     * <p>所以这里用「偏离中位数」当判据 —— 跟 {@link #hasInk} 完全一致：
     * 取亮度与中位数差 ≥ 40 的像素，保留它们原本的 RGB 和 alpha。
     * 这样不管是浅底深字还是深底浅字，都能抠对。
     *
     * <p>返回 null 表示抠不出字形（说明这张图其实是干净的键帽），调用方按"没字形"处理。
     */
    private static Bitmap inkMask(Bitmap src) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= 0 || h <= 0) return null;
        int n = w * h;
        int[] px = new int[n];
        src.getPixels(px, 0, w, 0, 0, w, h);

        int[] hist = new int[32];
        int opaque = 0;
        for (int c : px) {
            if (((c >>> 24) & 0xFF) <= 24) continue;
            hist[Math.min(31, luminance(c) >> 3)]++;
            opaque++;
        }
        if (opaque < 32) return null;
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
            int a = (c >>> 24) & 0xFF;
            if (a <= 24) continue;
            if (Math.abs(luminance(c) - median) >= 40) {
                out[i] = c;          // 原色保留
                ink++;
            }
        }
        // 少于 8 个像素就当没有字形 —— 免得把噪点/抗锯齿边当成字叠上去
        if (ink < 8) return null;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        b.setPixels(out, 0, w, 0, 0, w, h);
        return b;
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
