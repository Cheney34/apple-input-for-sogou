package com.chiron.iosemoji.config;

import android.content.Context;

import com.chiron.iosemoji.core.HookState;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.Charset;

/**
 * 把「上一次确认生效的皮肤设置」落盘保存在<b>搜狗进程自己的</b>数据目录里。
 *
 * <p>为什么需要它：{@code skin_preset} 正常来自模块 App 的 SharedPreferences（经
 * {@code XSharedPreferences}）或 ContentProvider（异步）。但冷启动时这两条路都可能还没就绪：
 * <ul>
 *   <li>{@code XSharedPreferences} 在部分 ROM / 多用户环境下读不到模块的 prefs 文件；</li>
 *   <li>ContentProvider 查询是异步的，而搜狗第一次解析皮肤 ini / 第一次落屏画键帽都早于它返回。</li>
 * </ul>
 * 结果就是「重启输入法后先按默认 iOS 画一遍，要再手动重启一次才对」。
 *
 * <p>这个文件由<b>上一次成功读到配置时</b>写入（见 {@code ConfigBridge}），下次冷启动可以在
 * 毫秒级同步读到，早于任何皮肤解析，从而保证第一帧就是用户选的皮肤。
 *
 * <p>写的是本进程 filesDir，不涉及跨进程权限；读失败一律返回 null，让调用方继续走原有兜底链。
 */
public final class PresetStore {

    private static final String FILE = "chiron_skin_state.txt";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static volatile boolean sLoaded;
    private static volatile String sPreset;
    private static volatile String sRadius;
    private static volatile String sMonetSeed;

    private PresetStore() {
    }

    /** 同步读一次（只读一次，之后走内存缓存）。可在 hook 线程早期安全调用。 */
    private static void load() {
        if (sLoaded) return;
        synchronized (PresetStore.class) {
            if (sLoaded) return;
            try {
                Context ctx = HookState.context();
                if (ctx != null) {
                    File f = new File(ctx.getFilesDir(), FILE);
                    if (f.isFile() && f.length() > 0 && f.length() < 8192) {
                        byte[] buf = new byte[(int) f.length()];
                        FileInputStream in = new FileInputStream(f);
                        try {
                            int off = 0;
                            while (off < buf.length) {
                                int n = in.read(buf, off, buf.length - off);
                                if (n <= 0) break;
                                off += n;
                            }
                        } finally {
                            in.close();
                        }
                        parse(new String(buf, UTF8));
                    }
                }
            } catch (Throwable t) {
                // 读不到就当没有
            }
            sLoaded = true;
        }
    }

    private static void parse(String text) {
        if (text == null) return;
        for (String line : text.split("\n")) {
            int i = line.indexOf('=');
            if (i <= 0) continue;
            String k = line.substring(0, i).trim();
            String v = line.substring(i + 1).trim();
            if (v.length() == 0) continue;
            if ("preset".equals(k)) sPreset = v;
            else if ("radius".equals(k)) sRadius = v;
            else if ("monet_seed".equals(k)) sMonetSeed = v;
        }
    }

    public static String preset() {
        load();
        return sPreset;
    }

    public static String radius() {
        load();
        return sRadius;
    }

    public static String monetSeed() {
        load();
        return sMonetSeed;
    }

    /** 由 ConfigBridge 在「确认读到一组有效配置」后调用，把当前值写盘供下次冷启动用。 */
    public static void save(Context ctx) {
        if (ctx == null) return;
        try {
            String preset = ModuleConfig.skinPreset();
            String radius = String.valueOf(ModuleConfig.keyRadiusPercent());
            String seed = ModuleConfig.monetSeed();
            StringBuilder sb = new StringBuilder();
            sb.append("preset=").append(preset).append('\n');
            sb.append("radius=").append(radius).append('\n');
            sb.append("monet_seed=").append(seed).append('\n');
            byte[] data = sb.toString().getBytes(UTF8);
            File f = new File(ctx.getFilesDir(), FILE);
            FileOutputStream out = new FileOutputStream(f, false);
            try {
                out.write(data);
                out.flush();
            } finally {
                out.close();
            }
            sPreset = preset;
            sRadius = radius;
            sMonetSeed = seed;
            sLoaded = true;
        } catch (Throwable t) {
            // 写不进去不影响运行
        }
    }

    /** 仅供设置页/诊断展示。 */
    public static String describe() {
        load();
        return "preset=" + sPreset + ",radius=" + sRadius + ",seed=" + sMonetSeed;
    }
}
