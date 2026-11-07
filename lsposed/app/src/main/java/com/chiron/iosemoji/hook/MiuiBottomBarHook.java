package com.chiron.iosemoji.hook;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
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
    private static volatile boolean sDispatchInstalled;
    private static volatile boolean sDisabled;
    private static volatile Boolean sSkinDark;
    private static volatile String sManagerLoader;
    private static volatile long sLastNavApply;
    /** IME 窗口的 decorView（onWindowShown 时抓一次），用于视图树兜底。 */
    private static volatile java.lang.ref.WeakReference<View> sDecor;
    private static final AtomicInteger TREE_HITS = new AtomicInteger();
    /** 系统导航栏（"系统底栏"）改参次数：全面屏键盘优化开着时，键盘下方那块是系统导航条。 */
    private static final AtomicInteger NAV_PATCHED = new AtomicInteger();
    private static volatile boolean sApplying;
    private static volatile java.lang.ref.WeakReference<android.view.Window> sWindow;
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
        hookDecorCapture(cl);
        hookClassLoaderDispatch();
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

    /**
     * ★ 关键：phrase 的 {@code InputMethodBottomManager} 在动态 dex 的 ClassLoader 里，
     * 它不一定能通过 lpparam.classLoader 直接找到（HyperCeiler 也是用 loadClass 分发解决的）。
     * 这里挂 {@code ClassLoader#loadClass}，一旦有人加载这个类，就地把它 hook 上。
     * 判据只有一个 {@code name.equals(...)}，命中一次后立即卸载负担（sManagerHooked）。
     */
    private static void hookClassLoaderDispatch() {
        if (sDispatchInstalled) return;
        sDispatchInstalled = true;
        XC_MethodHook cb = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (sManagerHooked) return;
                try {
                    Object a0 = param.args == null || param.args.length == 0 ? null : param.args[0];
                    if (!CLS_BOTTOM_MANAGER.equals(a0)) return;
                    Object r = param.getResult();
                    if (!(r instanceof Class)) return;
                    attachBottomManager((Class<?>) r);
                } catch (Throwable ignored) {
                }
            }
        };
        try {
            XposedHelpers.findAndHookMethod(ClassLoader.class, "loadClass", String.class, cb);
        } catch (Throwable t) {
            HookRegistry.log("H61 loadClass dispatch skip: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(ClassLoader.class, "loadClass", String.class, boolean.class, cb);
        } catch (Throwable ignored) {
        }
        HookRegistry.log("H61 loadClass dispatch armed for " + CLS_BOTTOM_MANAGER);
    }

    /** 抓 IME 的 decorView：底栏是 MIUI 挂在 SoftInputWindow 上的兄弟节点。 */
    private static void hookDecorCapture(ClassLoader cl) {
        try {
            XposedBridge.hookAllMethods(android.inputmethodservice.InputMethodService.class, "onWindowShown",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                android.inputmethodservice.InputMethodService svc =
                                        (android.inputmethodservice.InputMethodService) param.thisObject;
                                if (svc.getWindow() == null || svc.getWindow().getWindow() == null) return;
                                android.view.Window w = svc.getWindow().getWindow();
                                sDecor = new java.lang.ref.WeakReference<View>(w.getDecorView());
                                sWindow = new java.lang.ref.WeakReference<android.view.Window>(w);
                                applyNavBar(w);
                                postApply(120L);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
        } catch (Throwable t) {
            HookRegistry.log("H60 decor capture skip: " + t);
        }
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
                        protected void beforeHookedMethod(MethodHookParam param) {
                            // 系统底栏（导航条）：全面屏键盘优化开着时，键盘下方那块是系统导航条，
                            // 颜色就是在这个调用里定的。只替换颜色这一个参数，其余原样。
                            if (!enabled() || sApplying || sDisabled) return;
                            try {
                                int[] c = colors();
                                if (c == null) return;
                                param.args[0] = c[0];
                                NAV_PATCHED.incrementAndGet();
                            } catch (Throwable ignored) {
                            }
                        }

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
        attachBottomManager(cls);
    }

    /** 真正挂 hook（loadClass 分发与主动查找共用的入口）。 */
    private static synchronized void attachBottomManager(Class<?> cls) {
        if (sManagerHooked || sDisabled || cls == null) return;
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
            // 顺手改掉它的默认色：addMiuiBottomViewInner 里 setBottomColor(false,0,0,0)
            // 会拿静态字段刷底栏，改掉之后连默认路径都是我们的颜色。
            seedDefaults(cls);
        } catch (Throwable t) {
            fail("installBottomManager", t);
        }
    }

    /** 把 phrase 的静态默认色换成当前皮肤色（失败只记日志）。 */
    private static void seedDefaults(Class<?> cls) {
        try {
            int[] c = colors();
            if (c == null) return;
            setStatic(cls, "sDefBottomViewColor", c[0]);
            setStatic(cls, "sDefIconColorUnpressed", c[1]);
            setStatic(cls, "sDefIconColorPressed", c[2]);
            setStatic(cls, "currentBottomViewColor", c[0]);
            setStatic(cls, "sPhrasePanelBottomViewColorBackup", c[0]);
            HookRegistry.log(String.format("H61 defaults seeded -> #%08X", c[0]));
        } catch (Throwable t) {
            HookRegistry.log("H61 seedDefaults failed: " + t);
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
        if (!enabled() || sApplying) return;
        sApplying = true;
        try {
            applyNavBar(sWindow == null ? null : sWindow.get());
            Class<?> cls = resolveBottomManager(null);
            if (cls == null) return;
            int[] c = colors();
            if (c == null) return;

            View bottom = (View) staticField(cls, "sBottomView");
            if (bottom == null) {
                // 字段名被混淆 / 还没建好：走视图树兜底，不依赖 phrase 内部字段。
                int hits = recolorByTree(c);
                if (hits > 0) {
                    TREE_HITS.addAndGet(hits);
                    APPLIED.incrementAndGet();
                    if (APPLIED.get() <= 4) {
                        HookRegistry.log("H60 tree fallback recolored " + hits + " view(s) -> #"
                                + Integer.toHexString(c[0]));
                    }
                    return;
                }
                if (APPLIED.get() <= 4) {
                    HookRegistry.log("H60 apply skipped: sBottomView == null（等 addMiuiBottomView）");
                }
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
        } finally {
            sApplying = false;
        }
    }


    /**
     * 视图树兜底：从 IME decorView 里找出 MIUI 那条底栏并直接刷色。
     * 判据：android.widget 的 RelativeLayout/LinearLayout、可见、高 1dp~96dp、宽度接近满宽、背景是普通色。
     */
    private static int recolorByTree(int[] c) {
        View decor = sDecor == null ? null : sDecor.get();
        if (decor == null) return 0;
        try {
            java.util.ArrayList<View> out = new java.util.ArrayList<>();
            collectBars(decor, out, 0);
            int hits = 0;
            for (int k = 0; k < out.size(); k++) {
                View v = out.get(k);
                v.setBackgroundColor(c[0]);
                tintChildren(v, c);
                hits++;
            }
            return hits;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void collectBars(View v, java.util.ArrayList<View> out, int depth) {
        if (v == null || depth > 6 || out.size() >= 3) return;
        if (v.getVisibility() != View.VISIBLE) return;
        int hh = v.getHeight();
        int ww = v.getWidth();
        boolean barLike = hh > 0 && hh <= dp(96) && ww > dp(120)
                && (v instanceof RelativeLayout || v instanceof android.widget.LinearLayout)
                && v.getClass().getName().startsWith("android.widget");
        if (barLike) {
            android.graphics.drawable.Drawable bg = v.getBackground();
            if (bg == null || bg instanceof android.graphics.drawable.ColorDrawable) {
                out.add(v);
                return;
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int k = 0; k < g.getChildCount(); k++) {
                collectBars(g.getChildAt(k), out, depth + 1);
            }
        }
    }

    private static void tintChildren(View v, int[] c) {
        if (!(v instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) v;
        for (int k = 0; k < g.getChildCount(); k++) {
            View ch = g.getChildAt(k);
            if (ch instanceof ImageView) {
                ((ImageView) ch).setColorFilter(c[1]);
            } else if (ch instanceof android.widget.TextView) {
                ((android.widget.TextView) ch).setTextColor(c[1]);
            }
        }
    }

    private static int dp(int v) {
        try {
            Context ctx = HookState.context();
            if (ctx == null) return v * 3;
            return (int) (v * ctx.getResources().getDisplayMetrics().density);
        } catch (Throwable t) {
            return v * 3;
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
        int bg = p.kbBg;   // v2.0.3-beta8：底栏与键盘底完全同色
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
                + ";bbdp=" + (sDispatchInstalled ? 1 : 0)
                + ";bbfw=" + (sFrameworkHooked ? 1 : 0)
                + ";bbld=" + (sManagerLoader == null ? "-" : sManagerLoader)
                + ";bbtree=" + TREE_HITS.get()
                + ";bbdecor=" + (sDecor != null && sDecor.get() != null ? 1 : 0)
                + ";bbdark=" + (sSkinDark == null ? "-" : (sSkinDark ? "1" : "0"))
                + ";bbnav=" + NAV_PATCHED.get()
                + ";bbdis=" + (sDisabled ? 1 : 0);
    }

    public static String summary() {
        return "bottom(patched=" + PATCHED.get() + ",applied=" + APPLIED.get()
                + ",manager=" + (sManagerHooked ? "ok" : "no") + ")";
    }

    /** 把皮肤底色刷到系统导航栏（IME 窗口），并同步图标明暗；只改颜色，不动高度/手势条。 */
    private static void applyNavBar(android.view.Window w) {
        if (w == null || sDisabled) return;
        try {
            int[] c = colors();
            if (c == null) return;
            int bg = c[0];
            w.setNavigationBarColor(bg);
            boolean lightBg = IosSkin.contrast(0xFF000000, bg)
                    > IosSkin.contrast(0xFFFFFFFF, bg);
            View decor = w.getDecorView();
            if (decor == null) return;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.view.WindowInsetsController ic = decor.getWindowInsetsController();
                if (ic != null) {
                    int mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    ic.setSystemBarsAppearance(lightBg ? mask : 0, mask);
                }
            } else if (android.os.Build.VERSION.SDK_INT >= 26) {
                int vis = decor.getSystemUiVisibility();
                if (lightBg) vis |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                else vis &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                decor.setSystemUiVisibility(vis);
            }
        } catch (Throwable t) {
            fail("applyNavBar", t);
        }
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
