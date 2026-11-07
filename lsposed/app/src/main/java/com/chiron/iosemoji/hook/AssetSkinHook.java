package com.chiron.iosemoji.hook;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.chiron.iosemoji.config.ModuleConfig;
import com.chiron.iosemoji.core.HookState;
import com.chiron.iosemoji.skin.IosSkin;
import com.chiron.iosemoji.skin.SkinPreset;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 资源级（AssetManager）皮肤替换 —— <b>不依赖任何混淆类名</b>。
 *
 * <p>为什么要有这一层：小米版、OPPO 定制版（以及以后任何 OEM 版）都会把
 * 主题解析类和位图类改名（OPPO 甚至整包塞进 {@code defpackage}），
 * 但<b>它们读皮肤资源的物理动作永远是 {@code context.getAssets().open("theme/...")}</b>。
 * 所以在这一层拦截，一次写好，所有版本通吃，也不怕版本升级改名。
 *
 * <p><b>三条安全铁律（v2.0.2-beta4 血的教训）</b>：
 * <ol>
 *   <li><b>只改内置默认皮肤</b>：{@code theme/theme_default/…}（含其 {@code res/black|white}）。
 *       用户自己装的第三方皮肤（{@code .ssf} 解包到 {@code /sogou_custom/}、{@code /themes/}、
 *       {@code unPackThemeTmp} 等）一律<b>不碰</b> —— 那是用户自己挑的外观，
 *       而且第三方皮肤里可能有我们没见过的写法，改坏了就是「键盘呼不出来」。</li>
 *   <li><b>ini 只做行内取值替换</b>：绝不重新序列化整个文件（早期版本把整份 ini 解析成
 *       段表再重新拼回去，会丢掉注释、续行、重复键、无等号行 —— 第三方皮肤一旦这样被弄坏，
 *       主题解析直接抛异常，键盘就起不来了）。现在的做法是：先用段表算出"哪几个键要变成什么"，
 *       再回到<b>原始文本</b>里逐行替换这几个键的值，其余字节原封不动。</li>
 *   <li><b>熔断</b>：连续失败 {@link #FAIL_MAX} 次就整个进程停用这一层（并记日志），
 *       宁可没有皮肤，也不能让输入法起不来。</li>
 * </ol>
 */
public final class AssetSkinHook {

    private static final int SAMPLE_MAX = 16;
    private static final int CACHE_MAX = 256;
    private static final int FAIL_MAX = 3;

    private static final AtomicInteger ASSET_OPEN = new AtomicInteger();
    private static final AtomicInteger THEME_OPEN = new AtomicInteger();
    private static final AtomicInteger INI_PATCHED = new AtomicInteger();
    private static final AtomicInteger IMG_PATCHED = new AtomicInteger();
    private static final AtomicInteger IMG_SKIPPED = new AtomicInteger();
    private static final AtomicInteger INI_SKIPPED = new AtomicInteger();
    private static final AtomicInteger SKIPPED_CUSTOM = new AtomicInteger();
    private static final AtomicInteger FAILS = new AtomicInteger();

    private static final List<String> SAMPLES = new ArrayList<>();
    private static final Map<String, byte[]> CACHE = new ConcurrentHashMap<>();
    private static volatile boolean sInstalled;
    /** 熔断开关：true = 本进程不再改写任何资源。 */
    private static volatile boolean sTripped;

    private AssetSkinHook() {
    }

    public static void install(ClassLoader cl) {
        if (sInstalled) return;
        try {
            XposedHelpers.findAndHookMethod(AssetManager.class, "open",
                    String.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            onOpen(param, (String) param.args[0]);
                        }
                    });
            try {
                XposedHelpers.findAndHookMethod(AssetManager.class, "open",
                        String.class, int.class, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                onOpen(param, (String) param.args[0]);
                            }
                        });
            } catch (Throwable ignored) {
                // 某些 ROM 没有这个重载，忽略
            }
            sInstalled = true;
            HookRegistry.log("H50 asset skin hook ready (open/open+mode)");
        } catch (Throwable t) {
            HookRegistry.log("H50 asset skin hook failed");
            HookRegistry.log(t);
        }
    }

    private static void onOpen(XC_MethodHook.MethodHookParam param, String path) {
        if (path == null || sTripped) return;
        try {
            ASSET_OPEN.incrementAndGet();
            if (!isThemeAsset(path)) return;
            THEME_OPEN.incrementAndGet();
            sample(path);

            Object result = param.getResult();
            if (!(result instanceof InputStream)) return;
            if (!ModuleConfig.assetSkinEnabled()) return;

            // 铁律 1：第三方皮肤一个字都不改
            if (!isDefaultTheme(path)) {
                SKIPPED_CUSTOM.incrementAndGet();
                return;
            }

            String presetId = ModuleConfig.skinPreset();
            String cacheKey = path + '|' + presetId;
            byte[] cached = CACHE.get(cacheKey);
            if (cached != null) {
                param.setResult(new ByteArrayInputStream(cached));
                return;
            }

            byte[] raw = readAll((InputStream) result);
            if (raw == null || raw.length == 0) return;

            byte[] out = null;
            if (isIni(path) && !isColorIni(path)) {
                INI_SKIPPED.incrementAndGet();
                return;
            }
            if (isIni(path)) {
                out = patchIniBytes(path, raw, presetId);
                if (out != null) INI_PATCHED.incrementAndGet();
            } else if (isImage(path)) {
                out = patchImageBytes(path, raw, presetId);
                if (out != null) IMG_PATCHED.incrementAndGet();
                else IMG_SKIPPED.incrementAndGet();
            }
            if (out == null) return;

            if (CACHE.size() < CACHE_MAX) CACHE.put(cacheKey, out);
            param.setResult(new ByteArrayInputStream(out));
            if (INI_PATCHED.get() + IMG_PATCHED.get() <= 6) {
                HookRegistry.log("H50 patched asset: " + path
                        + " (" + raw.length + " -> " + out.length + "B)");
            }
        } catch (Throwable t) {
            trip("onOpen", t);
        }
    }

    /** 清缓存（换预设/重启后必须调用，否则还是上一套颜色）。 */
    public static void reset() {
        CACHE.clear();
    }

    // ---------------------------------------------------------------- ini

    /**
     * 铁律 2：<b>行内取值替换</b>，不重新序列化。
     *
     * <p>流程：原文 → 解析成段表副本 → 交给 {@link IosSkin#patchIni} 算出"哪些键要变" →
     * 拿这份 diff 回到<b>原始文本</b>逐行替换值 → 其余行（注释、续行、空行、顺序）原样保留。
     */
    private static byte[] patchIniBytes(String path, byte[] raw, String presetId) {
        String text = null;
        try {
            // ISO-8859-1 是字节级往返，不会动任何非 ASCII 值
            text = new String(raw, "ISO-8859-1");
            Map<String, Map<String, String>> original = parseIni(text);
            if (original.isEmpty()) return null;

            Map<String, Map<String, String>> copy = deepCopy(original);
            Context ctx = HookState.context();
            SkinPreset p = SkinPreset.of(presetId, IosSkin.isDark(path, ctx), ctx);
            if (!IosSkin.patchIni(path, copy, p)) return null;

            Map<String, Map<String, String>> diff = diff(original, copy);
            if (diff.isEmpty()) return null;

            String out = applyDiff(text, diff);
            if (out == null) return null;

            // 复核：改完段数/段名必须与原文完全一致，否则宁可原样返回
            if (!sameSkeleton(original, parseIni(out))) {
                trip("skeleton check failed on " + path, null);
                return null;
            }
            return out.getBytes("ISO-8859-1");
        } catch (Throwable t) {
            trip("patchIniBytes " + path, t);
            return null;
        }
    }

    private static Map<String, Map<String, String>> parseIni(String text) {
        Map<String, Map<String, String>> sections = new LinkedHashMap<>();
        Map<String, String> cur = null;
        for (String line : text.split("\n", -1)) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith(";") || s.startsWith("#")) continue;
            if (s.startsWith("[") && s.endsWith("]")) {
                String name = s.substring(1, s.length() - 1).trim();
                cur = sections.get(name);
                if (cur == null) {
                    cur = new LinkedHashMap<>();
                    sections.put(name, cur);
                }
                continue;
            }
            if (cur == null) continue;
            int eq = s.indexOf('=');
            if (eq <= 0) continue;
            cur.put(s.substring(0, eq).trim(), s.substring(eq + 1).trim());
        }
        return sections;
    }

    private static Map<String, Map<String, String>> deepCopy(
            Map<String, Map<String, String>> src) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> e : src.entrySet()) {
            out.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
        }
        return out;
    }

    /** 只挑"值真的变了"的键，避免把没动的行也重写。 */
    private static Map<String, Map<String, String>> diff(
            Map<String, Map<String, String>> a, Map<String, Map<String, String>> b) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> e : b.entrySet()) {
            Map<String, String> before = a.get(e.getKey());
            Map<String, String> changed = new LinkedHashMap<>();
            for (Map.Entry<String, String> kv : e.getValue().entrySet()) {
                String old = before == null ? null : before.get(kv.getKey());
                if (old == null) continue;                 // 不新增键
                if (!old.equals(kv.getValue())) changed.put(kv.getKey(), kv.getValue());
            }
            if (!changed.isEmpty()) out.put(e.getKey(), changed);
        }
        return out;
    }

    /** 在原文上按行替换值：只动 {@code KEY=} 后面那次出现，前面的缩进/空白/行尾全部保留。 */
    private static String applyDiff(String text, Map<String, Map<String, String>> diff) {
        String[] lines = text.split("\n", -1);
        String cur = null;
        int touched = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String s = line.trim();
            if (s.startsWith("[") && s.endsWith("]")) {
                cur = s.substring(1, s.length() - 1).trim();
                continue;
            }
            if (cur == null || s.startsWith(";") || s.startsWith("#")) continue;
            int eq = s.indexOf('=');
            if (eq <= 0) continue;
            String key = s.substring(0, eq).trim();
            Map<String, String> sec = diff.get(cur);
            if (sec == null) continue;
            String nv = sec.get(key);
            if (nv == null) continue;

            int pos = line.indexOf('=');
            if (pos < 0) continue;
            String head = line.substring(0, pos + 1);
            String tail = line.substring(pos + 1);
            // 保留行尾 \r
            String cr = tail.endsWith("\r") ? "\r" : "";
            if (cr.length() > 0) tail = tail.substring(0, tail.length() - 1);
            // 保留原有的前导空白
            int k = 0;
            while (k < tail.length() && (tail.charAt(k) == ' ' || tail.charAt(k) == '\t')) k++;
            String lead = tail.substring(0, k);
            lines[i] = head + lead + nv + cr;
            touched++;
        }
        if (touched == 0) return null;
        return String.join("\n", lines);
    }

    private static boolean sameSkeleton(Map<String, Map<String, String>> a,
                                       Map<String, Map<String, String>> b) {
        if (a.size() != b.size()) return false;
        for (String name : a.keySet()) {
            Map<String, String> sb = b.get(name);
            if (sb == null) return false;
            if (sb.size() != a.get(name).size()) return false;
        }
        return true;
    }

    // ------------------------------------------------------------- images

    private static byte[] patchImageBytes(String path, byte[] raw, String presetId) {
        try {
            // ★ 铁律 0：nine-patch 绝对不重画。
            // decode → compress 会把 PNG 里的 npTc（九宫格切边数据）整块丢掉，
            // 搜狗/系统 Inflate 这张图时会直接抛异常 —— 现象就是「键盘呼不出来」。
            if (isNinePatch(path, raw)) return null;
            // 资源层默认只兜底 ini 颜色；位图交给类名层（H41）去做，避免影响启动。
            if (!ModuleConfig.assetSkinImagesEnabled()) return null;
            Bitmap src = BitmapFactory.decodeByteArray(raw, 0, raw.length);
            if (src == null) return null;
            int kind = IosSkin.kindOf(path);
            if (kind == IosSkin.KIND_NONE) {
                src.recycle();
                return null;
            }
            Context ctx = HookState.context();
            SkinPreset p = SkinPreset.of(presetId, IosSkin.isDark(path, ctx), ctx);
            int color = IosSkin.colorOfKind(path, kind, p);
            if (color == 0) {
                src.recycle();
                return null;
            }
            Bitmap out = IosSkin.synth(src, kind, color, p);
            src.recycle();
            if (out == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream(raw.length / 2 + 512);
            out.compress(Bitmap.CompressFormat.PNG, 100, bos);
            out.recycle();
            return bos.toByteArray();
        } catch (Throwable t) {
            trip("patchImageBytes " + path, t);
            return null;
        }
    }

    /**
     * 是不是 nine-patch：文件名字面 {@code .9.png}，或 PNG 里带 {@code npTc} 块（九宫格切边）。
     * 这类图一旦被 decode→compress 重编码，切边数据就没了，系统加载会崩。
     */
    private static boolean isNinePatch(String path, byte[] raw) {
        if (path != null && path.toLowerCase().endsWith(".9.png")) return true;
        if (raw == null || raw.length < 16) return false;
        for (int i = 0; i + 3 < raw.length; i++) {
            if (raw[i] == 'n' && raw[i + 1] == 'p' && raw[i + 2] == 'T' && raw[i + 3] == 'c') return true;
        }
        return false;
    }

    // -------------------------------------------------------------- utils

    /**
     * ini 白名单：只碰纯颜色/候选样式的文件。
     * file_name_mapping_*.ini、thememanifest.ini、theme.ini、layout/template.ini、keys.ini、
     * texts.ini、images_config.ini、image_list.ini 都是<b>结构性</b>文件（映射/模板/九宫格切边），
     * 改写它们会让主题解析失败、键盘直接起不来 —— 一律不碰。
     */
    private static final String[] INI_WHITELIST = {
            "colors.ini", "cands.ini", "cands_outer.ini", "phoneskin.ini"
    };

    private static boolean isColorIni(String path) {
        if (path == null) return false;
        String n = path.toLowerCase(java.util.Locale.ROOT);
        int i = n.lastIndexOf('/');
        if (i >= 0) n = n.substring(i + 1);
        for (String w : INI_WHITELIST) {
            if (n.equals(w)) return true;
        }
        return false;
    }
    /** 只认皮肤相关资源，别去碰 emoji / lottie / 字体。 */
    private static boolean isThemeAsset(String path) {
        if (path == null || path.length() < 8) return false;
        String p = path.toLowerCase();
        if (p.contains("emoji") || p.contains("expression") || p.contains("lottie")
                || p.contains("font") || p.contains("doutu") || p.contains("face")) {
            return false;
        }
        boolean inTheme = p.startsWith("theme/") || p.contains("/theme/")
                || p.contains("theme_default") || p.contains("res/black")
                || p.contains("res/white") || p.contains("/layout/")
                || p.contains("/port/") || p.contains("/land/");
        if (!inTheme) return false;
        return isIni(p) || isImage(p);
    }

    /**
     * 铁律 1：只有**内置默认皮肤**才改。
     * 第三方皮肤（用户下载的 .ssf/主题包）解包后落在这几类目录里，一律放过：
     * {@code sogou_custom}、{@code theme_custom}、{@code /themes/}、{@code .ssf}、{@code theme_custom}、
     * {@code unPackThemeTmp}、{@code phoneTheme.ini}。
     */
    public static boolean isDefaultTheme(String path) {
        if (path == null) return false;
        String p = path.toLowerCase();
        // 主题包本身、用户下载的第三方皮肤目录：一个字都不改
        if (p.endsWith(".ssf") || p.contains(".ssf/")) return false;
        if (p.contains("sogou_custom") || p.contains("theme_custom") || p.contains("/themes/")) {
            return false;
        }
        if (p.contains("phonetheme")) return false;
        // 其余只要是「主题资源」(assets/theme/… 或缓存的 .theme/、unPackThemeTmp 解包目录) 就认作默认。
        return isThemeAsset(path);
    }

    private static boolean isIni(String path) {
        return path.endsWith(".ini");
    }

    private static boolean isImage(String path) {
        return path.endsWith(".png") || path.endsWith(".webp");
    }

    private static byte[] readAll(InputStream in) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void sample(String path) {
        synchronized (SAMPLES) {
            if (SAMPLES.size() >= SAMPLE_MAX) return;
            if (!SAMPLES.contains(path)) SAMPLES.add(path);
        }
    }

    /** 熔断：连续失败就把这一层整体关掉，绝不让输入法起不来。 */
    private static void trip(String where, Throwable t) {
        int n = FAILS.incrementAndGet();
        HookRegistry.log("H50 fail#" + n + " " + where);
        if (t != null) HookRegistry.log(t);
        if (n >= FAIL_MAX && !sTripped) {
            sTripped = true;
            CACHE.clear();
            HookRegistry.log("H50 circuit breaker OPEN —— 本进程不再改写任何皮肤资源（保命优先）");
        }
    }

    public static boolean tripped() {
        return sTripped;
    }

    /** 状态串片段，形如 {@code asset=open1234/theme56/ini4/img12/skip3/tp7}。 */
    public static String stateFields() {
        StringBuilder sb = new StringBuilder();
        sb.append("asset=open").append(ASSET_OPEN.get())
                .append("/theme").append(THEME_OPEN.get())
                .append("/ini").append(INI_PATCHED.get())
                .append("/img").append(IMG_PATCHED.get());
        if (IMG_SKIPPED.get() > 0) sb.append("/skip").append(IMG_SKIPPED.get());
        if (INI_SKIPPED.get() > 0) sb.append("/iniskip").append(INI_SKIPPED.get());
        if (SKIPPED_CUSTOM.get() > 0) sb.append("/tp").append(SKIPPED_CUSTOM.get());
        if (sTripped) sb.append("/TRIPPED");
        return sb.toString();
    }

    /** 见过的皮肤资源样本（最多 16 条），用来判断皮肤走的是 assets 还是解包后的文件。 */
    public static String samples() {
        synchronized (SAMPLES) {
            if (SAMPLES.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < SAMPLES.size(); i++) {
                if (i > 0) sb.append(' ');
                sb.append(SAMPLES.get(i));
            }
            return sb.toString();
        }
    }

    public static String summary() {
        return stateFields() + " fails=" + FAILS.get();
    }
}
