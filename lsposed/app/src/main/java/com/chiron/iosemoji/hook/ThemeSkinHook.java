package com.chiron.iosemoji.hook;

import android.content.Context;
import android.graphics.Bitmap;

import com.chiron.iosemoji.config.ModuleConfig;
import com.chiron.iosemoji.core.HookState;
import com.chiron.iosemoji.skin.IosSkin;
import com.chiron.iosemoji.skin.SkinPreset;

import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 「改搜狗默认皮肤」的 hook 组 —— 这是取代自绘键盘的方案。
 *
 * <p>搜狗键盘的观感由它自己的皮肤系统决定：
 * <pre>
 *   assets/theme/theme_default/.../layout/*.ini        ← 颜色 / 尺寸 / 图片名
 *   assets/theme/theme_default/.../res/{black,white}/*.png  ← 键帽、键盘底色
 * </pre>
 * 读取入口（均已用 DEX 核对）：
 * <ol>
 *   <li>{@code com.sogou.theme.parse.utils.b#a(String)} —— ini 统一解析入口，返回
 *       {@code parse.entity.b}（内含 {@code ArrayMap<段, ArrayMap<键,值>>}）；</li>
 *   <li>{@code com.sogou.theme.common.j#f(Context,String,boolean,boolean)} —— 皮肤位图统一解码入口。</li>
 * </ol>
 *
 * <p><b>alpha7 起带诊断</b>：每个 hook 都记录「被调用次数 / 真正改动次数 / 没识别出来的图名」，
 * 这些数字会出现在设置页的状态串里（{@code skin(ini=…,img=…,unmatched=…)}）。这样"到底改没改到"
 * 不再靠猜：<b>ini 调用数为 0</b> = 皮肤根本没走这个入口；<b>img 有调用但 redrawn 为 0</b> = 图名不在识别清单里。
 */
public final class ThemeSkinHook {

    /** ini 解析入口：com.sogou.theme.parse.utils.b#a(String) → parse.entity.b */
    private static final String CLS_INI_ENTRY = "com.sogou.theme.parse.utils.b";
    /** ini 行解析器：com.sogou.theme.parse.utils.c#n(BufferedReader) → ArrayMap（旁路探针） */
    private static final String CLS_INI_PARSER = "com.sogou.theme.parse.utils.c";
    /** 位图解码入口：com.sogou.theme.common.j#f(Context,String,boolean,boolean) → Bitmap */
    private static final String CLS_BITMAP = "com.sogou.theme.common.j";

    private static final AtomicInteger INI_CALLS = new AtomicInteger();
    private static final AtomicInteger INI_PATCHED = new AtomicInteger();
    private static final AtomicInteger IMG_CALLS = new AtomicInteger();
    private static final AtomicInteger IMG_REDRAWN = new AtomicInteger();
    /** 图里自带图标/文字（⌫ ⏎ 等），按设计原样保留，不重绘。 */
    private static final AtomicInteger IMG_KEPT = new AtomicInteger();
    /** 图标/菜单/语言标志这类资源，按名字直接跳过（alpha9 起不再碰它们）。 */
    private static final AtomicInteger IMG_PROTECTED = new AtomicInteger();
    private static final AtomicInteger PARSER_CALLS = new AtomicInteger();
    private static final AtomicInteger IMG_BYPASS = new AtomicInteger();
    /** 见过但没认出来的皮肤图名（最多留 12 个，供用户贴日志定位）。 */
    private static final ConcurrentLinkedQueue<String> UNMATCHED = new ConcurrentLinkedQueue<>();
    private static final int UNMATCHED_MAX = 12;
    private static final int LOG_LIMIT = 20;

    private ThemeSkinHook() {
    }

    public static void install(ClassLoader cl) {
        HookRegistry.install("H40_theme_ini", cl, ThemeSkinHook::hookIni);
        HookRegistry.install("H41_theme_bitmap", cl, ThemeSkinHook::hookBitmap);
        HookRegistry.install("H42_theme_ini_parser", cl, ThemeSkinHook::hookIniParser);
        HookRegistry.install("H43_theme_bitmap_cached", cl, ThemeSkinHook::hookBitmapCached);
    }

    // ── H40：ini 段值改写 ─────────────────────────────────────────────────

    private static void hookIni(ClassLoader cl) {
        XposedHelpers.findAndHookMethod(CLS_INI_ENTRY, cl, "a", String.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            INI_CALLS.incrementAndGet();
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

    /** H42：ini 行解析器旁路探针 —— 只计数，用来判断"皮肤到底有没有解析 ini"。 */
    private static void hookIniParser(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(CLS_INI_PARSER, cl);
            for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                if (!"n".equals(m.getName())) continue;
                if (m.getParameterCount() != 1) continue;
                de.robv.android.xposed.XposedBridge.hookAllMethods(cls, "n", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        PARSER_CALLS.incrementAndGet();
                    }
                });
                break;
            }
        } catch (Throwable t) {
            HookRegistry.log("H42 install skipped: " + t);
        }
    }

    // ── H41：皮肤位图重绘 ─────────────────────────────────────────────────

    private static void hookBitmap(ClassLoader cl) {
        XposedHelpers.findAndHookMethod(CLS_BITMAP, cl, "f",
                Context.class, String.class, boolean.class, boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            IMG_CALLS.incrementAndGet();
                            if (!ModuleConfig.themeSkinEnabled()) return;
                            Object res = param.getResult();
                            if (!(res instanceof Bitmap)) return;
                            String path = param.args[1] instanceof String ? (String) param.args[1] : null;
                            if (path == null) return;
                            if (!isSkinPath(path)) return;
                            int kind = IosSkin.kindOf(path);
                            if (kind == IosSkin.KIND_NONE) {
                                // alpha9：删掉「看着像键帽就当键帽」的兜底。
                                // 图标是「透明底 + 单色字形」，亮度分布很均匀，任何形状启发式
                                // 都会把它判成键帽然后刷成一块纯色方块 —— 用户看到的
                                // 「设置齿轮图标」「语言/地区旗帜图标」变方块就是这么来的。
                                if (IosSkin.isProtected(path)) IMG_PROTECTED.incrementAndGet();
                                else remember(path);
                                return;
                            }
                            Context ctx = param.args[0] instanceof Context ? (Context) param.args[0] : null;
                            SkinPreset preset = preset(ctx, path);
                            int color = IosSkin.colorOfKind(path, kind, preset);
                            if (color == 0) {
                                remember(path);
                                return;
                            }
                            Bitmap tinted = IosSkin.synth((Bitmap) res, kind, color, preset);
                            if (tinted == null) {
                                // 原图内含图标/文字（例如 ⌫、⏎），保留搜狗原样
                                IMG_KEPT.incrementAndGet();
                                return;
                            }
                            param.setResult(tinted);
                            int n = IMG_REDRAWN.incrementAndGet();
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

    /**
     * H43：带缓存的位图入口 {@code common.j#g(...)} —— 只计数。
     *
     * <p>如果 g 的调用很多而 f 很少，说明命中缓存、我们没机会重画（换预设后要靠
     * {@link #dropCaches()} 清缓存）。纯粹是诊断信号，不改任何东西。
     */
    private static void hookBitmapCached(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(CLS_BITMAP, cl);
            de.robv.android.xposed.XposedBridge.hookAllMethods(cls, "g", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    IMG_BYPASS.incrementAndGet();
                }
            });
        } catch (Throwable t) {
            HookRegistry.log("H43 install skipped: " + t);
        }
    }

    private static void remember(String path) {
        if (UNMATCHED.size() >= UNMATCHED_MAX) return;
        String n = baseName(path);
        if (!UNMATCHED.contains(n)) UNMATCHED.add(n);
    }

    /**
     * 是否是「皮肤资源」位图。
     *
     * <p>alpha7 放宽了判定：用户装的第三方皮肤可能落在 {@code /themes/<id>/}、{@code .theme/}、
     * {@code unPackThemeTmp/} 这些目录，不再要求路径里同时出现 {@code theme} 与 {@code /res/}。
     * 同时排除表情/动效/商店预览这些绝对不该动的图。
     */
    private static boolean isSkinPath(String path) {
        if (path == null) return false;
        if (path.contains("emoji") || path.contains("expression") || path.contains("lottie")
                || path.contains("preview") || path.contains("face") || path.contains("doutu")) {
            return false;
        }
        return path.contains("theme") || path.contains(".theme/") || path.contains("unPackThemeTmp")
                || path.contains("/themes/") || path.contains("/res/black/") || path.contains("/res/white/");
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

    /** 诊断/状态回显用：一眼看出「改到没改到」。 */
    public static String summary() {
        return "skin(preset=" + ModuleConfig.skinPreset()
                + ",ini=" + INI_CALLS.get() + "/" + INI_PATCHED.get()
                + ",img=" + IMG_CALLS.get() + "/" + IMG_REDRAWN.get()
                + ",parser=" + PARSER_CALLS.get()
                + ",cached=" + IMG_BYPASS.get()
                + ",kept=" + IMG_KEPT.get()
                + ",protected=" + IMG_PROTECTED.get()
                + ",unmatched=" + UNMATCHED.size() + ")";
    }

    /**
     * 状态串里的皮肤字段（用独立 key，值里不再含 {@code =}，方便设置页按 {@code ;}/{@code =} 解析）。
     *
     * <p>读法：{@code skini=<被调用的 ini 次数>/<真正改过的次数>}，
     * {@code skimg=<解码的皮肤图次数>/<真正重画的次数>}。
     * <b>skimg 右侧为 0 就说明图读到了但没认出来</b>，此时看 {@code skip=} 里的图名。
     */
    public static String stateFields() {
        return "skin=" + ModuleConfig.skinPreset()
                + ";skini=" + INI_CALLS.get() + "/" + INI_PATCHED.get()
                + ";skimg=" + IMG_CALLS.get() + "/" + IMG_REDRAWN.get()
                + ";skinq=" + PARSER_CALLS.get()
                + ";skinc=" + IMG_BYPASS.get()
                + ";skink=" + IMG_KEPT.get()
                + ";skinp=" + IMG_PROTECTED.get();
    }

    /** 没识别出来的图名（诊断用，最多 12 个）。 */
    public static String unmatchedNames() {
        if (UNMATCHED.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String s : UNMATCHED) {
            if (sb.length() > 0) sb.append(',');
            sb.append(s);
        }
        return sb.toString();
    }
}
