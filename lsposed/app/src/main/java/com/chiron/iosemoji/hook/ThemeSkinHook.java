package com.chiron.iosemoji.hook;

import android.graphics.Bitmap;

import com.chiron.iosemoji.config.ModuleConfig;
import com.chiron.iosemoji.skin.IosSkin;

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
                            if (!IosSkin.patchIni(path, sections)) return;
                            int n = INI_PATCHED.incrementAndGet();
                            if (n <= LOG_LIMIT) {
                                HookRegistry.log("H40 skin ini patched: " + baseName(path)
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
                            boolean dark = IosSkin.isDarkPath(path);
                            int color = IosSkin.colorOf(path, dark);
                            if (color == 0) return;
                            Bitmap tinted = IosSkin.synth((Bitmap) res, kind, color, dark);
                            if (tinted == null) return;
                            param.setResult(tinted);
                            int n = IMG_PATCHED.incrementAndGet();
                            if (n <= LOG_LIMIT) {
                                HookRegistry.log("H41 skin bitmap redrawn: " + baseName(path)
                                        + " kind=" + kind
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

    /** 诊断/状态回显用。 */
    public static String summary() {
        return "skin(ini=" + INI_PATCHED.get() + ",img=" + IMG_PATCHED.get() + ")";
    }
}
