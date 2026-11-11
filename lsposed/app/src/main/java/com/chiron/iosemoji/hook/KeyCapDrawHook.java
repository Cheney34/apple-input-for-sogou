package com.chiron.iosemoji.hook;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

import com.chiron.iosemoji.config.ModuleConfig;
import com.chiron.iosemoji.skin.IosSkin;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * H71 —— 在**真实画布**上画键帽，顺手把搜狗那一层方形底盖掉（v2.2.0）。
 *
 * <p>为什么必须走到这一步：v2.1.3~v2.1.9 六版都在改「搜狗喂过来的那张位图」，可这张图要
 * 变成屏幕上的像素，中间还隔着好几层搜狗自己的东西：
 *
 * <pre>
 *   images_config.ini  IMAGE=key.png,28,30,38,40 TYPE=1   ← 九宫格切边：角落固定 28/30px 不拉伸
 *   [Key*] BG_COLOR                                        ← 键底实色（方的）
 *   bg_container_key / blank_shadow                        ← 容器底板 / 投影
 * </pre>
 *
 * 任何一层不配合，圆角 / 液态玻璃 / 边缘高光就全部白做 —— 用户看到的永远是
 * "改了但没变化"，反馈里"九键、26 键都没用上圆角和高光"就是这么来的。
 *
 * <p>本类的做法是**绕开上面全部**：
 * <ol>
 *   <li>H41 重画完位图后，把「位图 → 键帽参数({@link IosSkin.Cap})」登记到本类；</li>
 *   <li>搜狗每次 {@code Canvas.drawBitmap} 要落屏时，先查这张登记表；</li>
 *   <li>命中就自己画：先整块涂键盘底色（底下的方角/底板一律盖住）→ 圆角面（玻璃或实色）
 *       → 边缘高光，然后 {@code setResult(null)} 跳过原始绘制。</li>
 * </ol>
 *
 * <p>屏幕上那一格从此 100% 是模块画的像素，与搜狗怎么摆图层、怎么切九宫格无关。
 *
 * <p><b>三道保险</b>（宁可"没效果"，也不能画错）：
 * <ul>
 *   <li>只处理登记过的位图 —— 图标、候选、表情图一律不碰（查表 O(1)）；</li>
 *   <li>九宫格切片（{@code src} 不是整张图）直接放行，让搜狗自己画：
 *       那种调用一次只画一个角，我们按整格重画会把键画成碎片；</li>
 *   <li>绘制抛异常就不调用 {@code setResult}，原始绘制照常进行 —— 最坏结果只是"没效果"。</li>
 * </ul>
 */
public final class KeyCapDrawHook {

    /** 位图 → 落屏参数。WeakHashMap：位图被回收后条目自动消失，不会攒住内存。 */
    private static final Map<Bitmap, IosSkin.Cap> CAPS =
            Collections.synchronizedMap(new WeakHashMap<Bitmap, IosSkin.Cap>());

    /** 真正实绘的键数（诊断：这个数 > 0 就说明"屏幕上确实是我们画的"）。 */
    private static final AtomicInteger DRAWN = new AtomicInteger();
    /** 九宫格切片调用（src 不是整张图）—— 放行，不计入实绘。 */
    private static final AtomicInteger SLICED = new AtomicInteger();
    /** 登记过的位图，但这次调用的落点太小/拿不到落点。 */
    private static final AtomicInteger SKIPPED = new AtomicInteger();
    /** 第一颗实绘键的落点尺寸（判断"落屏尺寸和位图尺寸差多少"）。 */
    private static volatile String sSample = "-";
    private static volatile boolean sHasCap = false;

    /** 配置（皮肤总开关）缓存：每秒最多读一次 SharedPreferences。 */
    private static volatile boolean sSkinOn = true;
    private static volatile long sNextCheck = 0L;

    private KeyCapDrawHook() {
    }

    /** H41 重画成功后调用：登记这张位图该怎么落屏。 */
    public static void register(Bitmap bmp, IosSkin.Cap cap) {
        if (bmp == null || cap == null || bmp.isRecycled()) return;
        try {
            CAPS.put(bmp, cap);
            sHasCap = true;
        } catch (Throwable ignored) {
            // 登记失败只是"这颗键不实绘"，不影响任何东西
        }
    }

    /** 换肤/换配色后清表：旧位图的参数已经过期，等 H41 用新参数重新登记。 */
    public static void clear() {
        try {
            CAPS.clear();
            sSample = "-";
            sHasCap = false;
        } catch (Throwable ignored) {
        }
        // 渲染缓存里存的是按旧参数（旧圆角 / 旧配色）渲出来的小图，必须一起丢掉，
        // 否则滑块拖完看到的还是上一档的形状 —— 那又会变成"调了没反应"。
        IosSkin.clearCapCache();
    }

    public static void install(ClassLoader cl) {
        try {
            // Canvas 是 framework 类，与搜狗的 ClassLoader 无关；按方法名挂全部重载。
            XposedBridge.hookAllMethods(Canvas.class, "drawBitmap", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    onDrawBitmap(param);
                }
            });
            HookRegistry.log("H71 keycap draw installed (Canvas.drawBitmap)");
        } catch (Throwable t) {
            HookRegistry.log("H71 keycap draw FAILED: " + t);
        }
    }

    private static boolean skinOn() {
        long now = System.currentTimeMillis();
        if (now >= sNextCheck) {
            sSkinOn = ModuleConfig.themeSkinEnabled();
            sNextCheck = now + 1000L;
        }
        return sSkinOn;
    }

    /**
     * 拦截一次 {@code Canvas.drawBitmap}。
     *
     * <p>参数形态（Android 全部重载）：
     * <pre>
     *   (Bitmap, float, float, Paint)          ← 1:1 贴图
     *   (Bitmap, Rect, Rect, Paint)            ← 拉伸（键帽最常见）
     *   (Bitmap, Rect, RectF, Paint)           ← 拉伸（浮点落点）
     *   (Bitmap, Matrix, Paint)                ← 矩阵绘制，拿不到落点，放行
     * </pre>
     */
    private static void onDrawBitmap(XC_MethodHook.MethodHookParam param) {
        if (!sHasCap) return;
        try {
            Object[] a = param.args;
            if (a == null || a.length < 3) return;
            if (!(a[0] instanceof Bitmap)) return;
            Bitmap bmp = (Bitmap) a[0];
            // 查表用 identity：Bitmap 没重写 equals，WeakHashMap 等价于按对象身份查
            IosSkin.Cap cap = CAPS.get(bmp);
            if (cap == null) return;
            if (bmp.isRecycled()) return;
            if (!(param.thisObject instanceof Canvas)) return;
            if (!skinOn()) return;

            // ── ① 取落点：拿不到就放行（宁可不动，也不能画错位置）
            Rect src = null;
            RectF dst = null;
            if (a[1] instanceof Rect && a.length > 2 && a[2] instanceof Rect) {
                src = (Rect) a[1];
                Rect r = (Rect) a[2];
                dst = new RectF(r);
            } else if (a[1] instanceof Rect && a.length > 2 && a[2] instanceof RectF) {
                src = (Rect) a[1];
                dst = new RectF((RectF) a[2]);
            } else if (a[1] instanceof Float && a[2] instanceof Float) {
                float x = (Float) a[1];
                float y = (Float) a[2];
                dst = new RectF(x, y, x + bmp.getWidth(), y + bmp.getHeight());
            } else {
                SKIPPED.incrementAndGet();   // Matrix 绘制等：落点未知
                return;
            }
            if (dst.width() < 6f || dst.height() < 6f) {
                SKIPPED.incrementAndGet();
                return;
            }

            // ── ② 九宫格切片：一次只画一个角/一条边，按整格重画会把键画成碎片，放行。
            if (src != null && (src.width() != bmp.getWidth() || src.height() != bmp.getHeight())) {
                SLICED.incrementAndGet();
                return;
            }

            // ── ③ 原始绘制的透明度（按键按压缩放用得上）
            int alpha = 255;
            Object last = a[a.length - 1];
            if (last instanceof Paint) {
                int pa = ((Paint) last).getAlpha();
                if (pa < 255) alpha = pa;
            }

            IosSkin.drawCapOn((Canvas) param.thisObject, dst, cap, alpha);
            param.setResult(null);   // 跳过原始绘制：这一格从此只有我们画的那一层
            int n = DRAWN.incrementAndGet();
            if ("-".equals(sSample)) {
                sSample = Math.round(dst.width()) + "x" + Math.round(dst.height());
            }
            if (n <= 3) {
                HookRegistry.log("H71 keycap drawn " + Math.round(dst.width()) + "x"
                        + Math.round(dst.height()) + " kind=" + cap.kind
                        + " ratio=" + cap.ratio + (cap.glass ? " glass" : "")
                        + (cap.rim ? " rim" : ""));
            }
        } catch (Throwable t) {
            // 不 setResult → 原始绘制照常发生，最坏只是"这一格没效果"
            SKIPPED.incrementAndGet();
        }
    }

    /** 状态串（诊断）：实绘 / 九宫格切片放行 / 落点未知。 */
    public static String state() {
        return DRAWN.get() + "/" + SLICED.get() + "/" + SKIPPED.get() + "/" + sSample;
    }
}
