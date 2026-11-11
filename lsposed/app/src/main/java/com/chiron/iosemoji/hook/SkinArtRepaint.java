package com.chiron.iosemoji.hook;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.chiron.iosemoji.config.ModuleConfig;
import com.chiron.iosemoji.core.HookState;
import com.chiron.iosemoji.skin.IosSkin;
import com.chiron.iosemoji.skin.SkinPreset;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * H73：把皮肤里的**空白键面 png 直接重画掉**（v2.3.1）。
 *
 * <h3>为什么这次不拦绘制的任何一层</h3>
 * v2.1.3~v2.3.0 一直在「拦绘制」：先拦 assets 解码（H41），再拦落屏 drawBitmap（H71），
 * 最后拦 Drawable.draw（H72）。九宫格始终没变化。问题不在拦得准不准，而在**拦的是哪一层
 * 本身有歧义** —— 同一个键底，可能被 Drawable.draw 画、可能被容器底板盖、可能被按下态
 * 的 selector 再画一遍。拦错一层就是整版"看不出变化"，而且没法从截图上判断到底错在哪。
 *
 * <p>这次改**输入**：皮肤目录里的 {@code res/black/key.png}、{@code res/white/key_qwerty.png}
 * 这些图，本身就是一张空键面（60×90，圆角很小、纯色）。把它们替换成 iOS 键帽再写回磁盘，
 * 搜狗之后无论走哪条路读、哪一层画、怎么切片，拿到的都是新像素。
 *
 * <h3>为什么要靠 ini 路径来定位</h3>
 * 皮肤的位图不是从 assets 直接读的，而是从解包目录读：
 * <pre>
 *   com.sogou.theme.parse.frame.i#R()
 *     → str2 = this.d.d() + 图片名        // d.d() = ".../res/black/"
 *     → new File(str2)  → bitmap.a.p(file) → BitmapFactory.decodeStream
 * </pre>
 * 而 H40 拿到的 ini 路径形如 {@code .../1080/layout/colors.ini}，
 * 它的**同级目录 {@code res}** 就是 {@code d.d()} 的父目录。所以从 ini 路径往上找
 * 一层带 {@code res/black/key.png} 的目录，就是唯一需要的信息。
 *
 * <p>找不到也没关系：{@link #installDecodeHook} 挂的是所有皮肤图共同的解码出口
 * {@code com.sogou.lib.common.picture.bitmap.a#p(File)}，在内存里做同一件事。
 * 两条路互斥（盘上成功了就不再拦解码），不会出现"玻璃叠两层"。
 */
public final class SkinArtRepaint {

    /**
     * 需要重画的图：全是**空白键面**（同一张纯色圆角小图），
     * 数字/字母是搜狗按 FG_STYLE 另外画的，所以换掉它们不会丢字。
     *
     * <p>刻意不含：{@code key_grid.png}（九宫格切换图标）、{@code key_grid_topline.png}
     * （6×1 分隔线）、{@code key_symbol_hw_full.png}（手写符号图标）——
     * 这些名字也以 key 开头，但把它们画成键帽就是事故。
     */
    private static final String[] FACES = {
            "key.png", "key_n.png", "key_p.png",
            "key_qwerty.png", "key_qwerty_p.png",
            "key_func.png", "key_func_p.png",
            "key_func_qwerty.png", "key_func_qwerty_p.png",
            "key_active.png", "key_active_p.png",
            "key_qwerty_active.png", "key_qwerty_active_p.png",
            "key_sl1.png", "key_sl2.png", "key_sl3.png", "key_us.png",
    };

    /** 画法版本：改了 {@link IosSkin#paintKeyFace} 的画法就 +1，否则旧图不会重画。 */
    private static final String ART_VERSION = "a3";

    private static final String MARKER = ".ioskeyart";

    private static final AtomicInteger OK = new AtomicInteger();
    private static final AtomicInteger SKIP = new AtomicInteger();
    private static final AtomicInteger FAIL = new AtomicInteger();
    private static volatile String sRoot = "-";
    private static volatile String sLast = "-";
    /** 上一次找到的资源目录 —— 换预设时不必再等 ini 解析，直接重画这里。 */
    private static volatile File LAST_ROOT;

    /** 已经处理过的 res 目录（绝对路径）→ 当时的签名。避免每次 ini 解析都扫磁盘。 */
    private static final Set<String> DONE = new LinkedHashSet<>();

    private SkinArtRepaint() {
    }

    /**
     * 盘上这批键面图**已经是**我们画的了吗。
     *
     * <p>不能只看「这次画了几张」：从第二次启动起，标记文件命中就直接跳过，
     * 一张都不会重画。如果 {@link #active()} 只看当次计数，它就会误判成"盘上没成功"，
     * 于是 H41 的 synth 与解码兜底又各画一遍 —— 玻璃叠两层，键面明显发亮。
     * 所以「标记命中」和「刚画完」都算生效。
     */
    private static volatile boolean sDiskArt;

    /** 盘上重绘是否已经生效。生效后解码兜底与 H72 都要让路，避免同一颗键画两遍。 */
    public static boolean active() {
        return sDiskArt;
    }

    // ── 入口 1：从 ini 路径反推皮肤资源目录 ────────────────────────────────

    /**
     * H40 每解析一个皮肤 ini 都会调这里。
     *
     * <p>只在**第一次**见到某个 res 目录时真正干活（要解码 17 张图 + 写盘）；
     * 之后同一个目录直接返回，代价可以忽略。
     */
    public static void noteIni(String iniPath) {
        try {
            if (iniPath == null || !ModuleConfig.themeSkinEnabled()) return;
            File res = statesDirOf(new File(iniPath));
            if (res == null) return;
            paintOnce(res);
        } catch (Throwable t) {
            HookRegistry.log("H73 noteIni failed: " + t);
        }
    }

    /**
     * 同一个 res 目录只处理一次。
     *
     * <p>两条入口（提前扫描 / ini 路径）可能撞在一起：{@code scanEarly} 在后台线程跑，
     * H40 在解析线程跑。加锁 + DONE 集合，保证 17 张图不会被人同时写两遍。
     */
    private static void paintOnce(File statesParent) {
        synchronized (DONE) {
            String key = statesParent.getAbsolutePath();
            if (DONE.contains(key)) return;
            DONE.add(key);
        }
        LAST_ROOT = statesParent;
        paintStates(statesParent);
    }

    /**
     * 从任意皮肤 ini 路径往上找「装着 black/ 与 white/ 的那一层」。
     *
     * <p>标准结构是 {@code <皮肤>/<密度>/res/{black,white}/key.png}，
     * 但也有版本把 res 省掉、直接 {@code <皮肤>/<密度>/{black,white}/}。
     * 两种都认，免得因为一层目录名不同就整条路失效（这正是前几版反复踩的坑）。
     */
    private static File statesDirOf(File ini) {
        File dir = ini.getAbsoluteFile().getParentFile();
        for (int i = 0; i < 5 && dir != null; i++) {
            File res = new File(dir, "res");
            if (hasState(res, "black") || hasState(res, "white")) return res;
            if (hasState(dir, "black") || hasState(dir, "white")) return dir;
            dir = dir.getParentFile();
        }
        return null;
    }

    private static boolean hasState(File base, String state) {
        return base != null && new File(new File(base, state), "key.png").isFile();
    }

    // ── 入口 2：模块启动时先扫一遍（比首个 ini 更早）──────────────────────

    /**
     * 在 {@code filesDir} 底下找皮肤资源目录，找到就先画掉。
     *
     * <p>为什么要有这一条：首次安装后搜狗可能**先解包、后解析 ini**，
     * 键盘在 ini 解析完之前就已经把图读进内存了。提前画掉最稳。
     * 扫不动也无所谓 —— {@link #noteIni} 那条路一定会再来一次。
     */
    public static void scanEarly(Context ctx) {
        try {
            if (ctx == null || !ModuleConfig.themeSkinEnabled()) return;
            File base = ctx.getFilesDir();
            if (base == null) return;
            File res = findStatesDir(base, 0);
            if (res == null) return;
            paintOnce(res);
        } catch (Throwable t) {
            HookRegistry.log("H73 scanEarly failed: " + t);
        }
    }

    /** 有界深度优先找 black/white 的上一层（最多 6 层 / 300 个目录，别在别人的目录树里乱翻）。 */
    private static File findStatesDir(File dir, int depth) {
        if (dir == null || depth > 6) return null;
        if (!dir.isDirectory()) return null;
        File res = new File(dir, "res");
        if (hasState(res, "black") || hasState(res, "white")) return res;
        if (hasState(dir, "black") || hasState(dir, "white")) return dir;
        File[] kids = dir.listFiles();
        if (kids == null || kids.length > 300) return null;
        for (File k : kids) {
            if (k == null || !k.isDirectory()) continue;
            String n = k.getName();
            if ("cache".equals(n) || "lib".equals(n) || "databases".equals(n)
                    || "shared_prefs".equals(n) || "code_cache".equals(n)) {
                continue;
            }
            File hit = findStatesDir(k, depth + 1);
            if (hit != null) return hit;
        }
        return null;
    }

    // ── 真正干活：把 {black,white} 下的键面全部重画 ────────────────────────

    private static void paintStates(File statesParent) {
        Context ctx = HookState.context();
        if (ctx == null) return;
        int total = 0;
        total += paintState(statesParent, "black", true, ctx);
        total += paintState(statesParent, "white", false, ctx);
        if (total > 0) {
            sRoot = statesParent.getAbsolutePath();
            HookRegistry.log("H73 repainted " + total + " key face(s) under " + sRoot
                    + " (" + IosSkin.lastRadius() + ")");
        }
    }

    private static int paintState(File res, String state, boolean dark, Context ctx) {
        File dir = new File(res, state);
        if (!dir.isDirectory()) return 0;
        String presetId = ModuleConfig.skinPreset();
        String sig = ART_VERSION + "|" + presetId + "|" + (dark ? "d" : "l")
                + "|" + ModuleConfig.glassEnabled()
                + "|" + ModuleConfig.edgeHighlightEnabled()
                + "|" + ModuleConfig.keyRadiusPercent();

        File marker = new File(dir, MARKER);
        String old = readString(marker);
        if (sig.equals(old)) {
            // 上一轮已经画过，图是对的 —— 但必须记住"盘上是我们的图"，
            // 否则 H41 / 解码兜底会以为没成功，再叠一层玻璃上来。
            sDiskArt = true;
            SKIP.incrementAndGet();
            return 0;
        }

        SkinPreset p = SkinPreset.of(presetId, dark, ctx);
        if (p == null) return 0;

        int n = 0;
        for (String name : FACES) {
            File f = new File(dir, name);
            if (!f.isFile()) continue;
            if (paintOne(f, p)) n++;
        }
        if (n > 0) {
            writeString(marker, sig);
            OK.addAndGet(n);
            sDiskArt = true;
            sLast = state + ":" + n;
        }
        return n;
    }

    /** 单张：解码 → 重画 → 写回。原图第一次会被备份成 {@code *.orig}。 */
    private static boolean paintOne(File f, SkinPreset p) {
        Bitmap src = null;
        try {
            src = decode(f);
            if (src == null) {
                FAIL.incrementAndGet();
                return false;
            }
            int color = colorFor(f.getName(), p);
            if (color == 0) return false;
            Bitmap art = IosSkin.paintKeyFace(src, color, p, isPressed(f.getName()));
            if (art == null) {
                FAIL.incrementAndGet();
                return false;
            }
            File bak = new File(f.getParentFile(), f.getName() + ".orig");
            if (!bak.isFile()) copy(f, bak);
            if (!writePng(f, art)) {
                FAIL.incrementAndGet();
                return false;
            }
            art.recycle();
            return true;
        } catch (Throwable t) {
            FAIL.incrementAndGet();
            return false;
        } finally {
            if (src != null && !src.isRecycled()) src.recycle();
        }
    }

    /** 文件名 → 该用预设里的哪个颜色。返回 0 表示这张图不归我们管。 */
    private static int colorFor(String name, SkinPreset p) {
        if (name == null) return 0;
        String n = name.toLowerCase(Locale.US);
        boolean pressed = isPressed(n);
        if (n.contains("active")) return p.accent;              // 回车 / 发送
        if (n.contains("func")) return pressed ? p.funcPressed : p.func;
        return pressed ? p.keyPressed : p.key;                  // 字母键 / 九宫格主键
    }

    private static boolean isPressed(String name) {
        return name != null && name.toLowerCase(Locale.US).endsWith("_p.png");
    }

    // ── 解码兜底（H73b）：盘上没画成功时，拦所有皮肤图共同的解码出口 ────────

    /**
     * 挂 {@code com.sogou.lib.common.picture.bitmap.a#p(File)}。
     *
     * <p>这是皮肤图（含键面、气泡、候选底）**唯一**的磁盘解码出口 ——
     * 反编译里 {@code parse.frame.i}、{@code parse.utils.d}、{@code utils.f} 全都调它。
     * 盘上重绘成功时这里直接放行（{@link #active()}），保证同一颗键只被画一次。
     */
    public static void installDecodeHook(ClassLoader cl) {
        try {
            final Class<?> cls = de.robv.android.xposed.XposedHelpers
                    .findClass("com.sogou.lib.common.picture.bitmap.a", cl);
            de.robv.android.xposed.XposedBridge.hookAllMethods(cls, "p",
                    new de.robv.android.xposed.XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            onDecoded(param);
                        }
                    });
            HookRegistry.log("H73 decode hook ok (bitmap.a#p)");
        } catch (Throwable t) {
            HookRegistry.log("H73 decode hook skipped: " + t);
        }
    }

    private static void onDecoded(de.robv.android.xposed.XC_MethodHook.MethodHookParam param) {
        try {
            if (active()) return;                       // 盘上已经画过，别再叠一层
            if (!ModuleConfig.themeSkinEnabled()) return;
            Object r = param.getResult();
            if (!(r instanceof Bitmap)) return;
            Object a0 = param.args == null || param.args.length < 1 ? null : param.args[0];
            if (!(a0 instanceof File)) return;
            File f = (File) a0;
            String name = f.getName();
            if (!isFace(name)) return;

            Context ctx = HookState.context();
            if (ctx == null) return;
            boolean dark = f.getAbsolutePath().contains("/black/");
            SkinPreset p = SkinPreset.of(ModuleConfig.skinPreset(), dark, ctx);
            if (p == null) return;
            int color = colorFor(name, p);
            if (color == 0) return;
            Bitmap art = IosSkin.paintKeyFace((Bitmap) r, color, p, isPressed(name));
            if (art == null) return;
            param.setResult(art);
            int n = OK.incrementAndGet();
            if (n <= 8) HookRegistry.log("H73 key face painted: " + name + " (total " + n + ")");
        } catch (Throwable t) {
            FAIL.incrementAndGet();
        }
    }

    private static boolean isFace(String name) {
        if (name == null) return false;
        for (String s : FACES) {
            if (s.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    // ── 小工具 ───────────────────────────────────────────────────────────

    private static Bitmap decode(File f) {
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            return BitmapFactory.decodeStream(in); // 不带 Options：保留原尺寸/原 alpha
        } catch (Throwable t) {
            return null;
        } finally {
            close(in);
        }
    }

    private static boolean writePng(File f, Bitmap bmp) {
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        OutputStream out = null;
        try {
            out = new FileOutputStream(tmp);
            if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) return false;
            out.flush();
            close(out);
            out = null;
            // 同目录 rename 在 Linux 上是原子的（且会覆盖），
            // 搜狗任何时刻读到的要么是旧图、要么是新图，不会读到半个文件。
            // 千万不能先 delete 再 rename —— 中间那一下丢文件，皮肤就整块空了。
            return tmp.renameTo(f);
        } catch (Throwable t) {
            return false;
        } finally {
            close(out);
            if (tmp.isFile()) tmp.delete();
        }
    }

    private static void copy(File from, File to) {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(from);
            out = new FileOutputStream(to);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (Throwable ignored) {
            // 备份失败不影响主流程
        } finally {
            close(in);
            close(out);
        }
    }

    private static String readString(File f) {
        if (f == null || !f.isFile()) return null;
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] b = new byte[(int) Math.min(256, f.length())];
            int n = in.read(b);
            return n <= 0 ? null : new String(b, 0, n, "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            close(in);
        }
    }

    private static void writeString(File f, String s) {
        OutputStream out = null;
        try {
            out = new FileOutputStream(f);
            out.write(s.getBytes("UTF-8"));
        } catch (Throwable ignored) {
        } finally {
            close(out);
        }
    }

    private static void close(Object c) {
        try {
            if (c instanceof java.io.Closeable) ((java.io.Closeable) c).close();
        } catch (Throwable ignored) {
        }
    }

    // ── 换预设 / 拖滑块后：作废标记，下次 ini 解析时重画 ───────────────────

    public static void invalidate() {
        final File root = LAST_ROOT;
        synchronized (DONE) {
            DONE.clear();
        }
        SKIP.set(0);
        // 换预设 / 拖滑块后立刻重画一遍：不必等搜狗下次解析 ini，
        // 否则用户看到的是"调了没反应"，得重启输入法才生效。
        if (root == null) return;
        try {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    paintOnce(root);
                }
            }, "ios-skin-art").start();
        } catch (Throwable ignored) {
        }
    }

    /** 状态卡：{@code A 成功张数/跳过次数/失败次数}。首字母 A = 盘上已是我们的图。 */
    public static String state() {
        String s = (sDiskArt ? "A" : "-") + OK.get() + "/" + SKIP.get() + "/" + FAIL.get();
        return FAIL.get() > 0 ? s + "!" : s;
    }

    public static String root() {
        return sRoot;
    }

    public static String last() {
        return sLast;
    }
}
