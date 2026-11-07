package com.chiron.iosemoji.hook;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.chiron.iosemoji.config.ModuleConfig;
import com.chiron.iosemoji.core.HookState;
import com.chiron.iosemoji.skin.IosSkin;
import com.chiron.iosemoji.skin.SkinPreset;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * H60：让 MIUI / HyperOS「全面屏键盘优化」底栏跟随本模块的皮肤配色。
 *
 * <p><b>为什么 H40 / H41 改不到它</b>：那两个 hook 改的是搜狗 APK 内部
 * {@code com.sogou.theme.*} 解析出来的 ini 颜色表与皮肤位图。而全面屏键盘优化底栏
 * 是 MIUI 从 {@code com.miui.phrase} 动态 dex 里加载
 * {@code com.miui.inputmethod.InputMethodBottomManager} 后，挂到
 * {@code InputMethodService} 的 {@code SoftInputWindow} 上的一组 View。
 * 它<b>不读搜狗的 ini，也不读搜狗的位图</b>，颜色走的是另一条独立通道：
 * <pre>
 *   PhoneWindow.setNavigationBarColor(int)          ← 输入法主动上报的窗口色
 *        ↓
 *   InputMethodServiceInjector.customizeBottomViewColor(boolean, int, int, int)
 *        ↓ reflectModule()
 *   InputMethodBottomManager  →  miuiBottomArea 底栏
 * </pre>
 * 所以皮肤换得再彻底，底栏拿到的仍是搜狗上报的「原始皮肤色」。
 *
 * <p><b>切入点的选择</b>：真正干活的 {@code com.miui.inputmethod.*} 在动态 dex 的
 * ClassLoader 里（{@code InputMethodModuleManager.loadDex} → addDexPath），不是
 * {@code lpparam.classLoader}；要 hook 它得额外给 LSPosed 勾选 {@code com.miui.phrase}
 * 作用域并监听 loadDex。但 {@code InputMethodServiceInjector} 在 framework.jar
 * （boot classpath），<b>在搜狗进程内用 null ClassLoader 就能拿到</b>，且它正好是
 * 所有颜色上报的转发枢纽。因此本 hook 完全在现有作用域内工作，不需要改 xposed_scope。
 *
 * <p><b>三处配合，缺一不可</b>：
 * <ol>
 *   <li><b>before 改参（治本）</b>：hook {@code customizeBottomViewColor}，
 *       把任何来源的颜色都改写成当前 {@link SkinPreset} 的配色。只主动调一次是不够的，
 *       搜狗后续每次上报都会把颜色冲掉；</li>
 *   <li>{@code addMiuiBottomView} 之后补一次：底栏刚创建、切换输入法后重建的时序；</li>
 *   <li>{@code PhoneWindow.setNavigationBarColor} 之后补一次：换肤 / 深浅色切换的时序。</li>
 * </ol>
 *
 * <p>参数语义由三个已实机验证的实现交叉确认（RC1844/MIUI_IME_Unlock、
 * Yizhou147/HyperOS-IME-Unlock、NEORUAA/WeType_UI_Enhanced）：
 * <ul>
 *   <li>arg0 {@code isCustom}：传 true 表示使用自定义配色；</li>
 *   <li>arg1：底栏背景色（不透明）；</li>
 *   <li>arg2：图标 / 文字色 = {@code ~bg | 0xFF000000}（反色，天然适配深浅色）；</li>
 *   <li>arg3：次要 / 按下态 = {@code ~bg | 0x66000000}（同色 40% alpha）。</li>
 * </ul>
 *
 * <p>全程只改颜色、不碰任何 View 的尺寸 / padding / insets，也不动窗口几何，
 * 与 H40/H41 的「铁律」一致。任何一步失败都只记日志，搜狗与底栏保持原行为。
 */
public final class MiuiBottomBarHook {

    /** 不同 MIUI / HyperOS 版本里这个注入类的名字不一样，逐个试（DEX 层面已见过的两个）。 */
    private static final String[] INJECTORS = {
            "android.inputmethodservice.InputMethodServiceInjector",
            "android.inputmethodservice.InputMethodServiceStubImpl",
    };

    /**
     * 透明模式：把 arg1 传 {@code Color.TRANSPARENT}，让底栏透出父容器，
     * 只保留白/黑图标（WeType_UI_Enhanced 的做法，观感最"无缝"）。
     *
     * <p>但它要求底栏的父容器已经铺好键盘底色；在搜狗这套「改默认皮肤」方案里
     * 父容器是 MIUI 的窗口，某些版本会露出窗口默认黑/白，反而更花。
     * 因此默认用实色 {@code SkinPreset.kbBg}。上机若发现底栏与键盘之间
     * 仍有一条接缝，可以把它改成 true 试一次。
     */
    private static final boolean FORCE_TRANSPARENT = false;

    private static volatile Method sCustomize;
    /** 由 ThemeSkinHook 在解析皮肤 ini 时同步：跟随实际加载的 black/white 皮肤，而不是猜系统夜间模式。 */
    private static volatile boolean sSkinDark;
    private static volatile boolean sHasSkinHint;
    private static volatile int sAppliedBg = Integer.MIN_VALUE;
    private static volatile boolean sApplying;
    /** setNavigationBarColor 是进程级高频方法，节流避免无谓反射。 */
    private static volatile long sLastNavApply;

    private static final AtomicInteger PATCHED = new AtomicInteger();
    private static final AtomicInteger APPLIED = new AtomicInteger();

    private MiuiBottomBarHook() {
    }

    /** 幂等安装。跟 H40/H41 一起在 attach 前后各调一次都没问题。 */
    public static void install(ClassLoader cl) {
        HookRegistry.install("H60_miui_bottom_bar", cl, MiuiBottomBarHook::setup);
    }

    private static void setup(ClassLoader cl) {
        Class<?> injector = findInjector();
        if (injector == null) {
            // 非 MIUI、或该 ROM 没有「全面屏键盘优化」：直接跳过，不改任何行为。
            // 这里不抛异常：状态串里的 bb=0/0;bbm=0 已经能说明没挂上，抛异常只会像出错。
            HookRegistry.log("H60 skipped: no MIUI InputMethodServiceInjector on this ROM");
            return;
        }
        HookRegistry.log("H60 injector=" + injector.getName()
                + " prop(ro.miui.support_miui_ime_bottom)=" + supportProp());

        // 主动调用用的 Method（private static，必须 setAccessible）
        try {
            Method m = injector.getDeclaredMethod("customizeBottomViewColor",
                    boolean.class, int.class, int.class, int.class);
            m.setAccessible(true);
            sCustomize = m;
        } catch (Throwable t) {
            // 某些 ROM 换成了别的签名，before-hook 仍然能命中，只是没法主动补刀
            HookRegistry.log("H60 customizeBottomViewColor(4 args) not found: " + t);
        }

        // ── 1. 治本：所有上报到 MIUI 底栏的颜色都改写成皮肤色 ──────────────
        hookAll(injector, "customizeBottomViewColor", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (!enabled() || param.args == null || param.args.length < 4) return;
                    if (!(param.args[1] instanceof Integer)) return;
                    int want = (Integer) param.args[1];
                    // 调用方本来就要「透明/无底栏」（全屏、游戏、悬浮键盘）时不要接管，
                    // 否则会把本来不显示的底栏点亮成一条灰条。
                    if (!FORCE_TRANSPARENT && (want >>> 24) == 0) return;
                    int[] c = colors();
                    if (c == null) return;
                    // 只改颜色，不动 args[0]（isCustom）—— 由调用方决定要不要走自定义配色。
                    param.args[1] = c[0];
                    param.args[2] = c[1];
                    param.args[3] = c[2];
                    int n = PATCHED.incrementAndGet();
                    if (n <= 5) {
                        HookRegistry.log(String.format("H60 color report patched -> #%08X", c[0]));
                    }
                } catch (Throwable t) {
                    HookRegistry.log("H60 before patch failed: " + t);
                }
            }
        });

        // ── 2. 底栏创建 / 切换输入法重建后，下一帧补一次 ────────────────────
        hookAll(injector, "addMiuiBottomView", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (!enabled()) return;
                    // 参数表（MIUI 12.5 上核对过）：
                    //   [0] LayoutInflater [1] fullscreenArea [2] inputFrame
                    //   [3] rootView       [4] miuiBottomArea [5] InputMethodManager
                    //   [6] InputMethodService
                    // 这里不动任何 View，只在下一帧重新下发颜色，避免踩布局。
                    if (PATCHED.get() <= 5) logArgs(param);
                    postApply(true);
                } catch (Throwable t) {
                    HookRegistry.log("H60 addMiuiBottomView after failed: " + t);
                }
            }
        });

        // ── 3. 换肤 / 深浅色切换时会重新上报导航栏颜色 ─────────────────────
        try {
            Class<?> phoneWindow = XposedHelpers.findClass(
                    "com.android.internal.policy.PhoneWindow", null);
            XposedHelpers.findAndHookMethod(phoneWindow, "setNavigationBarColor", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!enabled()) return;
                                if (HookState.context() == null) return;
                                long now = SystemClock.uptimeMillis();
                                if (now - sLastNavApply < 400L) return;
                                sLastNavApply = now;
                                apply(false);
                            } catch (Throwable t) {
                                HookRegistry.log("H60 navBar after failed: " + t);
                            }
                        }
                    });
            HookRegistry.log("H60 PhoneWindow.setNavigationBarColor hooked");
        } catch (Throwable t) {
            // 有的版本这个方法在 Window 基类 / 被内联掉；有 1、2 两点兜底即可
            HookRegistry.log("H60 PhoneWindow hook skipped: " + t);
        }
    }

    // ── 配色 ───────────────────────────────────────────────────────────────

    private static boolean enabled() {
        return ModuleConfig.masterEnabled()
                && ModuleConfig.themeSkinEnabled()
                && ModuleConfig.miuiBottomBarEnabled();
    }

    /** 返回 {背景色, 图标色, 次要色}。 */
    private static int[] colors() {
        try {
            Context ctx = HookState.context();
            boolean dark = sHasSkinHint ? sSkinDark : IosSkin.isDark(null, ctx);
            SkinPreset p = SkinPreset.of(ModuleConfig.skinPreset(), dark, ctx);

            int bg, icon, soft;
            if (FORCE_TRANSPARENT) {
                bg = Color.TRANSPARENT;
                icon = IosSkin.ensureContrast(p.text, p.kbBg, 3.0);
                soft = Color.argb(0x66, Color.red(icon), Color.green(icon), Color.blue(icon));
            } else {
                bg = p.kbBg;
                int inverted = ~bg;
                icon = inverted | 0xFF000000;                 // 反色，强制不透明
                soft = (inverted & 0x00FFFFFF) | 0x66000000; // 反色，40% alpha
            }
            return new int[]{bg, icon, soft};
        } catch (Throwable t) {
            HookRegistry.log("H60 colors failed: " + t);
            return null;
        }
    }

    // ── 应用 ───────────────────────────────────────────────────────────────

    /**
     * 主动下发一次配色。{@code force=true} 用于「底栏刚重建 / 配置刚变」，
     * 此时即使颜色没变也要重发；否则同色去重，避免无谓的反射调用。
     */
    private static void apply(boolean force) {
        if (!enabled() || sApplying) return;
        Method m = sCustomize;
        if (m == null) return;
        int[] c = colors();
        if (c == null) return;
        if (!force && c[0] == sAppliedBg) return;

        sApplying = true;
        try {
            m.invoke(null, Boolean.TRUE, c[0], c[1], c[2]);
            sAppliedBg = c[0];
            int n = APPLIED.incrementAndGet();
            if (n <= 5 || force) {
                HookRegistry.log(String.format(
                        "H60 apply bg=#%08X icon=#%08X soft=#%08X preset=%s dark=%b",
                        c[0], c[1], c[2], ModuleConfig.skinPreset(), sSkinDark));
            }
        } catch (Throwable t) {
            HookRegistry.log("H60 apply failed: " + t);
        } finally {
            sApplying = false;
        }
    }

    /** customizeBottomViewColor 最终会改 View，统一切到主线程执行。 */
    private static void postApply(final boolean force) {
        try {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    apply(force);
                }
            });
        } catch (Throwable t) {
            HookRegistry.log("H60 post failed: " + t);
        }
    }

    /** ThemeSkinHook 解析到 black/white 皮肤时同步，保证底栏跟「实际渲染的键盘」同色。 */
    public static void noteSkinDark(boolean dark) {
        if (sHasSkinHint && sSkinDark == dark) return;
        sHasSkinHint = true;
        sSkinDark = dark;
        sAppliedBg = Integer.MIN_VALUE;
        postApply(true);
    }

    /** 设置页改了皮肤预设 / 开关后由 ConfigBridge 回调，立刻重刷底栏。 */
    public static void reapply() {
        sAppliedBg = Integer.MIN_VALUE;
        postApply(true);
    }

    // ── 诊断 ───────────────────────────────────────────────────────────────

    /** 状态串字段：{@code bb=<改参次数>/<主动应用次数>}。bb 左侧为 0 说明颜色上报根本没走这条通道。 */
    public static String stateFields() {
        return "bb=" + PATCHED.get() + "/" + APPLIED.get()
                + ";bbdark=" + (sHasSkinHint ? (sSkinDark ? 1 : 0) : -1)
                + ";bbm=" + (sCustomize != null ? 1 : 0);
    }

    private static void logArgs(XC_MethodHook.MethodHookParam param) {
        if (param.args == null) return;
        StringBuilder sb = new StringBuilder("H60 addMiuiBottomView args:");
        for (int i = 0; i < param.args.length; i++) {
            Object a = param.args[i];
            sb.append(" [").append(i).append("]=")
                    .append(a == null ? "null" : a.getClass().getSimpleName());
        }
        HookRegistry.log(sb.toString());
    }

    private static int hookAll(Class<?> clazz, String name, XC_MethodHook cb) {
        try {
            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(clazz, name, cb);
            int n = hooks == null ? 0 : hooks.size();
            if (n == 0) HookRegistry.log("H60 no '" + name + "' on " + clazz.getName());
            return n;
        } catch (Throwable t) {
            HookRegistry.log("H60 hook '" + name + "' failed: " + t);
            return 0;
        }
    }

    private static Class<?> findInjector() {
        for (String name : INJECTORS) {
            try {
                Class<?> c = XposedHelpers.findClass(name, null);
                if (c != null) return c;
            } catch (Throwable ignored) {
                // 换下一个名字
            }
            try {
                return Class.forName(name);
            } catch (Throwable ignored) {
                // 换下一个名字
            }
        }
        return null;
    }

    /** 只读诊断：确认这台机器到底开没开全面屏键盘优化，不作为安装前置条件。 */
    private static String supportProp() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getDeclaredMethod("get", String.class, String.class);
            return String.valueOf(get.invoke(null, "ro.miui.support_miui_ime_bottom", "?"));
        } catch (Throwable t) {
            return "?";
        }
    }
}
