package com.chiron.iosemoji.hook;

import android.content.Context;
import android.view.View;

import com.chiron.iosemoji.compat.Targets;
import com.chiron.iosemoji.compat.VersionProfile;
import com.chiron.iosemoji.config.ModuleConfig;
import com.chiron.iosemoji.core.HookState;
import com.chiron.iosemoji.keyboard.ChironKeyboardView;
import com.chiron.iosemoji.keyboard.KeyboardFactory;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * H01：{@code com.sogou.lib.bu.ui.component.keyboard.d#getRootView()Landroid/view/View;}
 *
 * <p>为什么这是最优注入点（docs/HOOK_POINTS.md §2 H01）：
 * 全工程所有把键盘装进 z=3 的地方都写成 {@code setKeyboardView(<s0>.getRootView())}：
 * <pre>
 *   sogou/g0.java:414, :666            （悬浮键盘 / 常规键盘）
 *   sogou/MainImeServiceDel.java:2326  （主路径）
 *   sogou/manager/j.java:299           （表情面板）
 *   main/view/c.java:60                （初次装配）
 * </pre>
 * 一次 hook 覆盖全部重装路径。
 *
 * <p>风险：{@code getRootView()} 也被尺寸镜像逻辑使用（ui/frame/e.java:40-56），
 * 因此我们的 View 必须自己算好高度（{@link ChironKeyboardView#onMeasure} 已处理）。
 */
public final class KeyboardRootHook {

    public static final String ID = "H01_keyboard_getRootView";


    private KeyboardRootHook() {
    }

    public static void install(final ClassLoader classLoader, Targets targets) {
        final String CLS_KEYBOARD = targets == null ? null : targets.keyboardFacadeClass;
        if (CLS_KEYBOARD == null) return;
        HookRegistry.install(ID, classLoader, new HookRegistry.Installer() {
            @Override
            public void install(ClassLoader cl) throws Throwable {
                XposedHelpers.findAndHookMethod(CLS_KEYBOARD, cl, "getRootView", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (!VersionProfile.allowUiReplacement()) return;
                            if (!ModuleConfig.keyboardReplaceEnabled()) return;
                            Context ctx = HookState.context();
                            if (ctx == null) return;
                            Object result = param.getResult();
                            if (result instanceof ChironKeyboardView) return;
                            if (result == null) return; // 键盘尚未创建，交给 H02 兜底
                            View our = KeyboardFactory.obtain(ctx);
                            if (our != null) {
                                param.setResult(our);
                                HookRegistry.log("H01 replaced keyboard root view");
                            }
                        } catch (Throwable t) {
                            HookRegistry.log("H01 after failed");
                            HookRegistry.log(t);
                        }
                    }
                });
            }
        });
    }
}
