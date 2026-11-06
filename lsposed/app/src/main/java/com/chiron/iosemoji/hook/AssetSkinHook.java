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
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 资源级（AssetManager）皮肤替换 —— <b>不依赖任何混淆类名</b>。
 *
 * <p>为什么要有这一层：小米版、OPPO 定制版（以及以后任何 OEM 版）都会把
 * 主题解析类和位图类改名（OPPO 甚至整包塞进 {@code defpackage}），
 * 但<b>它们读皮肤资源的物理动作永远是 {@code context.getAssets().open("theme/...")}</b>。
 * 所以在这一层拦截，一次写好，所有版本通吃，也不怕版本升级改名。
 *
 * <p>处理规则：
 * <ul>
 *   <li>{@code *.ini}（主题布局/颜色/图片清单）：按行解析成段表 → 交给
 *       {@link IosSkin#patchIni(String, Map, SkinPreset)} 改颜色 → 再序列化回文本流；</li>
 *   <li>{@code *.png}（键帽/底图）：解码 → {@link IosSkin#synth} 按预设重绘 → 重新编码成 PNG 流；</li>
 *   <li>其它：一律原样放行。</li>
 * </ul>
 *
 * <p>同时它还是个<b>诊断器</b>：把见过的皮肤资源路径记下来（最多 {@link #SAMPLE_MAX} 条），
 * 通过 {@link #summary()} 出现在模块 App 的「连接状态」里 —— 这样即使我手上没有对应机型，
 * 也能从用户贴回的一行里看出"到底走的是 assets 还是解包后的文件"。
 */
public final class AssetSkinHook {

    private static final int SAMPLE_MAX = 16;
    private static final int CACHE_MAX = 256;

    private static final AtomicInteger ASSET_OPEN = new AtomicInteger();
    private static final AtomicInteger THEME_OPEN = new AtomicInteger();
    private static final AtomicInteger INI_PATCHED = new AtomicInteger();
    private static final AtomicInteger IMG_PATCHED = new AtomicInteger();
    private static final AtomicInteger IMG_SKIPPED = new AtomicInteger();

    private static final List<String> SAMPLES = new ArrayList<>();
    private static final Map<String, byte[]> CACHE = new ConcurrentHashMap<>();
    private static volatile boolean sInstalled;

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
        try {
            if (path == null) return;
            ASSET_OPEN.incrementAndGet();
            if (!isThemeAsset(path)) return;
            THEME_OPEN.incrementAndGet();
            sample(path);

            Object result = param.getResult();
            if (!(result instanceof InputStream)) return;
            if (!ModuleConfig.themeSkinEnabled()) return;

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
            if (INI_PATCHED.get() + IMG_PATCHED.get() <= 8) {
                HookRegistry.log("H50 patched asset: " + path
                        + " (" + raw.length + " -> " + out.length + "B)");
            }
        } catch (Throwable t) {
            HookRegistry.log("H50 asset hook error on " + path);
            HookRegistry.log(t);
        }
    }

    /** 清缓存（换预设/重启后必须调用，否则还是上一套颜色）。 */
    public static void reset() {
        CACHE.clear();
    }

    // ---------------------------------------------------------------- ini

    /**
     * 行级解析 → 段表 → {@link IosSkin#patchIni} → 序列化。
     * 全程用 ISO-8859-1 编解码（字节级往返，不会破坏非 ASCII 值）。
     */
    private static byte[] patchIniBytes(String path, byte[] raw, String presetId) {
        try {
            String text = new String(raw, "ISO-8859-1");
            Map<String, Map<String, String>> sections = parseIni(text);
            if (sections.isEmpty()) return null;

            Context ctx = HookState.context();
            SkinPreset p = SkinPreset.of(presetId, IosSkin.isDark(path, ctx), ctx);
            boolean changed = IosSkin.patchIni(path, sections, p);
            if (!changed) return null;

            String out = serializeIni(sections);
            return out.getBytes("ISO-8859-1");
        } catch (Throwable t) {
            HookRegistry.log("patchIniBytes failed: " + path);
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

    private static String serializeIni(Map<String, Map<String, String>> sections) {
        StringBuilder sb = new StringBuilder(4096);
        for (Map.Entry<String, Map<String, String>> e : sections.entrySet()) {
            sb.append('[').append(e.getKey()).append("]\n");
            for (Map.Entry<String, String> kv : e.getValue().entrySet()) {
                sb.append(kv.getKey()).append('=').append(kv.getValue()).append('\n');
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------- images

    private static byte[] patchImageBytes(String path, byte[] raw, String presetId) {
        try {
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
            HookRegistry.log("patchImageBytes failed: " + path);
            return null;
        }
    }

    // -------------------------------------------------------------- utils

    /** 只认皮肤相关的资源，别去碰 emoji / lottie / 字体。 */
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

    /** 状态串片段，形如 {@code asset=open1234/theme56/ini4/img12/skip3}。 */
    public static String stateFields() {
        StringBuilder sb = new StringBuilder();
        sb.append("asset=open").append(ASSET_OPEN.get())
                .append("/theme").append(THEME_OPEN.get())
                .append("/ini").append(INI_PATCHED.get())
                .append("/img").append(IMG_PATCHED.get());
        if (IMG_SKIPPED.get() > 0) sb.append("/skip").append(IMG_SKIPPED.get());
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
        return stateFields() + " samples=" + SAMPLES.size();
    }
}
