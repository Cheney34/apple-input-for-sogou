package com.chiron.iosemoji.hook;

import android.content.Context;
import android.graphics.Point;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import com.chiron.iosemoji.config.ModuleConfig;
import com.chiron.iosemoji.skin.IosSkin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;

/**
 * H70：液态玻璃的「澎湃实时背景模糊」那一半 —— 移植自 WeType UI Enhanced 的
 * {@code graphics/WeTypeHyperMaterial.kt}。
 *
 * <p><b>它在微信输入法上怎么做的</b>（照抄的调用序列）：
 * <pre>
 *   setPassWindowBlurEnabled(view, true)          // 声明这个 view 要"透窗"取背景
 *   setMiBackgroundBlurMode(1)                    // 背景模糊开
 *   setMiBackgroundBlurRadius(40dp)               // frosted 令牌的固定半径
 *   setMiViewBlurMode(1)                          // 视图自身也走模糊管线
 *   setMiBackgroundBlendColors([三色 + 混合模式])   // 玻璃 tint（light 121/3/3、dark 15/3/3）
 *   setMiBackgroundBlurType(0)
 *   setMixEffectEnabled(false)
 *   setMiBlurWinType(65536) → 500ms 后 → 1      // 新版 ROM 的离屏填充开关
 * </pre>
 *
 * <p><b>为什么这里不会把键盘刷成透明</b>：这些都挂在搜狗自己的输入视图上，模糊由合成器
 * 填在 view 的背景里 —— 它是一个"磨砂面板"，不是一个透明洞。任何一步失败都会
 * {@link #clear()} 回滚，并把 {@link IosSkin#setBlurReady(boolean)} 置 false，
 * 让键帽退回"只轻微半透明"（{@link IosSkin} 里的 glassAlpha）。
 *
 * <p><b>和微信输入法的差异</b>：WeType 需要给 {@code mPassWindowBlurFilterData} 补包名，
 * 是因为它用的 themed Context 属于模块包；我们改的是搜狗自己的 view、context 也是搜狗的，
 * 身份校验天然通过，所以不做那层准入 hack（少一处可能踩到 framework 内部状态的地方）。
 */
public final class HyperGlassHook {

    public static final String ID = "H70_hyper_glass";

    private static final String BLUR_SETTING = "background_blur_enable";
    private static final String PROP_OS_VERSION = "ro.mi.os.version.code";
    private static final String PROP_BLUR_SUPPORTED = "persist.sys.background_blur_supported";
    private static final String PROP_VISUAL_NEW = "persist.sys.advanced_visual_release";
    private static final String PROP_VISUAL_OLD = "persist.sys.background_blur_version";

    /** WeType 的两组玻璃 tint（light / dark）与混合模式，数值原样照抄。 */
    private static final int[] BLEND_LIGHT = {-2130706433, 1728053247, 1722132652};
    private static final int[] BLEND_DARK = {-428838800, -1726737388, 262385602};
    private static final int[] MODES_LIGHT = {121, 3, 3};
    private static final int[] MODES_DARK = {15, 3, 3};

    /** 40dp：WeType 用的是 frosted 令牌，不是可调值。 */
    private static final float BLUR_RADIUS_DP = 40f;

    private static volatile String sStatus = "未启用";
    private static volatile boolean sApplied;
    private static volatile View sTarget;
    private static volatile Drawable sOriginalBackground;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private HyperGlassHook() {
    }

    /** 状态卡用：{@code blur=ok} / {@code blur=off:原因}。 */
    public static String status() {
        return sStatus;
    }

    public static boolean applied() {
        return sApplied;
    }

    /**
     * 搜狗每次把键盘视图装上来时调用（H02 的 after）。
     *
     * <p>为什么挂在这里而不是 Application#attach：模糊必须在一个**已经 attach 的 View** 上开，
     * 键盘视图是唯一稳定拿到"当前这块面板"的时机；而每次弹键盘都会走一次，正好覆盖
     * "改设置 → 重新弹键盘"这个用户一定会做的动作。
     */
    public static void onInputView(View keyboardView) {
        if (keyboardView == null) return;
        if (!ModuleConfig.glassEnabled() || !ModuleConfig.hyperBlurEnabled()) {
            clear("关闭");
            return;
        }
        final View target = topLevel(keyboardView);
        // 刚 setKeyboardView 时通常还没 attach，下一帧再试；连试几次覆盖慢机型
        postApply(target, 0);
    }

    /** 配置变更后重新应用（ConfigBridge 收到广播时调用）。 */
    public static void reapply() {
        View t = sTarget;
        if (t == null) return;
        if (!ModuleConfig.glassEnabled() || !ModuleConfig.hyperBlurEnabled()) {
            clear("关闭");
            return;
        }
        postApply(t, 0);
    }

    private static void postApply(final View target, final int attempt) {
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!target.isAttachedToWindow()) {
                        if (attempt < 6) postApply(target, attempt + 1);
                        else setStatus("view 未 attach");
                        return;
                    }
                    apply(target);
                } catch (Throwable t) {
                    HookRegistry.log("H70 apply failed");
                    HookRegistry.log(t);
                    rollback(target);
                    setStatus("异常:" + t.getClass().getSimpleName());
                }
            }
        }, attempt == 0 ? 16L : 120L);
    }

    // ── 可用性判定（与 WeType 的 isAvailable 同一组条件）────────────────

    private static boolean available(Context ctx) {
        if (Build.VERSION.SDK_INT < 33) {
            setStatus("需 Android 13+");
            return false;
        }
        // 方法存在是最硬的信号：没有这套 View API 的机型，后面调什么都是空转
        if (!resolveMethods()) {
            setStatus("该机型无 View 模糊 API");
            return false;
        }
        // 系统属性读不到（hidden API 被限制等）时**不拦**，交给实际调用去失败 ——
        // 属性读失败并不等于机型不支持，按"支持"尝试一次更划算。
        String osVer = propOrNull(PROP_OS_VERSION);
        if (osVer != null && parseIntSafe(osVer, 0) < 3) {
            setStatus("非澎湃 3+");
            return false;
        }
        String supported = propOrNull(PROP_BLUR_SUPPORTED);
        if (supported != null && !"true".equalsIgnoreCase(supported.trim())) {
            setStatus("系统不支持模糊");
            return false;
        }
        try {
            // 设置项读不到就按"开"算（部分 ROM 没有这一项，但模糊照样可用）
            int enabled = Settings.Secure.getInt(ctx.getContentResolver(), BLUR_SETTING, 1);
            if (enabled != 1) {
                setStatus("系统「背景模糊」未开");
                return false;
            }
        } catch (Throwable ignored) {
            // 读不到就不拦
        }
        return true;
    }

    // ── 应用 ────────────────────────────────────────────────────────────

    private static void apply(View target) {
        if (!available(target.getContext())) {
            rollback(target);
            return;
        }
        if (sApplied && sTarget == target) {
            setStatus("ok");
            return;
        }
        rollback(target);

        float density = target.getResources().getDisplayMetrics().density;
        boolean dark = IosSkin.isDarkNow(target.getContext());

        // 背景必须是透明的，否则不透明底色会把模糊整块盖住
        sOriginalBackground = target.getBackground();
        target.setBackgroundColor(0);

        boolean ok = false;
        try {
            callBoolean(target, "setPassWindowBlurEnabled", true);
            // 返回 true 也可能只是"本来就是开的"，所以按 WeType 的做法直接读真实状态字段
            if (!passWindowBlurState(target)) {
                setStatus("窗口未准入");
            } else {
                callInt(target, "setMiBackgroundBlurMode", 1);
                callInt(target, "setMiBackgroundBlurRadius",
                        (int) (BLUR_RADIUS_DP * density + 0.5f));
                callInt(target, "setMiViewBlurMode", 1);
                callBlend(target, dark ? BLEND_DARK : BLEND_LIGHT, dark ? MODES_DARK : MODES_LIGHT);
                callInt(target, "setMiBackgroundBlurType", 0);
                callBoolean(target, "setMixEffectEnabled", false);
                if (supportsOffScreenFill()) {
                    callInt(target, "setMiBlurWinType", 65536);
                    // WeType 也是隔 500ms 再切成 1，早切会被 ROM 判成功但实际不生效
                    MAIN.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            View t = sTarget;
                            if (t != null && sApplied) {
                                callInt(t, "setMiBlurWinType", 1);
                            }
                        }
                    }, 500L);
                }
                ok = true;
            }
        } catch (Throwable t) {
            HookRegistry.log("H70 calls failed");
            HookRegistry.log(t);
            ok = false;
        }

        if (!ok) {
            rollback(target);
            setStatus("调用被系统拒绝");
            return;
        }

        sTarget = target;
        sApplied = true;
        // 告诉 IosSkin：模糊真的挂上了，键帽可以做更深的半透明
        IosSkin.setBlurReady(true);
        target.invalidate();
        setStatus("ok");
        HookRegistry.log("H70 blur on: " + target.getClass().getName());
    }

    /** 回滚到"什么也没发生"的状态。任何一步失败都走这里，绝不留下半套配置。 */
    public static void clear(String reason) {
        View t = sTarget;
        if (t != null) rollback(t);
        sTarget = null;
        sApplied = false;
        IosSkin.setBlurReady(false);
        setStatus(reason == null ? "关闭" : reason);
    }

    private static void rollback(View target) {
        sApplied = false;
        IosSkin.setBlurReady(false);
        try {
            callInt(target, "setMiBackgroundBlurMode", 0);
            callInt(target, "setMiViewBlurMode", 0);
            callInt(target, "setMiBackgroundBlurRadius", 0);
            callInt(target, "setMiBackgroundBlurType", 0);
            callBoolean(target, "setMixEffectEnabled", false);
            callBoolean(target, "setPassWindowBlurEnabled", false);
            callNoArgs(target, "clearMiBackgroundBlendColor");
            callInt(target, "setMiBlurWinType", 0);
        } catch (Throwable ignored) {
            // 回滚失败也不能抛：下次弹键盘会再试一遍
        }
        Drawable bg = sOriginalBackground;
        sOriginalBackground = null;
        if (bg != null) target.setBackground(bg);
        target.invalidate();
    }

    // ── 反射细节 ────────────────────────────────────────────────────────

    private static Method M_BLUR_MODE;
    private static Method M_BLUR_RADIUS;
    private static Method M_VIEW_BLUR_MODE;
    private static Method M_BLUR_TYPE;
    private static Method M_BLEND;
    private static Method M_CLEAR_BLEND;
    private static Method M_PASS_BLUR;
    private static Method M_MIX_EFFECT;
    private static Method M_BLUR_WIN_TYPE;
    private static volatile boolean sResolved;

    private static synchronized boolean resolveMethods() {
        if (sResolved) return M_PASS_BLUR != null;
        sResolved = true;
        M_BLUR_MODE = find("setMiBackgroundBlurMode", int.class);
        M_BLUR_RADIUS = find("setMiBackgroundBlurRadius", int.class);
        M_VIEW_BLUR_MODE = find("setMiViewBlurMode", int.class);
        M_BLUR_TYPE = find("setMiBackgroundBlurType", int.class);
        M_BLEND = find("setMiBackgroundBlendColors", ArrayList.class);
        M_CLEAR_BLEND = find("clearMiBackgroundBlendColor");
        M_PASS_BLUR = find("setPassWindowBlurEnabled", boolean.class);
        M_MIX_EFFECT = find("setMixEffectEnabled", boolean.class);
        M_BLUR_WIN_TYPE = find("setMiBlurWinType", int.class);
        return M_PASS_BLUR != null && M_BLUR_MODE != null;
    }

    private static Method find(String name, Class<?>... types) {
        try {
            Method m = View.class.getMethod(name, types);
            m.setAccessible(true);
            return m;
        } catch (Throwable ignored) {
            // 有些 ROM 把这些方法声明成非 public，退一步按 declared 找
        }
        try {
            Method m = View.class.getDeclaredMethod(name, types);
            m.setAccessible(true);
            return m;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean callBoolean(View v, String name, boolean value) {
        Method m = methodByName(name, boolean.class);
        if (m == null) return false;
        try {
            Object r = m.invoke(v, value);
            if (m.getReturnType() == boolean.class) {
                return (Boolean) r;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void callInt(View v, String name, int value) {
        Method m = methodByName(name, int.class);
        if (m == null) return;
        try {
            m.invoke(v, value);
        } catch (Throwable ignored) {
            // 单个 API 失败不影响其它几项
        }
    }

    private static void callNoArgs(View v, String name) {
        Method m = methodByName(name);
        if (m == null) return;
        try {
            m.invoke(v);
        } catch (Throwable ignored) {
        }
    }

    private static void callBlend(View v, int[] colors, int[] modes) {
        if (M_BLEND == null) return;
        ArrayList<Point> list = new ArrayList<>(colors.length);
        for (int i = 0; i < colors.length; i++) {
            list.add(new Point(colors[i], modes[Math.min(i, modes.length - 1)]));
        }
        try {
            M_BLEND.invoke(v, list);
        } catch (Throwable ignored) {
        }
    }

    private static Method methodByName(String name, Class<?>... types) {
        switch (name) {
            case "setMiBackgroundBlurMode":
                return M_BLUR_MODE;
            case "setMiBackgroundBlurRadius":
                return M_BLUR_RADIUS;
            case "setMiViewBlurMode":
                return M_VIEW_BLUR_MODE;
            case "setMiBackgroundBlurType":
                return M_BLUR_TYPE;
            case "setPassWindowBlurEnabled":
                return M_PASS_BLUR;
            case "setMixEffectEnabled":
                return M_MIX_EFFECT;
            case "setMiBlurWinType":
                return M_BLUR_WIN_TYPE;
            case "clearMiBackgroundBlendColor":
                return M_CLEAR_BLEND;
            default:
                return null;
        }
    }

    /**
     * 读 {@code View.mNeedPassWindowBlur} 的真实状态。
     *
     * <p>WeType 里那句 {@code check(state.getBoolean(view) == enabled)} 是同一个意思：
     * {@code setPassWindowBlurEnabled()} 返回 true 只代表"没报错"，可能本来就是开的；
     * 只有这个字段为 true 才说明窗口真的准入了。读不到就乐观认为成功（不同 ROM 字段名不一）。
     */
    private static boolean passWindowBlurState(View v) {
        try {
            Field f = View.class.getDeclaredField("mNeedPassWindowBlur");
            f.setAccessible(true);
            return f.getBoolean(v);
        } catch (Throwable t) {
            return true;
        }
    }
    // ── 工具 ────────────────────────────────────────────────────────────

    /**
     * 从键盘视图往上找到"这块面板"的最高一层容器（不含 DecorView）。
     *
     * <p>模糊要盖住键盘 + 候选栏 + 顶部条，所以不能只挂在键盘视图上；但也不能一路找到
     * DecorView —— 那是整扇 IME 窗口，把窗口自身背景刷成模糊会盖掉搜狗自己的绘制。
     */
    private static View topLevel(View v) {
        View cur = v;
        for (int i = 0; i < 16; i++) {
            ViewParent parent = cur.getParent();
            if (!(parent instanceof ViewGroup)) break;
            ViewGroup g = (ViewGroup) parent;
            String cn = g.getClass().getName();
            if (cn.contains("DecorView") || cn.contains("PopupWindow")) break;
            cur = g;
        }
        return cur;
    }

    private static boolean supportsOffScreenFill() {
        return visualVersion() >= 6;
    }

    private static int visualVersion() {
        String property = intProp(PROP_OS_VERSION, 0) > 1 ? PROP_VISUAL_NEW : PROP_VISUAL_OLD;
        return intProp(property, -1);
    }

    private static int intProp(String key, int def) {
        String v = propOrNull(key);
        return v == null ? def : parseIntSafe(v, def);
    }

    private static int parseIntSafe(String v, int def) {
        try {
            return Integer.parseInt(v.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    /** 读系统属性；读不到返回 null（与"读到空值"区分开，可用性判定要靠这个区别）。 */
    private static String propOrNull(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class);
            Object v = get.invoke(null, key);
            return v == null ? null : v.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void setStatus(String s) {
        sStatus = s;
    }
}
