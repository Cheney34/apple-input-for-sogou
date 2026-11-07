import io
import os

BASE = r"C:\Users\KOUFIK\Documents\deepseek-harness\default-workspace\apple-input-for-sogou\lsposed\app\src\main\java\com\chiron\iosemoji\skin"
IP = os.path.join(BASE, "IosSkin.java")
SP = os.path.join(BASE, "SkinPreset.java")


def load(p):
    t = io.open(p, "r", encoding="utf-8", newline="").read()
    nl = "\r\n" if "\r\n" in t else "\n"
    return t, nl


def lines_of(t):
    return t.replace("\r\n", "\n").split("\n")


def find(L, needle, start=0, exact=True):
    for i in range(start, len(L)):
        s = L[i].strip()
        if (s == needle) if exact else (needle in s):
            return i
    return -1


def apply(path, ins, must):
    t, nl = load(path)
    L = lines_of(t)
    for tag, pred in must:
        if find(L, pred) < 0:
            raise SystemExit("MISS anchor: " + tag)
    for idx in sorted(ins.keys(), reverse=True):
        L[idx:idx] = ins[idx]
    io.open(path, "w", encoding="utf-8", newline="").write(nl.join(L))
    print("WROTE", os.path.basename(path), "insertions:", len(ins))


# ═════════════ IosSkin ═════════════
t, nl = load(IP)
L = lines_of(t)
ins = {}

if find(L, "import android.graphics.PorterDuff;") < 0:
    i = find(L, "import android.graphics.Paint;")
    ins[i + 1] = ["import android.graphics.PorterDuff;", "import android.graphics.PorterDuffColorFilter;"]

i = find(L, "public static final int KIND_POPUP = 3;")
ins[i + 1] = '''    /**
     * 强调键：回车 / 发送 / 确认键 —— 铺 accent 圆角底，再把键上原有字形抠成对比色盖回去
     * （微信风格：发送键是绿的）。
     *
     * <p>为什么单独一类：isProtected 原本保护 enter / return，H41 直接跳过就等于这块永远是
     * 搜狗原来的灰；但也不能把 key / func 那层配色推给它们 —— iOS / Gboard / 莫奈的发送键
     * 是跟功能键同色的，不该变绿。所以只有 accentEnter=true 的预设才走这一类。
     */
    public static final int KIND_ACCENT = 4;'''.split("\n")

i = find(L, "public static int ensureContrast(int fg, int bg, double min) {")
ins[i] = '''    /** 被刷成强调色的回车 / 发送键数量（诊断用）。 */
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
'''.split("\n")

i = find(L, "if (isBgImage(n)) return KIND_FILL;")
ins[i + 1] = '''
        // 回车 / 发送 / 确认键（仅 accentEnter 预设命中）：必须排在 isProtected 之前，
        // 否则 enter / return 会被保护名单吃掉，永远是搜狗原来的灰。
        if (isAccentKey(n)) return KIND_ACCENT;'''.split("\n")

i = find(L, "case KIND_POPUP:")
if L[i + 1].strip() != "return p.popup;":
    raise SystemExit("MISS popup body")
ins[i + 2] = '''            case KIND_ACCENT:
                // 只有 accentEnter 预设（目前只有微信）才给颜色；返回 0 时调用方
                // （ThemeSkinHook）会跳过，等于回到「动作键完全不动」的旧行为。
                if (!p.accentEnter) return 0;
                return pressed ? darken(p.accent, 0.12f) : p.accent;'''.split("\n")

i = find(L, 'changed |= putColor(sections, "CloudView", "TEXT_HIGHLIGHT_STYLE", accentOnBar);')
j = find(L, "return changed;", i)
ins[j] = '''        // 回车 / 发送段：微信预设把「发送」刷成强调色（iOS / Google / 莫奈 / 搜狗经典保持原样）。
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
'''.split("\n")

i = find(L, "Rect box = opaqueBounds(src);")
j = find(L, "if (box == null || box.width() <= 0 || box.height() <= 0) {", i)
k = find(L, "}", j)  # closing brace of the null guard
ins[k + 1] = '''
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
            }'''.split("\n")

i = find(L, "private static int luminance(int argb) {")
ins[i] = '''    /**
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
'''.split("\n")

apply(IP, ins, [("KIND_POPUP", "public static final int KIND_POPUP = 3;")])

# ═════════════ SkinPreset ═════════════
t, nl = load(SP)
L = lines_of(t)
ins = {}

i = find(L, "public final boolean gradient;", exact=False)
if i < 0:
    raise SystemExit("MISS gradient field")
ins[i + 1] = '''
    /**
     * 回车 / 发送键是否也刷成 accent 强调色。
     *
     * <p>默认 false：只有微信风格是 true（参考微信键盘「发送键是绿的」）。
     * iOS / Google / 莫奈 / 搜狗经典的发送键跟功能键同色，不上强调色。
     * 关闭时 IosSkin.colorOfKind 对 KIND_ACCENT 返回 0，调用方跳过，
     * 等于回到「动作键完全不动」的旧行为。
     */
    public final boolean accentEnter;'''.split("\n")

i = find(L, "SkinPreset(String id, int kbBg", exact=False)
if i < 0:
    raise SystemExit("MISS ctor")
# signature 4 lines: i .. i+3 ends with ") {"
assert L[i + 3].strip().endswith(") {"), L[i + 3]
sig = [l for l in L[i:i + 4]]
ins[i + 4] = ['        this(id, kbBg, key, keyPressed, func, funcPressed, popup, barBg, text, textPressed,',
              '                accent, radiusRatio, shadowColor, shadowRadius, shadowDy, edgeLine, gradient, false);',
              '    }',
              '',
              '    SkinPreset(String id, int kbBg, int key, int keyPressed, int func, int funcPressed,',
              '                int popup, int barBg, int text, int textPressed, int accent,',
              '                float radiusRatio, int shadowColor, float shadowRadius, float shadowDy,',
              '                boolean edgeLine, boolean gradient, boolean accentEnter) {']

j = find(L, "this.gradient = gradient;")
ins[j + 1] = ["        this.accentEnter = accentEnter;"]

for idx in sorted(ins.keys(), reverse=True):
    L[idx:idx] = ins[idx]

# wechat factories → accentEnter true
txt = "\n".join(L)
for name in ("wechatLight", "wechatDark"):
    a = txt.index("private static SkinPreset %s()" % name)
    b = txt.index("false, false);", a)
    txt = txt[:b] + "false, false, true);" + txt[b + len("false, false);"):]
    print("ok", name)
io.open(SP, "w", encoding="utf-8", newline="").write(txt.replace("\n", nl))
print("WROTE SkinPreset.java")
