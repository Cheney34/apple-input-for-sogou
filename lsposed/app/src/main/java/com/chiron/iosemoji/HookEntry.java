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
import com.chiron.iosemoji.hook.CandidateProbeHook;
import com.chiron.iosemoji.hook.HookRegistry;
import com.chiron.iosemoji.hook.KeyboardRootHook;
import com.chiron.iosemoji.hook.SetKeyboardViewHook;

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

    private static final String TAG = "ChironIOSInput";
    private static final AtomicBoolean ATTACHED = new AtomicBoolean(false);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam == null || !Protocol.isTarget(lpparam.packageName)) return;

        HookRegistry.log("load " + lpparam.packageName + " process=" + lpparam.processName);
        ModuleConfig.reload();

        // ---- 类级 hook（不需要 Context）----
        if (VersionProfile.allowUiReplacement() || HookState.versionName().equals("unknown")) {
            // 首次进入时还读不到版本，先尝试安装；失败由 Registry 记录，attach 后会重试
            tryInstallKeyboardHooks(lpparam.classLoader);
        }
        if (ModuleConfig.candidateProbeEnabled()) {
            try {
                CandidateProbeHook.install(lpparam.classLoader);
            } catch (Throwable t) {
                HookRegistry.log(t);
            }
        }

        // ---- 旧的 v1.8 功能（表情/分组/Logo）----
        installLegacyHooks(lpparam.classLoader);

        // ---- 等 Context ----
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object arg = param.args[0];
                                if (!(arg instanceof Context)) return;
                                final Context ctx = (Context) arg;
                                if (!Protocol.isTarget(ctx.getPackageName())) return;
                                if (!ATTACHED.compareAndSet(false, true)) return;

                                HookState.setContext(ctx);
                                HookState.setVersionName(readVersionName(ctx));
                                ModuleConfig.reload();
                                HookRegistry.log("attached: " + VersionProfile.describe()
                                        + " config: kb=" + ModuleConfig.keyboardReplaceEnabled()
                                        + " emoji=" + ModuleConfig.emojiGroupFilterEnabled());

                                // 版本已确定，重新尝试键盘 hook（Registry 幂等，失败过的会重试）
                                tryInstallKeyboardHooks(ctx.getClassLoader());

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
                    });
        } catch (Throwable t) {
            HookRegistry.log("hook Application#attach failed");
            HookRegistry.log(t);
        }
    }

    private static void tryInstallKeyboardHooks(ClassLoader cl) {
        if (!ModuleConfig.keyboardReplaceEnabled()) {
            HookRegistry.log("keyboard replace disabled by config");
            return;
        }
        if (!VersionProfile.allowUiReplacement() && !"unknown".equals(HookState.versionName())) {
            HookRegistry.log("unsupported version, keyboard hooks skipped: "
                    + VersionProfile.describe());
            return;
        }
        try {
            KeyboardRootHook.install(cl);
        } catch (Throwable t) {
            HookRegistry.log(t);
        }
        try {
            SetKeyboardViewHook.install(cl);
        } catch (Throwable t) {
            HookRegistry.log(t);
        }
    }

    private static void installLegacyHooks(ClassLoader cl) {
        if (ModuleConfig.emojiSwipeEnabled()) {
            try {
                GestureHook.install(cl);
            } catch (Throwable t) {
                HookRegistry.log(t);
            }
        }
        if (ModuleConfig.emojiGroupFilterEnabled()) {
            try {
                GroupFilterHook.install(cl);
            } catch (Throwable t) {
                HookRegistry.log(t);
            }
        }
        if (ModuleConfig.logoTriggerEnabled()) {
            try {
                LogoHook.install();
            } catch (Throwable t) {
                HookRegistry.log(t);
            }
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
