package com.chiron.iosemoji.hook;

import android.content.Context;
import android.graphics.Bitmap;

import com.chiron.iosemoji.compat.Targets;
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

    /**
     * 当前进程对应定制版的类名表（小米 / OPPO …）。
     *
     * <p>搜狗的定制版之间**混淆结果不同**：小米版主题模块在 {@code com.sogou.theme.*}，
     * OPPO 版被 {@code -repackageclasses ''} 塞进默认包（ini 入口 = {@code defpackage.yy1#a}，
     * 位图入口 = {@code defpackage.nm1#j}）。所以这里不再是写死的常量，而是
     * {@link com.chiron.iosemoji.compat.Targets} 提供的映射；见 {@link #install(ClassLoader, Targets)}。
     */
    private static volatile Targets sTargets = Targets.XIAOMI;

    private static final AtomicInteger INI_CALLS = new AtomicInteger();
    private static final AtomicInteger INI_PATCHED = new AtomicInteger();
    private static final AtomicInteger IMG_CALLS = new AtomicInteger();
    private static final AtomicInteger IMG_REDRAWN = new AtomicInteger();
    /** 图里自带图标/文字（⌫ ⏎ 等），按设计原样保留，不重绘。 */
    private static final AtomicInteger IMG_KEPT = new AtomicInteger();
    /** 图标/菜单/语言标志这类资源，按名字直接跳过（alpha9 起不再碰它们）。 */
    private static final AtomicInteger IMG_PROTECTED = new AtomicInteger();
    private static final AtomicInteger PARSER_CALLS = new AtomicInteger();
    /** 被「只改内置默认皮肤」规则挡掉的资源（第三方皮肤）。 */
    private static final AtomicInteger CUSTOM_SKIPPED = new AtomicInteger();

    /**
     * 铁律：用户自己装的第三方皮肤一律不动（那是他自己挑的外观）。
     * 只认目录特征，不依赖任何渠道类名。
     */
    private static boolean isThirdPartyTheme(String path) {
        if (path == null) return false;
        String p = path.toLowerCase();
        return p.endsWith(".ssf") || p.contains(".ssf/")
                || p.contains("sogou_custom") || p.contains("theme_custom")
                || p.contains("/themes/") || p.contains("phonetheme");
    }
    private static final AtomicInteger IMG_BYPASS = new AtomicInteger();
    /** 见过但没认出来的皮肤图名（最多留 12 个，供用户贴日志定位）。 */
    /** 见到但没处理的资源（带原因 tag），用于「到底走到了哪一步」的诊断。 */
    private static final ConcurrentLinkedQueue<String> SEEN = new ConcurrentLinkedQueue<>();
    private static final int SEEN_MAX = 15;
    private static final ConcurrentLinkedQueue<String> UNMATCHED = new ConcurrentLinkedQueue<>();
    private static final int UNMATCHED_MAX = 12;
    private static final int LOG_LIMIT = 20;

    private ThemeSkinHook() {
    }

    public static void install(ClassLoader cl) {
        install(cl, Targets.XIAOMI);
    }

    /**
     * 按定制版类名表安装。
     *
     * <p>只装这张表里**核对过**的目标；表里为 null 的功能直接跳过并留日志，
     * 避免用猜的名字 hook（猜错是静默失效，比不装更难查）。
     */
    public static void install(ClassLoader cl, Targets targets) {
        if (targets != null) sTargets = targets;
        Targets t = sTargets;
        if (!t.supportsSkin()) {
            HookRegistry.log("skin hooks skipped: " + t.describe());
            return;
        }
        if (t.supportsIniSkin()) {
            HookRegistry.install("H40_theme_ini", cl, ThemeSkinHook::hookIni);
        } else {
            HookRegistry.log("H40 skipped: 该渠道 ini 入口未定位（" + t.label + "）");
        }
        if (t.supportsBitmapSkin()) {
            HookRegistry.install("H41_theme_bitmap", cl, ThemeSkinHook::hookBitmap);
        } else {
            HookRegistry.log("H41 skipped: 该渠道位图入口未定位（" + t.label + "）");
        }
        if (t.themeParserClass != null) {
            HookRegistry.install("H42_theme_ini_parser", cl, ThemeSkinHook::hookIniParser);
        }
    }

    // ── H40：ini 段值改写 ─────────────────────────────────────────────────

    private static void hookIni(ClassLoader cl) {
        final Targets t = sTargets;
        Class<?> iniCls = XposedHelpers.findClass(t.themeIniClass, cl);
        for (final String iniMethod : t.themeIniMethods) {
            try {
                de.robv.android.xposed.XposedBridge.hookAllMethods(iniCls, iniMethod,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            INI_CALLS.incrementAndGet();
                            if (!ModuleConfig.themeSkinEnabled()) return;
                            Object entity = param.getResult();
                            if (entity == null) return;
                            Object raw = asSections(entity);
                            if (!(raw instanceof Map)) return;
                            @SuppressWarnings("unchecked")
                            Map<String, Map<String, String>> sections = (Map<String, Map<String, String>>) raw;
                            String path = param.args[0] instanceof String ? (String) param.args[0] : "";
                            // 铁律 1：只改内置默认皮肤；用户自己装的第三方皮肤一律不碰
                            //（改坏了就是「键盘呼不出来」，beta3 的教训）
                            if (isThirdPartyTheme(path)) {
                                CUSTOM_SKIPPED.incrementAndGet();
                                return;
                            }
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
            } catch (Throwable ignored) {
                // 该名字在这一版不存在，换下一个
            }
        }
    }

    /** H42：ini 行解析器旁路探针 —— 只计数，用来判断"皮肤到底有没有解析 ini"。 */
    private static void hookIniParser(ClassLoader cl) {
        try {
            final Targets t = sTargets;
            if (t.themeParserClass == null) return;
            Class<?> cls = XposedHelpers.findClass(t.themeParserClass, cl);
            final String parserMethod = t.themeParserMethod == null ? "n" : t.themeParserMethod;
            for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                if (!parserMethod.equals(m.getName())) continue;
                if (m.getParameterCount() != 1) continue;
                de.robv.android.xposed.XposedBridge.hookAllMethods(cls, parserMethod, new XC_MethodHook() {
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

    /**
     * 位图入口的候选方法名（不同定制版不同）：
     * 小米版 {@code common.j#f(...)} / 带缓存 {@code #g(...)} / Drawable {@code #d(...)}；
     * OPPO 定制版 {@code nm1#j(...)} / 带缓存 {@code #k(...)} / Drawable {@code #e(...)}。
     * 用 {@code hookAllMethods}（只按方法名）逐个挂，命中哪个算哪个 ——
     * 因为各版参数个数不一样，写死签名会在另一版上静默失配。
     */
    private static final String[] GETTER_NAMES = {"f", "g", "j", "k", "d", "e"};

    private static void hookBitmap(ClassLoader cl) {
        final Targets t = sTargets;
        try {
            int hooked = 0;
            int classCount = 0;
            for (String clsName : t.bitmapClasses) {
            Class<?> cls;
            try {
                cls = XposedHelpers.findClass(clsName, cl);
            } catch (Throwable notFound) {
                continue;
            }
            classCount++;
            for (String name : t.bitmapMethods) {
                try {
                    de.robv.android.xposed.XposedBridge.hookAllMethods(cls, name, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            onBitmapResult(param);
                        }
                    });
                    hooked++;
                } catch (Throwable ignored) {
                    // 该名字在这一版不存在，换下一个
                }
            }
            }
            HookRegistry.log("H41 bitmap getters hooked on " + classCount + " class(es), " + hooked + " method name(s)");
        } catch (Throwable t2) {
            HookRegistry.log("H41 install failed: " + t2);
        }
    }

    private static void onBitmapResult(XC_MethodHook.MethodHookParam param) {
        try {
            IMG_CALLS.incrementAndGet();
            Object res = param.getResult();
            Bitmap src = asBitmap(res);
            if (src == null) return;
            String path = pickPath(param);
            if (path == null) return;
            if (isThirdPartyTheme(path)) {
                CUSTOM_SKIPPED.incrementAndGet();
                return;
            }
            String name = baseName(path);
            if (!isSkinPath(path, name)) {
                // 诊断：把「被路径过滤掉」的图名列出来，避免「什么都没发生」这种无信息状态
                seen("skip-path", path);
                return;
            }
            if (!ModuleConfig.themeSkinEnabled()) return;
            int kind = IosSkin.kindOf(name);
            if (kind == IosSkin.KIND_NONE) {
                // 删掉「看着像键帽就当键帽」的兜底：图标是「透明底 + 单色字形」，
                // 任何形状启发式都会把图标刷成纯色方块（设置齿轮、语言旗帜就是这么坏的）。
                seen("unknown", path);
                if (IosSkin.isProtected(name)) IMG_PROTECTED.incrementAndGet();
                else remember(path);
                return;
            }
            Context ctx = pickContext(param);
            SkinPreset preset = preset(ctx, path);
            int color = IosSkin.colorOfKind(name, kind, preset);
            if (color == 0) {
                remember(path);
                return;
            }
            Bitmap tinted = IosSkin.synth(src, kind, color, preset);
            if (tinted == null) {
                // 原图内含图标/文字（⌫、⏎ 等），保留搜狗原样
                IMG_KEPT.incrementAndGet();
                return;
            }
            if (res instanceof Bitmap) {
                param.setResult(tinted);
            } else {
                param.setResult(wrapDrawable(ctx, res, tinted));
            }
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

    /** 结果可能是 Bitmap，也可能是 BitmapDrawable —— 统一取成 Bitmap 处理。 */
    private static Bitmap asBitmap(Object res) {
        if (res instanceof Bitmap) return (Bitmap) res;
        if (res instanceof android.graphics.drawable.BitmapDrawable) {
            return ((android.graphics.drawable.BitmapDrawable) res).getBitmap();
        }
        return null;
    }

    /** 原来是 Drawable 的返回 Drawable，别把类型换掉（调用方会 {@code findViewById} 式强转）。 */
    private static Object wrapDrawable(Context ctx, Object original, Bitmap tinted) {
        if (ctx == null) ctx = HookState.context();
        if (ctx == null) return tinted;
        return new android.graphics.drawable.BitmapDrawable(ctx.getResources(), tinted);
    }

    /** 从参数里挑出资源路径：第一个「含 / 或以图片/ini 结尾」的 String。 */
    private static String pickPath(XC_MethodHook.MethodHookParam param) {
        if (param.args == null) return null;
        for (Object a : param.args) {
            if (!(a instanceof String)) continue;
            String s = (String) a;
            if (s.indexOf('/') >= 0 || s.endsWith(".png") || s.endsWith(".ini") || s.endsWith(".webp")) {
                return s;
            }
        }
        return null;
    }

    /** 从参数里挑出 Context（可选）。 */
    private static Context pickContext(XC_MethodHook.MethodHookParam param) {
        if (param.args != null) {
            for (Object a : param.args) {
                if (a instanceof Context) return (Context) a;
            }
        }
        return HookState.context();
    }

    /** 诊断：记录「见到但没处理」的图名（最多 15 个），配合 tag 说明原因。 */
    private static void seen(String tag, String path) {
        if (SEEN.size() >= SEEN_MAX) return;
        String line = tag + ":" + baseName(path);
        if (!SEEN.contains(line)) SEEN.add(line);
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

    /** 路径不像皮肤、但<b>名字</b>像皮肤位图时也放行（官方/OPPO 会传相对名或逻辑名）。 */
    private static boolean isSkinPath(String path, String name) {
        if (isSkinPath(path)) return true;
        if (looksLikeSkinImage(name)) {
            // 名字像皮肤图，但仍要排除表情/动效等：这些名字里通常带 emoji/expression 等关键词
            return !(path != null && (path.contains("emoji") || path.contains("expression")
                    || path.contains("lottie") || path.contains("preview") || path.contains("face")));
        }
        return false;
    }

    private static String baseName(String path) {
        if (path == null) return "?";
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(i + 1) : path;
    }

    /**
     * 把 ini 结果统一取成「段 → 键 → 值」的可变 Map。
     *
     * <p>小米/官方/OPPO 返回 {@code com.sogou.theme.parse.entity.b}（要调 {@code b()} 拿 ArrayMap），
     * vivo 直接返回 {@code ArrayMap}。两种都要吃，否则某个渠道会「一次都没改到」。
     */
    private static Object asSections(Object entity) {
        if (entity instanceof Map) return entity;
        try {
            return XposedHelpers.callMethod(entity, "b");
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 逻辑名像不像「皮肤位图」。
     *
     * <p>官方版传给位图取值器的不一定是完整路径，可能只是 {@code candidate_bg_tab}、
     * {@code key_qwerty}、{@code key_qwerty_png} 这种逻辑名。用 {@link #isSkinPath} 那样要求
     * 路径里含 {@code theme} 会把它们全过滤掉 —— 这正是 beta1「官方版只有工具栏变色、
     * 键帽不动」的原因。
     */
    private static boolean looksLikeSkinImage(String name) {
        if (name == null || name.isEmpty()) return false;
        String n = name.toLowerCase(java.util.Locale.US);
        if (n.startsWith("candidate") || n.startsWith("cand_") || n.startsWith("first_cand")
                || n.startsWith("more_cands") || n.startsWith("function") || n.startsWith("float_function")
                || n.startsWith("cloud") || n.startsWith("bg_cloud") || n.startsWith("bg_composing")
                || n.startsWith("composing") || n.startsWith("toolbar") || n.startsWith("header")
                || n.startsWith("key") || n.startsWith("popup") || n.startsWith("bg")
                || n.startsWith("blank_shadow") || n.startsWith("container_key")) {
            return true;
        }
        return false;
    }

    /** 取当前预设（含深色判定）；{@code ctx} 只给「莫奈取色」用来读系统动态配色。 */
    private static SkinPreset preset(Context ctx, String path) {
        boolean dark = IosSkin.isDark(path, ctx);
        if (path != null) {
            // 搜狗皮肤自带 black/white 时以它为准：让 MIUI 底栏跟着「实际渲染的键盘」同色
            if (path.contains("/black/")) MiuiBottomBarHook.noteSkinDark(true);
            else if (path.contains("/white/")) MiuiBottomBarHook.noteSkinDark(false);
        }
        return SkinPreset.of(ModuleConfig.skinPreset(), dark, ctx);
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
            Class<?> cls = null;
            for (String clsName : sTargets.bitmapClasses) {
                try {
                    cls = XposedHelpers.findClass(clsName, cl);
                    break;
                } catch (Throwable ignored) {
                    cls = null;
                }
            }
            if (cls == null) return; // 这个渠道没定位到位图类，清缓存失败不影响任何事
            // 单例取法 / 缓存取法各定制版不同：小米 e()/j()，OPPO 定制版 f()/n()。两个都试。
            Object mgr = null;
            for (String name : new String[]{"e", "f"}) {
                try {
                    mgr = XposedHelpers.callStaticMethod(cls, name);
                } catch (Throwable ignored) {
                    mgr = null;
                }
                if (mgr != null) break;
            }
            if (mgr == null) return;
            Object cache = null;
            for (String name : new String[]{"j", "n"}) {
                try {
                    cache = XposedHelpers.callMethod(mgr, name);
                } catch (Throwable ignored) {
                    cache = null;
                }
                if (cache != null) break;
            }
            if (cache == null) return;
            XposedHelpers.callMethod(cache, "clear");
            HookRegistry.log("skin caches dropped (preset switch)");
            MiuiBottomBarHook.reapply();
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
                + ";skinp=" + IMG_PROTECTED.get()
                + ";skint=" + IosSkin.contrastFixes()
                + ";skinse=" + SEEN.size()
                + ";skintp=" + CUSTOM_SKIPPED.get();
    }

    /** 见到但没处理的名字（带原因 tag），诊断用。 */
    public static String seenNames() {
        if (SEEN.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String s : SEEN) {
            if (sb.length() > 0) sb.append(',');
            sb.append(s);
        }
        return sb.toString();
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
