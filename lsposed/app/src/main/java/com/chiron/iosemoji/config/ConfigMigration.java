package com.chiron.iosemoji.config;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 配置迁移 —— 只在升级到 v2.0.0-alpha4（versionCode 23）后第一次运行时执行一次。
 *
 * <p>为什么需要它：alpha3 及以前「自绘键盘」默认是**开**的，实测会把搜狗自己算好的
 * 布局与命中区打乱（样式错乱）。升级上来的用户 prefs 里存的是 `true`，
 * 光改默认值对老用户无效，所以这里显式写一次 `false`；同时把新的皮肤开关置为开。
 *
 * <p>只跑一次，之后尊重用户自己的选择（用户想再试自绘键盘可以手动打开）。
 */
public final class ConfigMigration {

    /** 迁移标记本身不参与配置下发，只存在模块自己的 prefs 里。 */
    public static final String KEY_MIGRATION_DONE = "migrated_v23_skin";

    private ConfigMigration() {
    }

    public static void runOnce(Context context) {
        if (context == null) return;
        try {
            SharedPreferences prefs =
                    context.getSharedPreferences(ModuleConfig.PREFS_NAME, Context.MODE_PRIVATE);
            if (prefs.getBoolean(KEY_MIGRATION_DONE, false)) return;
            SharedPreferences.Editor editor = prefs.edit();
            // 皮肤方案默认开（用户没显式设置过才写）
            if (!prefs.contains(ModuleConfig.KEY_THEME_SKIN)) {
                editor.putBoolean(ModuleConfig.KEY_THEME_SKIN, true);
            }
            editor.putBoolean(KEY_MIGRATION_DONE, true);
            editor.commit();
        } catch (Throwable ignored) {
            // 迁移失败不影响使用：默认值本身就是自绘键盘关闭
        }
    }
}
