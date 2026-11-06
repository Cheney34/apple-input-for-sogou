package com.chiron.iosemoji.hook;

import android.content.Context;
import android.graphics.Bitmap;

import com.chiron.iosemoji.config.ModuleConfig;
import com.chiron.iosemoji.core.HookState;
import com.chiron.iosemoji.skin.IosSkin;
import com.chiron.iosemoji.skin.SkinPreset;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 「改搜狗默认皮肤」的两个 hook —— 这是取代自绘键盘的方案。
 *
 * <p>搜狗键盘的观感由它自己的皮肤系统决定，链路是：
 * <pre>
 *   assets/theme/theme_default/.../layout/*.ini      ← 颜色/尺寸/图片名
 *   assets/theme/theme_default/.../res/{black,white}/*.png  ← 键帽、键盘底色
 * </pre>
 * 读取入口只有两处（均已用 DEX 核对）：
 * <ol>
 *   <li>{@code com.sogou.theme.parse.utils.b#a(String)} —— 所有 ini 的统一解析入口，
 *       返回 {@code com.sogou.theme.parse.entity.b}，内部持有 {@code ArrayMap<段, ArrayMap<键,值>>}；</li>
 *   <li>{@code com.sogou.theme.common.j#f(Context,String,boolean,boolean)} —— 所有皮肤位图的统一解码入口。</li>
 * </ol>
 * 我们只在这两处**改值/重上色**，布局、命中区、insets 全部保持搜狗原样，
 * 因此不会再出现"键盘样式卡 bug"。
 *
 * <p>开关：{@code ModuleConfig.themeSkinEnabled()}，关掉即完全恢复原皮肤（双向可逆）。
 */
public final class ThemeSkinHook {

    /** ini 解析入口：com.sogou.theme.parse.utils.b#a(String) → parse.entity.b */
    private static final String CLS_INI_ENTRY = "com.sogou.theme.parse.utils.b";
    /** 位图解码入口：com.sogou.theme.common.j#f(Context,String,boolean,boolean) → Bitmap */
    private static final String CLS_BITMAP = "com.sogou.theme.common.j";

    private static final AtomicInteger INI_PATCHED = new AtomicInteger();
    private static final AtomicInteger IMG_PATCHED = new AtomicInteger();
    private static final int LOG_LIMIT = 12;

    private ThemeSkinHook() {
    }

    public static void install(ClassLoader cl) {
        HookRegistry.install("H40_theme_ini", cl, new HookRegistry.Installer() {
            @Override
            public void install(ClassLoader classLoader) {
                hookIni(classLoader);
            }
        });
        HookRegistry.install("H41_theme_bitmap", cl, new HookRegistry.Installer() {
            @Override
            public void install(ClassLoader classLoader) {
                hookBitmap(classLoader);
            }
        });
    }

    // ── H40：ini 段值改写 ─────────────────────────────────────────────────

    private static void hookIni(ClassLoader cl) {
        XposedHelpers.findAndHookMethod(CLS_INI_ENTRY, cl, "a", String.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (!ModuleConfig.themeSkinEnabled()) return;
                            Object entity = param.getResult();
                            if (entity == null) return;
                            Object raw = XposedHelpers.callMethod(entity, "b");
                            if (!(raw instanceof Map)) return;
                            @SuppressWarnings("unchecked")
                            Map<String, Map<String, String>> sections = (Map<String, Map<String, String>>) raw;
                            String path = param.args[0] instanceof String ? (String) param.args[0] : "";
                            SkinPreset preset = preset(HookState.context(), path);
                            if (!IosSkin.patchIni(path, sections, preset)) return;
                            int n = INI_PATCHED.incrementAndGet();
                            if (n <= LOG_LIMIT) {
                                HookRegistry.log("H40 skin ini patched: " + baseName(path)
                                        + " preset=" + preset.id
                                        + " (total " + n + ")");
                            }
                        } catch (Throwable t) {
                            HookRegistry.log("H40 patch failed: " + t);
                        }
                    }
                });
    }

    // ── H41：皮肤位图重上色 ───────────────────────────────────────────────

    private static void hookBitmap(ClassLoader cl) {
        XposedHelpers.findAndHookMethod(CLS_BITMAP, cl, "f",
                android.content.Context.class, String.class, boolean.class, boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (!ModuleConfig.themeSkinEnabled()) return;
                            Object res = param.getResult();
                            if (!(res instanceof Bitmap)) return;
                            String path = param.args[1] instanceof String ? (String) param.args[1] : null;
                            if (path == null) return;
                            // 只动「皮肤资源」，其它位图（表情、图标、商店预览）一律不碰
                            boolean skinPath = (path.contains("theme") && path.contains("/res/"))
                                    || path.contains("/black/") || path.contains("/white/");
                            if (!skinPath) return;
                            int kind = IosSkin.kindOf(path);
                            if (kind == IosSkin.KIND_NONE) return;
                            Context ctx = param.args[0] instanceof Context ? (Context) param.args[0] : null;
                            SkinPreset preset = preset(ctx, path);
                            int color = IosSkin.colorOf(path, preset);
                            if (color == 0) return;
                            Bitmap tinted = IosSkin.synth((Bitmap) res, kind, color, preset);
                            if (tinted == null) return;
                            param.setResult(tinted);
                            int n = IMG_PATCHED.incrementAndGet();
                            if (n <= LOG_LIMIT) {
                                HookRegistry.log("H41 skin bitmap redrawn: " + baseName(path)
                                        + " kind=" + kind
                                        + " preset=" + preset.id
                                        + " -> " + String.format("#%08X", color)
                                        + " (total " + n + ")");
                            }
                        } catch (Throwable t) {
                            HookRegistry.log("H41 redraw failed: " + t);
                        }
                    }
                });
    }

    private static String baseName(String path) {
        if (path == null) return "?";
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(i + 1) : path;
    }

    /** 取当前预设（含深色判定）；{@code ctx} 只给「莫奈取色」用来读系统动态配色。 */
    private static SkinPreset preset(Context ctx, String path) {
        return SkinPreset.of(ModuleConfig.skinPreset(), IosSkin.isDarkPath(path), ctx);
    }

    /**
     * 丢掉搜狗的皮肤位图缓存 —— 切换预设后必须调用，否则拿到的还是上一套配色。
     *
     * <p>{@code com.sogou.theme.common.j#e()} 单例 → {@code j()} 返回
     * {@code com.sogou.theme.common.e<String,Bitmap>} 缓存 → 清空它。
     * 全程 try/catch：清不掉只是"下次重启才换色"，不影响稳定性。
     */
    public static void dropCaches() {
        try {
            ClassLoader cl = ThemeSkinHook.class.getClassLoader();
            if (cl == null) return;
            Class<?> cls;
            try {
                cls = XposedHelpers.findClass(CLS_BITMAP, cl);
            } catch (Throwable notFound) {
                return; // 这个版本没这个类，清缓存失败不影响任何事
            }
            Object mgr = XposedHelpers.callStaticMethod(cls, "e");
            if (mgr == null) return;
            Object cache = XposedHelpers.callMethod(mgr, "j");
            if (cache == null) return;
            XposedHelpers.callMethod(cache, "clear");
            HookRegistry.log("skin caches dropped (preset switch)");
        } catch (Throwable t) {
            HookRegistry.log("skin cache drop failed: " + t);
        }
    }

    /** 诊断/状态回显用。 */
    public static String summary() {
        return "skin(preset=" + ModuleConfig.skinPreset()
                + ",ini=" + INI_PATCHED.get() + ",img=" + IMG_PATCHED.get() + ")";
    }
}
