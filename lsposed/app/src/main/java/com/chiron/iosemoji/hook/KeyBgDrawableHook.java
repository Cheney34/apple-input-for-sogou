package com.chiron.iosemoji.hook;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import com.chiron.iosemoji.config.ModuleConfig;
import com.chiron.iosemoji.skin.IosSkin;
import com.chiron.iosemoji.skin.SkinPreset;

import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * H72：键帽背景的 <b>Drawable</b> 层接管（v2.3.0）。
 *
 * <h3>为什么前八版都没生效</h3>
 * 搜狗皮肤里的键帽背景<b>不是一个自己画的 Canvas 图层</b>，而是
 * "一张 png → 解析成 Drawable → 当成某个 View/元素的背景"：
 *
 * <pre>
 * images_config.ini   [key_png]  IMAGE=key.png,28,30,38,40   TYPE=1
 *                          ↓  com.sogou.theme.parse.frame.i#R()
 *                          ↓  com.sogou.lib.common.picture.bitmap.a.p(File)   ← 从磁盘读，不走 assets
 *                     com.sogou.theme.utils.e#f(Context, Bitmap, 名字, 内边距)
 *                          ↓  NinePatchDrawable 包一层
 *                com.sogou.theme.data.drawable.l / h   ← draw(Canvas) 里被画出来
 * </pre>
 *
 * <p>H41 挂的是 {@code com.sogou.theme.common.j#f} —— 那是 <b>assets</b> 那条读图路
 * （{@code context.getAssets().open(str)}）。皮肤一旦落到磁盘上（首次启动就会解包），
 * 走的是 {@code parse.frame.i} 里那条 {@code bitmap.a.p(File)}，
 * <b>H41 一次都不会被调用</b>，H71 自然也没有东西可画 —— 于是不论怎么调半径、
 * 加玻璃、加高光，屏幕上一个像素都不会变。这解释了"改了九版都没变化"。
 *
 * <h3>现在的做法</h3>
 * 在 <b>Drawable 工厂</b> {@code utils.e#f/#c} 出口处登记「这张 drawable → 它是什么键」，
 * 再挂 {@code drawable.l#draw / h#draw}：命中登记表就整块换成我们自己画的圆角键帽
 * （圆角 / 液态玻璃 / 边缘高光都用 {@link IosSkin#drawCapOn}），然后跳过原始绘制。
 *
 * <p>键的矩形来自 {@code getBounds()} —— 那是搜狗自己算出来的位置，
 * 所以九宫格、26 键、横竖屏、空格/回车这些特殊宽度全部照旧，只有"长什么样"是我们的。
 *
 * <p>九宫格之所以也一起好了：它用的 {@code KeyBg_Default → key.png} 和
 * 26 键用的 {@code KeyBg_Default_Latin → key_qwerty.png} 是同一套机制、同一个工厂，
 * 以前只是名字没进白名单；这次判断靠的是「名字 + 位图形状」，两条都不挑布局。
 */
public final class KeyBgDrawableHook {

    /** drawable 实例 → 它是哪颗键。用 IdentityHashMap：Drawable 没重写 equals。 */
    private static final Map<Object, Entry> CAPS = new IdentityHashMap<>();

    private static final AtomicInteger TAKEN = new AtomicInteger();
    private static final AtomicInteger DRAWN = new AtomicInteger();
    private static final AtomicInteger FAILED = new AtomicInteger();
    private static volatile String sSample = "-";
    private static volatile String sClass = "-";

    private KeyBgDrawableHook() {
    }

    /** 一张键背景 drawable 的登记信息。Cap 按「配置代」懒算，拖滑块时自动换新的。 */
    private static final class Entry {
        final String name;
        final int kind;
        final int w;
        final int h;
        IosSkin.Cap cap;
        int gen = Integer.MIN_VALUE;

        Entry(String name, int kind, int w, int h) {
            this.name = name;
            this.kind = kind;
            this.w = w;
            this.h = h;
        }
    }

    // ── 安装 ─────────────────────────────────────────────────────────────

    public static void install(ClassLoader cl) {
        int factory = hookFactory(cl);
        int draw = 0;
        draw += hookDraw(cl, "com.sogou.theme.data.drawable.l");
        draw += hookDraw(cl, "com.sogou.theme.data.drawable.h");
        HookRegistry.log("H72 drawable: factory=" + factory + " draw=" + draw);
        if (factory == 0) {
            HookRegistry.log("H72 未命中按钮背景工厂 —— 该渠道可能还是 assets 读图（H41 那条路）");
        }
    }

    /** 挂 drawable 工厂 {@code com.sogou.theme.utils.e#f / #c}。 */
    private static int hookFactory(ClassLoader cl) {
        int n = 0;
        try {
            Class<?> util = XposedHelpers.findClass("com.sogou.theme.utils.e", cl);
            for (String name : new String[]{"f", "c"}) {
                // 用 hookAllMethods（只按名字）：各定制版参数个数不一样，
                // 写死签名会在另一版上静默失配。真正的过滤在 onFactory 里做。
                try {
                    XposedBridge.hookAllMethods(util, name, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            onFactory(param);
                        }
                    });
                    n++;
                } catch (Throwable ignored) {
                    // 这个名字在这一版不存在
                }
            }
        } catch (Throwable t) {
            HookRegistry.log("H72 factory class missing: " + t);
        }
        return n;
    }

    /** 挂 drawable 的 {@code draw(Canvas)}（{@code l} = 九宫格切片，{@code h} = 普通位图）。 */
    private static int hookDraw(ClassLoader cl, String clsName) {
        try {
            Class<?> cls = XposedHelpers.findClass(clsName, cl);
            XposedBridge.hookAllMethods(cls, "draw", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    onDraw(param);
                }
            });
            if ("-".equals(sClass)) sClass = clsName.substring(clsName.lastIndexOf('.') + 1);
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    // ── 工厂出口：登记「这张 drawable 是键帽」 ────────────────────────────

    private static void onFactory(XC_MethodHook.MethodHookParam param) {
        try {
            Object res = param.getResult();
            if (!(res instanceof Drawable)) return;
            Object[] a = param.args;
            if (a == null || a.length < 3) return;
            if (!(a[1] instanceof Bitmap) || !(a[2] instanceof String)) return;
            Bitmap src = (Bitmap) a[1];
            String name = (String) a[2];
            if (src.isRecycled()) return;

            int kind = IosSkin.kindOf(name);
            // 只接管「键帽」这一类。候选栏底、键盘底、图标、气泡一律不动：
            //   它们是整条/整块的图，套上圆角反而把字和图标切坏。
            if (kind != IosSkin.KIND_KEY) return;
            if (!looksLikeKeyCap(src, name)) return;
            // 字烧在图里的键不碰：这类图（少数第三方皮肤会做「一颗键一张带字母的图」）
            // 一旦被换成纯色圆角，字母数字就没了。默认皮肤不存在这种情况 —— 
            // 9 键和 26 键的 key.png / key_qwerty.png 都是同一张空的键面，
            // 数字和 ABC 是另画的 FG_STYLE，所以这条闸门不会挡住九宫格。
            if (IosSkin.hasBakedGlyph(src)) return;
            if (!ModuleConfig.themeSkinEnabled()) return;
            // v2.4.0：盘上重绘（H73）已经把这张键面换成 iOS 键帽了。
            // 这里再接管一次就是"玻璃叠玻璃"，所以让它先。
            if (SkinArtRepaint.active()) return;

            SkinPreset p = ThemeSkinHook.presetForName(name);
            if (p == null) return;
            if (IosSkin.colorOfKind(name, kind, p) == 0) return;

            synchronized (CAPS) {
                CAPS.put(res, new Entry(name, kind, src.getWidth(), src.getHeight()));
            }
            int n = TAKEN.incrementAndGet();
            if ("-".equals(sSample)) {
                sSample = name + "@" + src.getWidth() + "x" + src.getHeight();
            }
            if (n <= 8) {
                HookRegistry.log("H72 key bg taken: " + name + " "
                        + src.getWidth() + "x" + src.getHeight() + " (total " + n + ")");
            }
        } catch (Throwable t) {
            FAILED.incrementAndGet();
        }
    }

    /**
     * 位图形状像不像键帽。
     *
     * <p>名字已经先过了一遍 {@link IosSkin#kindOf}，这里再用形状挡一层 ——
     * 因为 {@code key_grid.png}（九宫格切换图标，30×30 满不透明）、
     * {@code key_grid_topline.png}（6×1 的分隔线）名字都以 key 开头，
     * 但把它们画成圆角方块就是事故。
     *
     * <p>判据：短边 ≥ 32px（图标都小）+ 高/宽 ≥ 1.15（键帽图本身是竖长的，
     * 60×90 / 51×80 都是 1.5 左右；图标是正方形或多条横杠）。
     */
    private static boolean looksLikeKeyCap(Bitmap src, String name) {
        if (src == null || name == null) return false;
        int w = src.getWidth();
        int h = src.getHeight();
        if (w < 32 || h < 32) return false;
        if ((float) h / w < 1.15f) return false;
        String n = name.toLowerCase(Locale.US);
        return !n.contains("grid") && !n.contains("topline") && !n.contains("icon");
    }

    // ── 绘制：命中登记表就整块换成我们画的键帽 ────────────────────────────

    private static void onDraw(XC_MethodHook.MethodHookParam param) {
        try {
            if (CAPS.isEmpty()) return;
            Object self = param.thisObject;
            Entry e;
            synchronized (CAPS) {
                e = CAPS.get(self);
            }
            if (e == null) return;
            Object cnv = param.args[0];
            if (!(cnv instanceof Canvas)) return;

            Rect b = null;
            Object bounds = XposedHelpers.callMethod(self, "getBounds");
            if (bounds instanceof Rect) b = (Rect) bounds;
            if (b == null || b.width() < 8 || b.height() < 8) return;

            IosSkin.Cap cap = cap(e);
            if (cap == null) return;

            int alpha = 255;
            try {
                Object a = XposedHelpers.callMethod(self, "getAlpha");
                if (a instanceof Integer) alpha = (Integer) a;
            } catch (Throwable ignored) {
                // getAlpha 反射失败就按不透明画
            }
            if (alpha <= 4) return;

            IosSkin.drawCapOn((Canvas) cnv, new RectF(b), cap, alpha);
            param.setResult(null);
            DRAWN.incrementAndGet();
        } catch (Throwable t) {
            FAILED.incrementAndGet();
        }
    }

    /** 取（必要时重算）这张 drawable 的键帽参数。配置代变了就重算一次。 */
    private static IosSkin.Cap cap(Entry e) {
        int gen = ThemeSkinHook.configGen();
        if (e.cap != null && e.gen == gen) return e.cap;
        SkinPreset p = ThemeSkinHook.presetForName(e.name);
        if (p == null) return e.cap;
        int color = IosSkin.colorOfKind(e.name, e.kind, p);
        if (color == 0) return e.cap;
        IosSkin.Cap c = IosSkin.capOf(e.kind, color, p);
        if (c != null) c = c.noUnder();
        e.cap = c;
        e.gen = gen;
        return c;
    }

    // ── 诊断 ─────────────────────────────────────────────────────────────

    /**
     * 状态卡用：{@code 接管数/已画次数/首个名字@宽x高}。
     *
     * <p>接管数 0 = 一条键背景都没抓到（工厂没命中 / 名字或形状没过闸）；
     * 接管数 > 0 而已画 0 = 抓到了但 draw 没被调用（走的是别的绘制层）。
     */
    public static String state() {
        int n;
        synchronized (CAPS) {
            n = CAPS.size();
        }
        return n + "/" + DRAWN.get() + "/" + sSample + (FAILED.get() > 0 ? "!e" + FAILED.get() : "");
    }

    /** 换肤 / 换预设后：参数要重算（drawable 本身不用丢，它是一个"壳"）。 */
    public static void invalidate() {
        synchronized (CAPS) {
            for (Entry e : CAPS.values()) {
                e.gen = Integer.MIN_VALUE;
            }
        }
    }
}
