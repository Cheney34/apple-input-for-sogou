package com.chiron.iosemoji;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import com.chiron.iosemoji.compat.VersionProfile;
import com.chiron.iosemoji.config.ModuleConfig;
import com.chiron.iosemoji.core.HookState;
import com.chiron.iosemoji.diagnostics.ViewTreeDumper;
import com.chiron.iosemoji.compat.Targets;
import com.chiron.iosemoji.hook.CandidateProbeHook;
import com.chiron.iosemoji.hook.ConfigBridge;
import com.chiron.iosemoji.hook.HookRegistry;
import com.chiron.iosemoji.hook.KeyBgDrawableHook;
import com.chiron.iosemoji.hook.KeyCapDrawHook;
import com.chiron.iosemoji.hook.AssetSkinHook;
import com.chiron.iosemoji.hook.MiuiBottomBarHook;
import com.chiron.iosemoji.hook.SetKeyboardViewHook;
import com.chiron.iosemoji.hook.ThemeSkinHook;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed 入口。
 *
 * <p>结构：
 * <ol>
 *   <li>{@code handleLoadPackage}：只放行搜狗包名；安装**不依赖 Context** 的类级 hook；</li>
 *   <li>{@code Application#attach}：拿到 Context 后读版本/配置，安装依赖 Context 的 hook，
 *       注册表情安装服务，并对"启动早期就拿不到类"的 hook 做延迟补装。</li>
 * </ol>
 *
 * <p>所有 hook 都经过 {@link HookRegistry}：幂等 + try/catch + 统一日志。
 * 任何一步失败都只影响该功能本身，**搜狗原行为保持不变**。
 */
public class HookEntry implements IXposedHookLoadPackage {

    private static final String TAG = "AppleInputForSogou";
    private static final AtomicBoolean ATTACHED = new AtomicBoolean(false);
    /** 当前进程的定制版类名表（handleLoadPackage 里按包名选定）。 */
    private static volatile Targets sTargets = Targets.XIAOMI;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam == null || !Protocol.isTarget(lpparam.packageName)) return;

        HookRegistry.log("load " + lpparam.packageName + " process=" + lpparam.processName);
        // 定制版差异（小米 / OPPO …）都收在 Targets 里：类名一律从这里取，不再写死
        Targets t2 = Targets.forPackage(lpparam.packageName);
        if (t2 == null) {
            // 手册原则：未识别的渠道默认不修改原始行为（宁可什么都不装）
            HookRegistry.log("unknown channel " + lpparam.packageName + ", no hooks installed");
            return;
        }
        sTargets = t2;
        HookRegistry.log("targets: " + sTargets.describe());
        ModuleConfig.reload();

        // ---- 外观：改搜狗默认皮肤（ini + 键帽配色），不依赖 Context，先挂上 ----
        installThemeHooks(lpparam.classLoader);

        // ---- 类级 hook（不需要 Context）----
        if (VersionProfile.allowUiReplacement() || HookState.versionName().equals("unknown")) {
            // 首次进入时还读不到版本，先尝试安装；失败由 Registry 记录，attach 后会重试
            tryInstallInputViewHooks(lpparam.classLoader);
        }
        if (ModuleConfig.candidateProbeEnabled() && sTargets.supportsCandidateProbe()) {
            try {
                CandidateProbeHook.install(lpparam.classLoader, sTargets);
            } catch (Throwable t) {
                HookRegistry.log(t);
            }
        }

        // ---- 旧的 v1.8 功能（表情/分组/Logo）----
        installLegacyHooks(lpparam.classLoader);

        // ---- 等 Context：主路径 hook Application#attach ----
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object arg = param.args[0];
                            if (!(arg instanceof Context)) return;
                            onAttached((Context) arg);
                        }
                    });
        } catch (Throwable t) {
            HookRegistry.log("hook Application#attach failed");
            HookRegistry.log(t);
        }

        // ---- 兜底：搜狗是 Tinker 热修复壳（SogouTinkerApplication），
        //      模块有可能在 Application 已经 attach 完之后才被加载，那样 attach hook 永远不触发 ——
        //      表现就是"模块连不上输入法"。这里主动向 ActivityThread 要一次 Application，
        //      再错开几个时间点重试。 ----
        scheduleAttachFallback(lpparam.classLoader);
    }

    private static void scheduleAttachFallback(final ClassLoader cl) {
        try {
            Handler handler = new Handler(Looper.getMainLooper());
            for (final long delay : new long[]{0L, 300L, 1200L, 3000L, 8000L}) {
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (ATTACHED.get()) return;
                        Context ctx = currentApplication(cl);
                        if (ctx == null) return;
                        HookRegistry.log("attach fallback: context from ActivityThread");
                        onAttached(ctx);
                    }
                }, delay);
            }
        } catch (Throwable t) {
            HookRegistry.log("scheduleAttachFallback failed");
            HookRegistry.log(t);
        }
    }

    private static Context currentApplication(ClassLoader cl) {
        try {
            Class<?> activityThread = XposedHelpers.findClass("android.app.ActivityThread", cl);
            Object app = XposedHelpers.callStaticMethod(activityThread, "currentApplication");
            return app instanceof Context ? (Context) app : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 真正的 attach 后初始化：两条路径（attach hook / ActivityThread 兜底）都汇聚到这里。 */
    private static void onAttached(final Context ctx) {
        try {
            if (ctx == null) return;
            if (!Protocol.isTarget(ctx.getPackageName())) return;
            if (!ATTACHED.compareAndSet(false, true)) return;

            HookState.setContext(ctx);
            HookState.setVersionName(readVersionName(ctx));
            ModuleConfig.reload();

            // 配置通道：ContentProvider 冷启动读 + 有序广播实时下发（含回执）
            try {
                ConfigBridge.setOnConfigChanged(new Runnable() {
                    @Override
                    public void run() {
                        // 开关变化后补装/重试：各 install() 自身幂等
                        installThemeHooks(ctx.getClassLoader());
                        tryInstallInputViewHooks(ctx.getClassLoader());
                        installLegacyHooks(ctx.getClassLoader());
                    }
                });
                ConfigBridge.install(ctx);
            } catch (Throwable t) {
                HookRegistry.log(t);
            }

            HookRegistry.log("attached: " + VersionProfile.describe()
                    + " | cfg " + ConfigBridge.summary());

            // 版本已确定，重新尝试键盘 hook（Registry 幂等，失败过的会重试）
            installThemeHooks(ctx.getClassLoader());
            tryInstallInputViewHooks(ctx.getClassLoader());

            installLegacyHooks(ctx.getClassLoader());
            scheduleLegacyHooks(ctx);

            try {
                EmojiService.register(ctx);
            } catch (Throwable t) {
                HookRegistry.log(t);
            }

            if (ModuleConfig.diagnosticsEnabled()) {
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        ViewTreeDumper.dumpInputView();
                    }
                }, 3000L);
            }
        } catch (Throwable t) {
            HookRegistry.log("attach failed");
            HookRegistry.log(t);
        }
    }

    /**
     * 皮肤 hook **总是安装**（开关在回调里实时判断）——它只改颜色/配色，
     * 不动布局，是 alpha4 起默认的外观方案（取代自绘键盘注入）。
     */
    private static void installThemeHooks(ClassLoader cl) {
        try {
            ThemeSkinHook.install(cl, sTargets);
        } catch (Throwable t) {
            HookRegistry.log("theme skin hook failed");
            HookRegistry.log(t);
        }
        // H71：键帽落屏重画（v2.2.0）。挂在 framework 的 Canvas.drawBitmap 上，
        // 与搜狗的混淆类名无关 —— 换句话说，只要搜狗是用 Canvas 把键画出来的，这条就一定挂得上。
        // 装不上也不影响任何原有功能（主题仍走 H40/H41）。
        try {
            KeyCapDrawHook.install(cl);
        } catch (Throwable t) {
            HookRegistry.log("keycap draw hook failed");
            HookRegistry.log(t);
        }
        // H72：键帽背景的 Drawable 层接管（v2.3.0）。
        //
        // 这才是键帽真正的落点：皮肤 png 从磁盘读出来 → utils.e#f 包成 Drawable →
        // 当成元素背景画。H41 挂的是 assets 那条读图路，皮肤解包到磁盘后根本不走它，
        // 所以前九版「改了但没变化」—— 九宫格和 26 键其实一样，都没被改到。
        // 装不上不影响任何原有功能。
        try {
            KeyBgDrawableHook.install(cl);
        } catch (Throwable t) {
            HookRegistry.log("key bg drawable hook failed");
            HookRegistry.log(t);
        }
        // 资源级兜底：直接拦 AssetManager.open()，不依赖任何混淆类名。
        // 小米版能命中就命中；OPPO 等改名字的版本靠这一层也能改到皮肤。
        try {
            AssetSkinHook.install(cl);
        } catch (Throwable t) {
            HookRegistry.log("asset skin hook failed");
            HookRegistry.log(t);
        }

        // MIUI「全面屏键盘优化」底栏（H60）：framework 层，只在小米渠道装。
        // 其它渠道/非 MIUI 机型上找不到注入类时会自己跳过，不影响任何东西。
        if (sTargets.xiaomiInputLayer) {
            try {
                MiuiBottomBarHook.install(cl);
            } catch (Throwable t) {
                HookRegistry.log("miui bottom bar hook failed");
                HookRegistry.log(t);
            }
        }
    }

    /**
     * 键盘 hook **总是尝试安装**，是否真的替换由 {@link ModuleConfig} 在每次调用时判断 ——
     * 这样设置页关掉「iOS 键盘」立刻退回搜狗原键盘，重新打开也不用重启输入法。
     */
    private static void tryInstallInputViewHooks(ClassLoader cl) {
        try {
            SetKeyboardViewHook.install(cl, sTargets);
        } catch (Throwable t) {
            HookRegistry.log(t);
        }
    }

    /**
     * 三个既有功能（滑动 / 分组过滤 / Logo 触发词）**总是安装**，开关在回调里实时判断，
     * 因此设置页改动即时生效、双向可逆（关掉就是搜狗原行为）。
     */
    private static void installLegacyHooks(ClassLoader cl) {
        try {
            GestureHook.install(cl, sTargets);
        } catch (Throwable t) {
            HookRegistry.log(t);
        }
        try {
            GroupFilterHook.install(cl, sTargets);
        } catch (Throwable t) {
            HookRegistry.log(t);
        }
        try {
            LogoHook.install();
        } catch (Throwable t) {
            HookRegistry.log(t);
        }
    }

    /**
     * ExpressionUtil / ExpressionServiceImpl 在进程很早的阶段可能还没加载，
     * 错开几个时间点补挂。各 install() 自身幂等。
     */
    private static void scheduleLegacyHooks(Context ctx) {
        final ClassLoader cl = ctx.getClassLoader();
        try {
            Handler handler = new Handler(Looper.getMainLooper());
            for (final long delay : new long[]{0L, 300L, 1200L, 3000L}) {
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        installLegacyHooks(cl);
                    }
                }, delay);
            }
        } catch (Throwable t) {
            HookRegistry.log(t);
        }
    }

    private static String readVersionName(Context ctx) {
        try {
            return ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "unknown";
        }
    }
}
