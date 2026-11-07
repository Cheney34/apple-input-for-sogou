import io
import os

BASE = r"C:\Users\KOUFIK\Documents\deepseek-harness\default-workspace\apple-input-for-sogou\lsposed\app\src\main\java\com\chiron\iosemoji\skin"
IP = os.path.join(BASE, "IosSkin.java")
SP = os.path.join(BASE, "SkinPreset.java")


def patch(path, edits):
    t = io.open(path, "r", encoding="utf-8", newline="").read()
    nl = "\r\n" if "\r\n" in t else "\n"
    for tag, old, new in edits:
        o = old.replace("\n", nl)
        n = new.replace("\n", nl)
        c = t.count(o)
        if c != 1:
            raise SystemExit("MISS %s (count=%d)" % (tag, c))
        t = t.replace(o, n)
        print("ok", tag)
    io.open(path, "w", encoding="utf-8", newline="").write(t)
    print("WROTE", os.path.basename(path))


# ─────────────────────────── IosSkin.java ───────────────────────────
imports_anchor = """import android.graphics.Paint;"""
imports_new = """import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;"""

kind_accent_anchor = '''    public static final int KIND_POPUP = 3;'''
kind_accent_new = '''    public static final int KIND_POPUP = 3;
    /**
     * 强调键：回车 / 发送 / 确认键 —— 铺 {@code preset.accent} 圆角底，再把键上原有字形
     * 抠成对比色盖回去（微信风格「发送键是绿的」）。
     *
     * <p>为什么单独一类：{@link #isProtected} 原本保护 enter / return，H41 直接跳过就等于
     * 这块永远是搜狗原来的灰；但也不能把 key / func 那一层配色直接推给它们 —— iOS / Gboard /
     * 莫奈的发送键是跟功能键同色的，不该变绿。所以只有 {@code accentEnter=true} 的预设才走
     * 这一类，其余预设不会命中，行为与改动前完全一致。
     */
    public static final int KIND_ACCENT = 4;'''

counters_anchor = '''    public static int ensureContrast(int fg, int bg, double min) {'''
counters_new = '''    /** 被刷成强调色的回车 / 发送键数量（诊断用）。 */
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

    public static int ensureContrast(int fg, int bg, double min) {'''

kindof_anchor = '''        if (isBgImage(n)) return KIND_FILL;

        if (isProtected(n)) return KIND_NONE;'''
kindof_new = '''        if (isBgImage(n)) return KIND_FILL;

        // 回车 / 发送 / 确认键（仅 accentEnter 预设命中）：必须排在 isProtected 之前，
        // 否则 enter / return 会被保护名单吃掉，永远是搜狗原来的灰。
        if (isAccentKey(n)) return KIND_ACCENT;

        if (isProtected(n)) return KIND_NONE;'''

color_anchor = '''            case KIND_POPUP:
                return p.popup;'''
color_new = '''            case KIND_POPUP:
                return p.popup;
            case KIND_ACCENT:
                // 只有 accentEnter 预设（目前只有微信）才给颜色；返回 0 时调用方
                // （ThemeSkinHook）会跳过，等于回到「动作键完全不动」的旧行为。
                if (!p.accentEnter) return 0;
                return pressed ? darken(p.accent, 0.12f) : p.accent;'''

ini_anchor = '''        changed |= putColor(sections, "CloudView", "TEXT_HIGHLIGHT_STYLE", accentOnBar);

        return changed;'''
ini_new = '''        changed |= putColor(sections, "CloudView", "TEXT_HIGHLIGHT_STYLE", accentOnBar);

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

        return changed;'''

synth_anchor = '''            Rect box = opaqueBounds(src);
            if (box == null || box.width() <= 0 || box.height() <= 0) {
                paint.setColor(color);
                canvas.drawRect(0f, 0f, w, h, paint);
                return out;
            }'''
synth_new = '''            Rect box = opaqueBounds(src);
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
                float radA = Math.max(2f, Math.min(rA.height() * p.radiusRatio,
                        Math.min(rA.width(), rA.height()) * 0.5f));
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
            }'''

helpers_anchor = '''    private static int luminance(int argb) {'''
helpers_new = '''    /**
     * 这张图是不是「回车 / 发送 / 确认」键的底图。
     *
     * <p>只看名字，并刻意排除带 {@code icon} 的那批（{@code icon_enter} 是箭头图标，
     * 平铺成色块就废了），以及语音 / 表情里也叫 {@code *_send} 的键。
     * 宁可漏改几张，也不能把图标涂了 —— 这是 alpha9 留下的规矩。
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
     * <p>做法：先统计不透明像素的亮度直方图取中位数，再以中位数为界二分 —— 比阈值亮的归
     * 「字形」（输出白色、保留自身 alpha），比阈值暗的归「底」（透明）。整张纯色（没有字）
     * 或字形像素太少时返回 null，调用方就只铺底色、不盖字。
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

    private static int luminance(int argb) {'''

patch(IP, [
    ("imports", imports_anchor, imports_new),
    ("KIND_ACCENT", kind_accent_anchor, kind_accent_new),
    ("counters", counters_anchor, counters_new),
    ("kindOf", kindof_anchor, kindof_new),
    ("colorOfKind", color_anchor, color_new),
    ("patchIni", ini_anchor, ini_new),
    ("synth", synth_anchor, synth_new),
    ("helpers", helpers_anchor, helpers_new),
])

# ─────────────────────────── SkinPreset.java ───────────────────────────
field_anchor = '''    public final boolean gradient;  // 底色是否画竖向渐变（莫奈用，氛围感）'''
field_new = '''    public final boolean gradient;  // 底色是否画竖向渐变（莫奈用，氛围感）

    /**
     * 回车 / 发送键是否也刷成 {@code accent} 强调色。
     *
     * <p>默认 false：只有**微信**风格是 true（参考微信键盘「发送键是绿的」）。
     * iOS / Google / 莫奈 / 搜狗经典 的发送键跟功能键同色，不上强调色。
     * 关闭时 {@code IosSkin.colorOfKind} 对 KIND_ACCENT 返回 0，调用方跳过，
     * 等于回到「动作键完全不动」的旧行为。
     */
    public final boolean accentEnter;'''

ctor_anchor = '''    SkinPreset(String id, int kbBg, int key, int keyPressed, int func, int funcPressed,
                int popup, int barBg, int text, int textPressed, int accent,
                float radiusRatio, int shadowColor, float shadowRadius, float shadowDy,
                boolean edgeLine, boolean gradient) {'''
ctor_new = '''    SkinPreset(String id, int kbBg, int key, int keyPressed, int func, int funcPressed,
                int popup, int barBg, int text, int textPressed, int accent,
                float radiusRatio, int shadowColor, float shadowRadius, float shadowDy,
                boolean edgeLine, boolean gradient) {
        this(id, kbBg, key, keyPressed, func, funcPressed, popup, barBg, text, textPressed,
                accent, radiusRatio, shadowColor, shadowRadius, shadowDy, edgeLine, gradient, false);
    }

    SkinPreset(String id, int kbBg, int key, int keyPressed, int func, int funcPressed,
                int popup, int barBg, int text, int textPressed, int accent,
                float radiusRatio, int shadowColor, float shadowRadius, float shadowDy,
                boolean edgeLine, boolean gradient, boolean accentEnter) {'''

assign_anchor = '''        this.gradient = gradient;'''
assign_new = '''        this.gradient = gradient;
        this.accentEnter = accentEnter;'''

patch(SP, [
    ("field", field_anchor, field_new),
    ("ctor", ctor_anchor, ctor_new),
    ("assign", assign_anchor, assign_new),
])

# wechat factories → accentEnter = true
t = io.open(SP, "r", encoding="utf-8", newline="").read()
nl = "\r\n" if "\r\n" in t else "\n"
for name in ("wechatLight", "wechatDark"):
    i = t.index("private static SkinPreset %s()" % name)
    j = t.index("false, false);", i)
    t = t[:j] + "false, false, true);" + t[j + len("false, false);"):]
    print("ok", name)
io.open(SP, "w", encoding="utf-8", newline="").write(t)
print("WROTE wechat accentEnter")
