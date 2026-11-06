package com.chiron.iosemoji.config;

/**
 * 配置键的定义表 —— 模块 App 进程与搜狗进程**共用**，避免两边写死不一致。
 *
 * <p>为什么要有这张表：设置页、ContentProvider、广播下发、hook 内部读取必须用同一套键名
 * 与同一套默认值，否则会出现"开关看着是开的、实际没生效"这种没有任何反馈的问题。
 */
public final class ConfigValues {

    /** 顺序即设置页显示顺序，也是广播下发顺序。 */
    public static final String[] KEYS = {
            ModuleConfig.KEY_MASTER,
            ModuleConfig.KEY_THEME_SKIN,
            ModuleConfig.KEY_EMOJI_GROUPS,
            ModuleConfig.KEY_EMOJI_SWIPE,
            ModuleConfig.KEY_LOGO_TRIGGER,
            ModuleConfig.KEY_KEYBOARD_REPLACE,
            ModuleConfig.KEY_CANDIDATE_PROBE,
            ModuleConfig.KEY_DIAGNOSTICS,
    };

    private ConfigValues() {
    }

    /** 默认值：皮肤与表情相关默认开（v1.8 既有行为保留）；自绘键盘/探针/诊断默认关。 */
    public static boolean defaultValue(String key) {
        switch (key) {
            case ModuleConfig.KEY_MASTER:
            case ModuleConfig.KEY_THEME_SKIN:
            case ModuleConfig.KEY_EMOJI_GROUPS:
            case ModuleConfig.KEY_EMOJI_SWIPE:
                return true;
            case ModuleConfig.KEY_KEYBOARD_REPLACE:
            case ModuleConfig.KEY_CANDIDATE_PROBE:
            case ModuleConfig.KEY_LOGO_TRIGGER:
            case ModuleConfig.KEY_DIAGNOSTICS:
                return false;
            default:
                return false;
        }
    }

    /** 中文短名，用于状态回显与 Toast。 */
    public static String label(String key) {
        switch (key) {
            case ModuleConfig.KEY_MASTER:
                return "总开关";
            case ModuleConfig.KEY_THEME_SKIN:
                return "iOS 皮肤";
            case ModuleConfig.KEY_KEYBOARD_REPLACE:
                return "自绘键盘（实验）";
            case ModuleConfig.KEY_CANDIDATE_PROBE:
                return "候选探针";
            case ModuleConfig.KEY_EMOJI_GROUPS:
                return "隐藏内置分组";
            case ModuleConfig.KEY_EMOJI_SWIPE:
                return "分组滑动";
            case ModuleConfig.KEY_LOGO_TRIGGER:
                return "Logo 触发词";
            case ModuleConfig.KEY_DIAGNOSTICS:
                return "视图树诊断";
            default:
                return key;
        }
    }

    public static boolean isKnown(String key) {
        for (String k : KEYS) {
            if (k.equals(key)) return true;
        }
        return false;
    }
}
