package com.chiron.iosemoji.config;

import android.content.SharedPreferences;

import com.chiron.iosemoji.Protocol;

import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;

/**
 * 模块配置（在搜狗进程内读取模块 APK 的 SharedPreferences）。
 *
 * <p>读取方式说明：
 * <ul>
 *   <li>本模块使用 {@link XSharedPreferences}。在 LSPosed 上，框架会拦截模块自身
 *       SharedPreferences 文件的读取并做权限桥接，因此无需 MODE_WORLD_READABLE；
 *       在旧式 Xposed 上需要手动给模块 prefs 加 world-readable 权限，这一点已在 README 说明。</li>
 *   <li>任何读取失败都退回默认值——**绝不让配置问题影响输入法可用性**。</li>
 * </ul>
 *
 * <p>默认值策略：表情相关（v1.8 已验证的既有功能）默认开启；
 * 键盘/候选栏替换属于新功能，默认**开启**但在版本不匹配时会自动降级。
 */
public final class ModuleConfig {

    public static final String PREFS_NAME = "chiron_config";

    public static final String KEY_MASTER = "master_enabled";
    public static final String KEY_KEYBOARD_REPLACE = "keyboard_replace_enabled";
    public static final String KEY_CANDIDATE_PROBE = "candidate_probe_enabled";
    public static final String KEY_EMOJI_GROUPS = "emoji_group_filter_enabled";
    public static final String KEY_EMOJI_SWIPE = "emoji_swipe_enabled";
    public static final String KEY_LOGO_TRIGGER = "logo_trigger_enabled";
    public static final String KEY_DIAGNOSTICS = "diagnostics_enabled";

    private static volatile XSharedPreferences sPrefs;
    private static final Object LOCK = new Object();

    private ModuleConfig() {
    }

    private static XSharedPreferences prefs() {
        XSharedPreferences p = sPrefs;
        if (p == null) {
            synchronized (LOCK) {
                if (sPrefs == null) {
                    try {
                        XSharedPreferences created = new XSharedPreferences(Protocol.MODULE_PKG, PREFS_NAME);
                        created.reload();
                        sPrefs = created;
                    } catch (Throwable t) {
                        XposedBridge.log("[AppleInputForSogou] config open failed: " + t);
                        return null;
                    }
                }
                p = sPrefs;
            }
        }
        return p;
    }

    /** 配置可能被设置页改动，这里按需刷新（廉价操作，只在安装 hook 时调用）。 */
    public static void reload() {
        try {
            XSharedPreferences p = prefs();
            if (p != null) {
                p.reload();
            }
        } catch (Throwable ignored) {
            // 读不到就用旧缓存
        }
    }

    private static boolean getBoolean(String key, boolean def) {
        // 优先级 1：运行中由设置页通过有序广播下发的覆盖值（改开关即时生效）
        Boolean live = RuntimeConfig.get(key);
        if (live != null) return live;
        // 优先级 2：XSharedPreferences（进程启动早期 ContentProvider 还没回来的兜底）
        try {
            XSharedPreferences p = prefs();
            if (p == null) return def;
            return p.getBoolean(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    public static boolean masterEnabled() {
        return getBoolean(KEY_MASTER, true);
    }

    public static boolean keyboardReplaceEnabled() {
        return masterEnabled() && getBoolean(KEY_KEYBOARD_REPLACE, true);
    }

    public static boolean candidateProbeEnabled() {
        return masterEnabled() && getBoolean(KEY_CANDIDATE_PROBE, false);
    }

    public static boolean emojiGroupFilterEnabled() {
        return masterEnabled() && getBoolean(KEY_EMOJI_GROUPS, true);
    }

    public static boolean emojiSwipeEnabled() {
        return masterEnabled() && getBoolean(KEY_EMOJI_SWIPE, true);
    }

    public static boolean logoTriggerEnabled() {
        return masterEnabled() && getBoolean(KEY_LOGO_TRIGGER, false);
    }

    public static boolean diagnosticsEnabled() {
        return getBoolean(KEY_DIAGNOSTICS, false);
    }

    /** 设置页写入的快捷方法（由模块自己的 UI 进程调用）。 */
    public static void writeBoolean(SharedPreferences.Editor editor, String key, boolean value) {
        editor.putBoolean(key, value);
    }
}
