package com.chiron.iosemoji.hook;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;

import com.chiron.iosemoji.Protocol;
import com.chiron.iosemoji.config.ConfigValues;
import com.chiron.iosemoji.config.ModuleConfig;
import com.chiron.iosemoji.config.RuntimeConfig;
import com.chiron.iosemoji.core.HookState;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 配置桥 —— 装在**搜狗进程**里，负责把模块 App 的开关真正送到 hook 面前，并且回话。
 *
 * <p>三条通道，按需命中：
 * <ol>
 *   <li><b>冷启动</b>：进程刚起来时用 {@link ContentResolver} 读
 *       {@code content://com.chiron.iosemoji.config/config}（跨进程、免 root、各版本可用）；</li>
 *   <li><b>运行中改开关</b>：模块 App 发有序广播 {@link Protocol#ACTION_CFG}，这里写入
 *       {@link RuntimeConfig} 覆盖层并 {@code setResultData} 回一份状态串 —— 这就是设置页
 *       "已生效/未连接" 反馈的来源；</li>
 *   <li><b>状态查询</b>：{@link Protocol#ACTION_CFG_QUERY}，只回状态不做修改。</li>
 * </ol>
 *
 * <p>失败策略：任何一步抛异常都只记日志，配置保持"默认值 + 原有来源"，绝不打断输入法。
 */
public final class ConfigBridge {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean PROVIDER_DONE = new AtomicBoolean(false);

    /** 配置发生变化后要重做的事（补装 hook 等），由 HookEntry 在 attach 时注入。 */
    private static volatile Runnable sOnConfigChanged;

    private ConfigBridge() {
    }

    public static void setOnConfigChanged(Runnable r) {
        sOnConfigChanged = r;
    }

    /** 在搜狗进程内注册接收器并异步拉一次冷启动配置。幂等。 */
    public static void install(final Context ctx) {
        if (ctx == null) return;
        if (!INSTALLED.compareAndSet(false, true)) return;

        // 1) 冷启动配置：放到后台线程，别在主线程碰 ContentResolver
        loadFromProviderAsync(ctx);

        // 2) 运行中配置下发 / 状态查询
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                if (intent == null) return;
                String action = intent.getAction();
                try {
                    if (Protocol.ACTION_CFG.equals(action)) {
                        int n = RuntimeConfig.applyFromIntent(intent);
                        HookRegistry.log("config pushed: " + RuntimeConfig.describe()
                                + " (" + n + " keys)");
                        // 换皮肤预设后必须丢掉搜狗的位图缓存，否则还是上一套配色
                        ThemeSkinHook.dropCaches();
                        setResultCode(Protocol.RESULT_OK);
                        setResultData(state(ctx));
                        runConfigCallback();
                    } else if (Protocol.ACTION_CFG_QUERY.equals(action)) {
                        setResultCode(Protocol.RESULT_OK);
                        setResultData(state(ctx));
                    }
                } catch (Throwable t) {
                    HookRegistry.log(t);
                    try {
                        setResultCode(Protocol.RESULT_ERROR);
                        setResultData("error: " + t.getClass().getSimpleName());
                    } catch (Throwable ignored) {
                        // 回包失败就失败，客户端会超时提示
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter(Protocol.ACTION_CFG);
        filter.addAction(Protocol.ACTION_CFG_QUERY);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(receiver, filter);
            }
            HookRegistry.log("config bridge ready in " + ctx.getPackageName());
        } catch (Throwable t) {
            INSTALLED.set(false);
            HookRegistry.log("config bridge register failed");
            HookRegistry.log(t);
        }
    }

    /**
     * 状态串，回给模块 App 显示。格式固定且只用 ASCII 分隔符，方便人读也方便解析：
     * {@code v=2.0.0-alpha3;src=broadcast;hooks=H01,H02;master=1;keyboard=1;...}
     */
    private static String state(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("v=").append(HookState.versionName());
        sb.append(";src=").append(RuntimeConfig.hasAny() ? "broadcast" : "cold-start");
        sb.append(";hooks=").append(HookRegistry.installedIds());
        sb.append(';').append(RuntimeConfig.describe());
        sb.append(";sg=").append(sogouVersion(ctx));
        return sb.toString();
    }

    private static String sogouVersion(Context ctx) {
        try {
            return ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static void runConfigCallback() {
        Runnable r = sOnConfigChanged;
        if (r == null) return;
        try {
            r.run();
        } catch (Throwable t) {
            HookRegistry.log(t);
        }
    }

    /** 冷启动读一次 ContentProvider，把结果灌进 {@link RuntimeConfig}。 */
    private static void loadFromProviderAsync(Context ctx) {
        final Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Uri uri = Uri.parse("content://" + Protocol.CFG_AUTHORITY + "/config");
                    ContentResolver cr = app.getContentResolver();
                    Cursor cursor = cr.query(uri, null, null, null, null);
                    if (cursor == null) {
                        HookRegistry.log("config provider: no cursor (module app not installed?)");
                        return;
                    }
                    try {
                        if (!cursor.moveToFirst()) return;
                        int n = 0;
                        for (String key : ConfigValues.KEYS) {
                            int idx = cursor.getColumnIndex(key);
                            if (idx < 0) continue;
                            RuntimeConfig.put(key, cursor.getInt(idx) != 0);
                            n++;
                        }
                        int skinIdx = cursor.getColumnIndex(ModuleConfig.KEY_SKIN_PRESET);
                        if (skinIdx >= 0) {
                            String preset = cursor.getString(skinIdx);
                            if (preset != null && preset.length() > 0) {
                                RuntimeConfig.putString(ModuleConfig.KEY_SKIN_PRESET, preset);
                                n++;
                            }
                        }
                        HookRegistry.log("config provider: loaded " + n + " keys -> "
                                + RuntimeConfig.describe());
                        PROVIDER_DONE.set(true);
                        runConfigCallback();
                    } finally {
                        cursor.close();
                    }
                } catch (Throwable t) {
                    HookRegistry.log("config provider read failed: " + t);
                }
            }
        }, "AppleCfgLoad");
        thread.setDaemon(true);
        thread.start();
    }

    public static boolean providerLoaded() {
        return PROVIDER_DONE.get();
    }

    /** 便于 HookEntry 在 attach 日志里打一行"当前生效配置"。 */
    public static String summary() {
        return "src=" + (RuntimeConfig.hasAny() ? "broadcast" : "cold-start")
                + " " + RuntimeConfig.describe();
    }
}
