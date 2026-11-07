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
    /** iOS 皮肤（改搜狗默认皮肤的 ini + 键帽配色）—— v2.0.0-alpha4 起的默认外观方案。 */
    public static final String KEY_THEME_SKIN = "theme_skin_enabled";

    /** 资源层（H50）：直接在 AssetManager 层改写皮肤 ini/位图，对付 OEM 改了类名的版本（如 OPPO 定制版）。 */
    /**
     * 皮肤预设（**字符串**，不是布尔）：{@code ios} / {@code wechat} / {@code google} / {@code monet}。
     * 取值与配色见 {@link com.chiron.iosemoji.skin.SkinPreset}。
     */
    public static final String KEY_SKIN_PRESET = "skin_preset";
    public static final String DEFAULT_SKIN_PRESET = "ios";
    public static final String KEY_CANDIDATE_PROBE = "candidate_probe_enabled";
    public static final String KEY_EMOJI_GROUPS = "emoji_group_filter_enabled";
    public static final String KEY_EMOJI_SWIPE = "emoji_swipe_enabled";
    public static final String KEY_LOGO_TRIGGER = "logo_trigger_enabled";
    public static final String KEY_DIAGNOSTICS = "diagnostics_enabled";

    /** MIUI「全面屏键盘优化」底栏是否跟随皮肤配色（H60，仅小米定制版有效）。 */
    public static final String KEY_MIUI_BOTTOM_BAR = "miui_bottom_bar_enabled";
    /** 资源层是否连位图一起重画（默认关：重编码会丢 nine-patch 数据，风险高）。 */
    /** 资源层兜底（H50）总开关：默认关。小米/官方走类名层即可，OPPO/vivo 才需要它。 */
    public static final String KEY_ASSET_SKIN = "asset_skin_enabled";

    public static final String KEY_ASSET_SKIN_IMAGES = "asset_skin_images_enabled";

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

    /** iOS 皮肤：改写搜狗默认皮肤的 ini 颜色 + 键帽/键盘底色重上色（不动布局）。默认开启。 */
    public static boolean themeSkinEnabled() {
        return masterEnabled() && getBoolean(KEY_THEME_SKIN, true);
    }

    private static String getString(String key, String def) {
        // 优先级 1：设置页广播下发的覆盖值
        String live = RuntimeConfig.getString(key);
        if (live != null) return live;
        // 优先级 2：XSharedPreferences
        try {
            XSharedPreferences p = prefs();
            if (p == null) return def;
            String v = p.getString(key, def);
            return v == null ? def : v;
        } catch (Throwable t) {
            return def;
        }
    }

    /** 当前皮肤预设 id（{@code ios}/{@code wechat}/{@code google}/{@code monet}）。 */
    public static String skinPreset() {
        String v = getString(KEY_SKIN_PRESET, DEFAULT_SKIN_PRESET);
        if (!com.chiron.iosemoji.skin.SkinPreset.isKnown(v)) v = DEFAULT_SKIN_PRESET;
        return v;
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

    public static boolean miuiBottomBarEnabled() {
        // v2.0.3-beta4：默认关。H60 会 hook framework 的颜色上报链路，属于「可能影响键盘能否唤起」的改动，
        // 先让它在小米上验证过再默认开。
        // v2.0.3-beta7：H61 已改成「改参 + 建完补一次 + 视图树兜底」三条并行，默认开。
        return masterEnabled() && getBoolean(KEY_MIUI_BOTTOM_BAR, true);
    }

    /** 资源层兜底（H50）：进程级拦 AssetManager，默认关，改坏就会「键盘呼不出来」。 */
    public static boolean assetSkinEnabled() {
        return themeSkinEnabled() && getBoolean(KEY_ASSET_SKIN, false);
    }

    public static boolean assetSkinImagesEnabled() {
        return assetSkinEnabled() && getBoolean(KEY_ASSET_SKIN_IMAGES, false);
    }

    /** 设置页写入的快捷方法（由模块自己的 UI 进程调用）。 */
    public static void writeBoolean(SharedPreferences.Editor editor, String key, boolean value) {
        editor.putBoolean(key, value);
    }
}
