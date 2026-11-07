package com.chiron.iosemoji.hook;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.ImageView;
import android.widget.RelativeLayout;

import com.chiron.iosemoji.compat.Targets;
import com.chiron.iosemoji.config.ModuleConfig;
import com.chiron.iosemoji.core.HookState;
import com.chiron.iosemoji.skin.IosSkin;
import com.chiron.iosemoji.skin.SkinPreset;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * H60 + H61：让 MIUI / HyperOS「全面屏键盘优化」底栏跟随本模块皮肤。
 *
 * <p><b>真正的颜色落点是 {@code com.miui.phrase} 里的
 * {@code com.miui.inputmethod.InputMethodBottomManager}</b>（v2.0.3-beta6 起按此实现）：
 * <pre>
 *   Sogou 上报窗口色 → PhoneWindow.setNavigationBarColor(int)
 *        ↓
 *   framework: InputMethodServiceInjector.customizeBottomViewColor(boolean,int,int,int)
 *        ↓
 *   InputMethodBottomManager.customizeBottomViewColor(...)   ← H61 before 改参（治本）
 *        ↓
 *   InputMethodBottomManager.setBottomColor(boolean,int,int,int)
 *        sBottomView.setBackgroundColor(bg) + 左右图标 setBackgroundTintList(CSL)
 * </pre>
 *
 * <p><b>为什么以前不生效（已从 phrase 5.7.4 反编译确认）</b>：
 * {@code setBottomColor()} 第一行就是
 * {@code if (sBottomView == null) { Log.e("...please setBottomColor after addMiuiBottomView"); return; }}
 * —— 搜狗上报颜色时底栏还没 add（或刚被重建），这次上报被直接丢弃；随后
 * {@code addMiuiBottomView()} 用<b>默认色</b>把底栏建起来，再没有人重设。
 * 所以必须「改参 + 建完之后主动补一次」两条都做。
 *
 * <p><b>参数语义</b>（phrase 5.7.4 反编译 + 已有实现交叉确认）：
 * arg0 {@code isCustom}（true=用自定义配色）、arg1 底栏背景色、arg2 图标常态色、
 * arg3 图标按下态色。全程只改颜色，不动尺寸 / padding / insets。
 */
public final class MiuiBottomBarHook {

    private static final String CLS_BOTTOM_MANAGER = "com.miui.inputmethod.InputMethodBottomManager";

    /** 不同 MIUI / HyperOS 版本里注入类名字不一样，逐个试。 */
    private static final String[] INJECTORS = {
            "android.inputmethodservice.InputMethodServiceInjector",
            "android.inputmethodservice.InputMethodServiceStubImpl",
    };

    /** 底栏透出父容器（只留图标）。默认关：需要父容器已铺键盘底色。 */
    private static final boolean FORCE_TRANSPARENT = false;

    private static final AtomicInteger PATCHED = new AtomicInteger();
    private static final AtomicInteger APPLIED = new AtomicInteger();

    private static volatile boolean sInstalled;
    private static volatile boolean sFrameworkHooked;
    private static volatile boolean sManagerHooked;
    private static volatile boolean sDisabled;
    private static volatile Boolean sSkinDark;
    private static volatile String sManagerLoader;
    private static volatile long sLastNavApply;
    private static final int FAIL_MAX = 3;
    private static int sFails;

    private MiuiBottomBarHook() {
    }

    // ------------------------------------------------------------------ install

    public static void install(ClassLoader cl) {
        install(cl, null);
    }

    public static void install(final ClassLoader cl, Targets targets) {
        if (sInstalled) {
            tryInstallBottomManager(cl);
            return;
        }
        sInstalled = true;
        hookFramework(cl);
        tryInstallBottomManager(cl);
        // 动态 dex 里的类要等注入完成才能 load，前几十秒反复试（失败只影响底栏配色）。
        final Handler h = new Handler(Looper.getMainLooper());
        final long[] delays = {300L, 1000L, 3000L, 6000L, 12000L, 24000L, 45000L};
        for (final long d : delays) {
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (sManagerHooked || sDisabled) return;
                    tryInstallBottomManager(cl);
                }
            }, d);
        }
        HookRegistry.log("H60/H61 install: framework=" + sFrameworkHooked + " manager=" + sManagerHooked);
    }

    /** framework 侧只做「观察 + 补一次」，不再改参（改参放到 phrase 侧，出问题也只在底栏）。 */
    private static void hookFramework(ClassLoader cl) {
        if (sFrameworkHooked) return;
        for (String name : INJECTORS) {
            try {
                Class<?> cls = XposedHelpers.findClass(name, null);
                if (cls == null) continue;
                XposedBridge.hookAllMethods(cls, "addMiuiBottomView", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        postApply(80L);
                    }
                });
                try {
                    XposedHelpers.findAndHookMethod(cls, "customizeBottomViewColor",
                            boolean.class, int.class, int.class, int.class,
                            new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) {
                                    postApply(30L);
                                }
                            });
                } catch (Throwable ignored) {
                    // 这个注入类里没有这个方法就跳过
                }
                sFrameworkHooked = true;
                HookRegistry.log("H60 framework hooked: " + name);
                break;
            } catch (Throwable t) {
                HookRegistry.log("H60 framework skip " + name + ": " + t);
            }
        }
        try {
            XposedHelpers.findAndHookMethod("com.android.internal.policy.PhoneWindow", null,
                    "setNavigationBarColor", int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            long now = SystemClock.uptimeMillis();
                            if (now - sLastNavApply < 400L) return;
                            sLastNavApply = now;
                            postApply(60L);
                        }
                    });
        } catch (Throwable ignored) {
        }
    }

    /** phrase 侧的 InputMethodBottomManager：改参（治本）+ 建完之后补一次。 */
    private static void tryInstallBottomManager(ClassLoader cl) {
        if (sManagerHooked || sDisabled) return;
        Class<?> cls = resolveBottomManager(cl);
        if (cls == null) return;
        try {
            try {
                XposedHelpers.findAndHookMethod(cls, "customizeBottomViewColor",
                        boolean.class, int.class, int.class, int.class, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                rewriteArgs(param);
                            }
                        });
            } catch (Throwable ignored) {
            }
            try {
                XposedHelpers.findAndHookMethod(cls, "setBottomColor",
                        boolean.class, int.class, int.class, int.class, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                rewriteArgs(param);
                            }
                        });
            } catch (Throwable ignored) {
            }
            // 底栏刚建好 / 重建后补一次：setBottomColor 的 early-return 会吃掉首次上报。
            try {
                XposedBridge.hookAllMethods(cls, "addMiuiBottomView", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        postApply(120L);
                    }
                });
            } catch (Throwable ignored) {
            }
            try {
                XposedBridge.hookAllMethods(cls, "refreshBottomSkin", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        postApply(60L);
                    }
                });
            } catch (Throwable ignored) {
            }
            sManagerHooked = true;
            HookRegistry.log("H61 hooked: " + CLS_BOTTOM_MANAGER + " via " + sManagerLoader);
        } catch (Throwable t) {
            fail("installBottomManager", t);
        }
    }

    private static Class<?> resolveBottomManager(ClassLoader cl) {
        ClassLoader[] candidates = new ClassLoader[]{
                cl,
                HookState.context() == null ? null : HookState.context().getClassLoader(),
                Thread.currentThread().getContextClassLoader(),
                ClassLoader.getSystemClassLoader(),
        };
        for (int i = 0; i < candidates.length; i++) {
            ClassLoader l = candidates[i];
            if (l == null) continue;
            try {
                Class<?> cls = XposedHelpers.findClass(CLS_BOTTOM_MANAGER, l);
                if (cls != null) {
                    sManagerLoader = (i == 0 ? "ime" : "c" + i);
                    return cls;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static void rewriteArgs(XC_MethodHook.MethodHookParam param) {
        try {
            if (!enabled() || param.args == null || param.args.length < 4) return;
            int[] c = colors();
            if (c == null) return;
            param.args[0] = Boolean.TRUE;
            param.args[1] = c[0];
            param.args[2] = c[1];
            param.args[3] = c[2];
            int n = PATCHED.incrementAndGet();
            if (n <= 4) HookRegistry.log(String.format("H61 bottom color -> #%08X", c[0]));
        } catch (Throwable t) {
            fail("rewriteArgs", t);
        }
    }

    private static void postApply(long delay) {
        if (!enabled()) return;
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                apply();
            }
        }, delay);
    }

    /** 主动改已存在的底栏（反射静态字段，全部判空、失败只记日志）。 */
    public static void apply() {
        if (!enabled()) return;
        try {
            Class<?> cls = resolveBottomManager(null);
            if (cls == null) return;
            int[] c = colors();
            if (c == null) return;

            View bottom = (View) staticField(cls, "sBottomView");
            if (bottom == null) {
                HookRegistry.log("H60 apply skipped: sBottomView == null（等 addMiuiBottomView）");
                return;
            }
            bottom.setBackgroundColor(c[0]);
            int[][] states = (int[][]) staticField(cls, "sBottomIconStates");
            ImageView left = (ImageView) staticField(cls, "sLeftButton");
            ImageView right = (ImageView) staticField(cls, "sRightButton");
            if (states != null && (left != null || right != null)) {
                ColorStateList csl = new ColorStateList(states, new int[]{c[1], c[2]});
                if (left != null) left.setBackgroundTintList(csl);
                if (right != null) right.setBackgroundTintList(csl);
            }
            setStatic(cls, "currentBottomViewColor", c[0]);
            int n = APPLIED.incrementAndGet();
            if (n <= 4) HookRegistry.log(String.format("H60 bottom applied -> #%08X", c[0]));
        } catch (Throwable t) {
            fail("apply", t);
        }
    }

    private static Object staticField(Class<?> cls, String name) {
        try {
            Field f = cls.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void setStatic(Class<?> cls, String name, Object value) {
        try {
            Field f = cls.getDeclaredField(name);
            f.setAccessible(true);
            f.set(null, value);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------- colors

    private static boolean enabled() {
        if (sDisabled) return false;
        try {
            return ModuleConfig.masterEnabled()
                    && ModuleConfig.themeSkinEnabled()
                    && ModuleConfig.miuiBottomBarEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    private static int[] colors() {
        Context ctx = HookState.context();
        if (ctx == null) return null;
        boolean dark;
        if (sSkinDark != null) {
            dark = sSkinDark;
        } else {
            dark = IosSkin.isDark(null, ctx);
        }
        SkinPreset p = SkinPreset.of(ModuleConfig.skinPreset(), dark, ctx);
        int bg = p.barBg != 0 && !FORCE_TRANSPARENT ? p.barBg : p.kbBg;
        if (FORCE_TRANSPARENT) bg = Color.TRANSPARENT;
        int icon = (IosSkin.ensureContrast(p.text, bg, 3.0) & 0xFFFFFF) | 0xFF000000;
        int iconPressed = (IosSkin.ensureContrast(p.textPressed, bg, 3.0) & 0xFFFFFF) | 0xFF000000;
        return new int[]{bg, icon, iconPressed};
    }

    // --------------------------------------------------------------------- API

    /** ThemeSkinHook 解析到 /black/ 或 /white/ 皮肤目录时同步深浅色，避免一次误判。 */
    public static void noteSkinDark(boolean dark) {
        sSkinDark = dark;
    }

    /** 配置变更 / 换肤 / 重载后重新下发一次。 */
    public static void reapply() {
        sSkinDark = null;
        if (!sInstalled) return;
        tryInstallBottomManager(null);
        postApply(60L);
    }

    public static String stateFields() {
        return "bb=" + PATCHED.get() + "/" + APPLIED.get()
                + ";bbmg=" + (sManagerHooked ? 1 : 0)
                + ";bbfw=" + (sFrameworkHooked ? 1 : 0)
                + ";bbld=" + (sManagerLoader == null ? "-" : sManagerLoader)
                + ";bbdark=" + (sSkinDark == null ? "-" : (sSkinDark ? "1" : "0"))
                + ";bbdis=" + (sDisabled ? 1 : 0);
    }

    public static String summary() {
        return "bottom(patched=" + PATCHED.get() + ",applied=" + APPLIED.get()
                + ",manager=" + (sManagerHooked ? "ok" : "no") + ")";
    }

    private static void fail(String where, Throwable t) {
        sFails++;
        HookRegistry.log("H60/H61 " + where + " failed: " + t);
        if (sFails >= FAIL_MAX && !sDisabled) {
            sDisabled = true;
            HookRegistry.log("H60 circuit breaker OPEN —— 本进程不再改底栏（保命优先）");
        }
    }
}
