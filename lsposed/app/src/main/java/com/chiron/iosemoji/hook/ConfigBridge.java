package com.chiron.iosemoji.hook;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;

import com.chiron.iosemoji.compat.Targets;
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
    /** 定时重读 provider 的次数（诊断用：看到它在涨说明进程活着、通道在跑）。 */
    private static final java.util.concurrent.atomic.AtomicInteger RESYNC =
            new java.util.concurrent.atomic.AtomicInteger();

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
                        MiuiBottomBarHook.reapply();
                                    setResultCode(Protocol.RESULT_OK);
                        setResultData(state(ctx));
                        runConfigCallback();
                    } else if (Protocol.ACTION_CFG_QUERY.equals(action)) {
                        setResultCode(Protocol.RESULT_OK);
                        setResultData(state(ctx));
                    } else if (Protocol.ACTION_RESTART.equals(action)) {
                        // 「重启输入法」：先把状态回给 App（否则 App 收不到回执），再结束自己的进程。
                        // 结束进程是合法的（自己的进程），系统在下次需要输入法时会重新拉起，
                        // 这就等于让搜狗把皮肤资源、配置、hook 全部按当前设置重新走一遍。
                        setResultCode(Protocol.RESULT_OK);
                        setResultData("restarting;" + state(ctx));
                        HookRegistry.log("restart requested -> 200ms 后结束搜狗进程（pid "
                                + android.os.Process.myPid() + "）");
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        try {
                                            android.os.Process.killProcess(android.os.Process.myPid());
                                        } catch (Throwable t) {
                                            HookRegistry.log("restart failed: " + t);
                                        }
                                    }
                                }, 200L);
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
        filter.addAction(Protocol.ACTION_RESTART);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(receiver, filter);
            }
            HookRegistry.log("config bridge ready in " + ctx.getPackageName());
            scheduleResync(ctx);
        } catch (Throwable t) {
            INSTALLED.set(false);
            HookRegistry.log("config bridge register failed");
            HookRegistry.log(t);
        }
    }

    /**
     * 定时重读配置 —— 专治「广播收不到 / 连不上」。
     *
     * <p>ColorOS / HyperOS 等系统会限制后台应用的隐式广播，模块 App 的有序广播未必送达到
     * 输入法进程；而 {@link ContentResolver} 查询是普通 Binder 调用，只要进程活着就能读。
     * 所以这里在进程启动后的头 60 秒里按 2/5/10/20/40/60 秒重读几次，读到的值覆盖
     * {@link RuntimeConfig}；用户哪怕"先开键盘、后改设置"，只要重新点开一次键盘就会生效。
     */
    private static void scheduleResync(final Context ctx) {
        try {
            final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            final long[] delays = {2000L, 5000L, 10000L, 20000L, 40000L, 60000L};
            for (final long d : delays) {
                h.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        RESYNC.incrementAndGet();
                        loadFromProviderAsync(ctx);
                    }
                }, d);
            }
            HookRegistry.log("config resync scheduled: 6 times (2s..60s)");
        } catch (Throwable t) {
            HookRegistry.log("config resync schedule failed");
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
        // 到底是哪个渠道的搜狗连上来了：小米 / 官方 / OPPO / vivo —— 设置页要显示这个
        sb.append(";ch=").append(channelLabel(ctx));
        sb.append(";chpkg=").append(ctx.getPackageName());
        sb.append(';').append(RuntimeConfig.describe());
        sb.append(";sg=").append(sogouVersion(ctx));
        // 皮肤改动是否真的落到了搜狗的皮肤资源上 —— 这行是"改没改到"的唯一判据
        sb.append(';').append(ThemeSkinHook.stateFields());
        String unmatched = ThemeSkinHook.unmatchedNames();
        if (unmatched.length() > 0) sb.append(";skip=").append(unmatched);
        String seen = ThemeSkinHook.seenNames();
        if (seen.length() > 0) sb.append(";seen=").append(seen);
        sb.append(";resync=").append(RESYNC.get());
        return sb.toString();
    }

    /** 当前连上来的渠道名（给设置页显示，避免用户分不清小米/官方/OPPO/vivo）。 */
    private static String channelLabel(Context ctx) {
        try {
            Targets t = Targets.forPackage(ctx.getPackageName());
            if (t != null) return t.label;
        } catch (Throwable ignored) {
        }
        return "未知渠道";
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
                        int seedIdx = cursor.getColumnIndex(ModuleConfig.KEY_MONET_SEED);
                        if (seedIdx >= 0) {
                            String seedKey = cursor.getString(seedIdx);
                            if (seedKey != null && seedKey.length() > 0) {
                                RuntimeConfig.putString(ModuleConfig.KEY_MONET_SEED, seedKey);
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

    /**
     * 立刻重读一次模块 App 的配置（异步、绝不在主线程碰 ContentResolver）。
     *
     * <p>挂在「键盘被装上」这个时机上（{@code H02}），等于「每次弹出键盘都对齐一次配置」——
     * 用户改完设置、切回输入框，配置就生效了，不依赖广播能不能送达。
     */
    public static void resyncNow() {
        Context ctx = HookState.context();
        if (ctx == null) return;
        RESYNC.incrementAndGet();
        loadFromProviderAsync(ctx);
    }

    /** 便于 HookEntry 在 attach 日志里打一行"当前生效配置"。 */
    public static String summary() {
        return "src=" + (RuntimeConfig.hasAny() ? "broadcast" : "cold-start")
                + " " + RuntimeConfig.describe();
    }
}
