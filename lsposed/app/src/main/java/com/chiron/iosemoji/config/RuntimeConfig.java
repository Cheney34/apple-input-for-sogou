package com.chiron.iosemoji.config;

import android.content.Intent;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 搜狗进程内的**运行时配置覆盖层**。
 *
 * <p>存在的理由：SharedPreferences 跨进程读不到（或读到旧缓存）时，开关就"看着没用"。
 * 所以模块 App 每次改开关都会把所有键值用有序广播直接下发到搜狗进程，这里存下来，
 * {@link ModuleConfig} 优先读这里。
 *
 * <p>优先级：运行时覆盖 > ContentProvider 冷启动快照 > XSharedPreferences > 默认值。
 *
 * <p>两类值：布尔开关（{@link #VALUES}）与字符串选项（{@link #STRINGS}，目前只有皮肤预设
 * {@code skin_preset}）。
 */
public final class RuntimeConfig {

    private static final ConcurrentHashMap<String, Boolean> VALUES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> STRINGS = new ConcurrentHashMap<>();

    private RuntimeConfig() {
    }

    /** 返回 null 表示"没有覆盖，继续走后面的来源"。 */
    public static Boolean get(String key) {
        return VALUES.get(key);
    }

    public static void put(String key, boolean value) {
        VALUES.put(key, value);
    }

    /** 返回 null 表示"没有覆盖"。 */
    public static String getString(String key) {
        return STRINGS.get(key);
    }

    public static void putString(String key, String value) {
        if (key == null) return;
        if (value == null) STRINGS.remove(key);
        else STRINGS.put(key, value);
    }

    public static boolean hasAny() {
        return !VALUES.isEmpty() || !STRINGS.isEmpty();
    }

    public static int size() {
        return VALUES.size() + STRINGS.size();
    }

    /** 用有序广播带来的 extras 覆盖全部已知键（只覆盖 intent 里确实带了的）。 */
    public static int applyFromIntent(Intent intent) {
        if (intent == null) return 0;
        int n = 0;
        for (String key : ConfigValues.KEYS) {
            if (!intent.hasExtra(key)) continue;
            try {
                VALUES.put(key, intent.getBooleanExtra(key, ConfigValues.defaultValue(key)));
                n++;
            } catch (Throwable ignored) {
                // 单个键解析失败不影响其它键
            }
        }
        try {
            String seedKey = intent.getStringExtra(ModuleConfig.KEY_MONET_SEED);
            if (seedKey != null && seedKey.length() > 0) {
                STRINGS.put(ModuleConfig.KEY_MONET_SEED, seedKey);
                n++;
            }
            String preset = intent.getStringExtra(ModuleConfig.KEY_SKIN_PRESET);
            if (preset != null && preset.length() > 0) {
                STRINGS.put(ModuleConfig.KEY_SKIN_PRESET, preset);
                n++;
            }
        } catch (Throwable ignored) {
            // 皮肤预设解析失败就用当前值
        }
        return n;
    }

    /** 当前覆盖值的可读快照：master=1 kb=1 probe=0 skin=ios … */
    public static String describe() {
        StringBuilder sb = new StringBuilder();
        for (String key : ConfigValues.KEYS) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(shortName(key)).append('=').append(Boolean.TRUE.equals(VALUES.get(key)) ? '1' : '0');
        }
        String skin = STRINGS.get(ModuleConfig.KEY_SKIN_PRESET);
        if (skin != null) sb.append(" skin=").append(skin);
        return sb.toString();
    }

    private static String shortName(String key) {
        switch (key) {
            case ModuleConfig.KEY_MASTER:
                return "master";
            case ModuleConfig.KEY_THEME_SKIN:
                return "skin_on";
                case ModuleConfig.KEY_ASSET_SKIN:
                return "asset";
            case ModuleConfig.KEY_MIUI_BOTTOM_BAR:
                return "bb";
            case ModuleConfig.KEY_ASSET_SKIN_IMAGES:
                return "aimg";
            case ModuleConfig.KEY_CANDIDATE_PROBE:
                return "probe";
            case ModuleConfig.KEY_EMOJI_GROUPS:
                return "groups";
            case ModuleConfig.KEY_EMOJI_SWIPE:
                return "swipe";
            case ModuleConfig.KEY_LOGO_TRIGGER:
                return "logo";
            case ModuleConfig.KEY_DIAGNOSTICS:
                return "diag";
            default:
                return key;
        }
    }
}
