package com.chiron.iosemoji.hook;

import android.view.View;

import com.chiron.iosemoji.compat.Targets;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * H02：{@code <rootContainer>#setKeyboardView(Landroid/view/View;)V}（只做配置对齐）
 *
 * <p>历史上这个 hook 用来把模块自绘的键盘塞进搜狗输入视图（z=3），
 * 但自绘键盘会跟搜狗自己算的布局/命中区打架（样式错乱），已整体下线（v2.0.3-beta2）。
 *
 * <p>现在它只负责一件事：<b>键盘每次被装上时对齐一次模块配置</b>。
 * 国产 ROM（HyperOS/ColorOS）会拦掉应用间广播，配置下发不可靠；
 * 而「改完设置 → 点输入框 → 键盘重新弹出」这个动作用户一定会做，
 * 于是把重读配置挂在这里最稳，且不改动搜狗任何行为。
 */
public final class SetKeyboardViewHook {

    public static final String ID = "H02_input_view_resync";

    private SetKeyboardViewHook() {
    }

    public static void install(final ClassLoader classLoader, Targets targets) {
        final String CLS_ROOT_CONTAINER = targets == null ? null : targets.rootContainerClass;
        if (CLS_ROOT_CONTAINER == null) return;
        HookRegistry.install(ID, classLoader, new HookRegistry.Installer() {
            @Override
            public void install(ClassLoader cl) throws Throwable {
                XposedHelpers.findAndHookMethod(CLS_ROOT_CONTAINER, cl, "setKeyboardView",
                        View.class, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    ConfigBridge.resyncNow();
                                } catch (Throwable t) {
                                    HookRegistry.log("H02 resync failed");
                                    HookRegistry.log(t);
                                }
                                // H70：液态玻璃的另一半 —— 给这块面板开澎湃实时背景模糊。
                                // 拿的正是这次装进来的键盘视图，往上找到整块面板（键盘 + 候选栏）。
                                try {
                                    if (param.args != null && param.args.length > 0
                                            && param.args[0] instanceof View) {
                                        HyperGlassHook.onInputView((View) param.args[0]);
                                    }
                                } catch (Throwable t) {
                                    HookRegistry.log("H70 apply failed");
                                    HookRegistry.log(t);
                                }
                            }
                        });
            }
        });
    }
}