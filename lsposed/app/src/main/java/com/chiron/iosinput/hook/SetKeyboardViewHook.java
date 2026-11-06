package com.chiron.iosinput.hook;

import android.content.Context;
import android.view.View;
import android.widget.RelativeLayout;

import com.chiron.iosinput.compat.VersionProfile;
import com.chiron.iosinput.config.ModuleConfig;
import com.chiron.iosinput.core.HookState;
import com.chiron.iosinput.keyboard.ChironKeyboardView;
import com.chiron.iosinput.keyboard.KeyboardFactory;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * H02：{@code com.sohu.inputmethod.ui.frame.NormalIMERootContainer#setKeyboardView(Landroid/view/View;)V}
 *
 * <p>作用：H01 的兜底。若某个调用点没有走 {@code getRootView()}（或被别处覆盖），
 * 这里在方法执行后用同一个 z-index(3) 把我们的 View 再塞进去。
 *
 * <p>原方法行为（jadx ui/frame/NormalIMERootContainer.java:946-985）：
 * 设 id、RelativeLayout.LayoutParams(-2,-2)、按 {@code instanceof s0} 决定 rule 6/3、
 * {@code addChildView(view, 3)}、{@code updateAllChildren()}、通知 mKeyboardViewChangedListener。
 * 我们只复用最后两步，不改动它的任何逻辑。
 *
 * <p>已知限制：MVP 阶段我们的 View **没有**实现 {@code com.sohu.inputmethod.sogou.s0}，
 * 因此原方法会按"非 s0"分支设置顶边规则（rule 3），在部分主题下可能与其期望的
 * 间距不同——这在 docs/MVP_PROPOSAL.md §7 已列为已知限制，Phase 3 会实现 s0 空壳。
 */
public final class SetKeyboardViewHook {

    public static final String ID = "H02_ime_root_setKeyboardView";

    private static final String CLS_ROOT_CONTAINER = "com.sohu.inputmethod.ui.frame.NormalIMERootContainer";

    private SetKeyboardViewHook() {
    }

    public static void install(final ClassLoader classLoader) {
        HookRegistry.install(ID, classLoader, new HookRegistry.Installer() {
            @Override
            public void install(ClassLoader cl) throws Throwable {
                XposedHelpers.findAndHookMethod(CLS_ROOT_CONTAINER, cl, "setKeyboardView",
                        View.class, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    if (!VersionProfile.allowUiReplacement()) return;
                                    if (!ModuleConfig.keyboardReplaceEnabled()) return;
                                    Object container = param.thisObject;
                                    if (container == null) return;
                                    Object arg = param.args[0];
                                    if (arg instanceof ChironKeyboardView) return;

                                    Context ctx = HookState.context();
                                    if (ctx == null && arg instanceof View) {
                                        ctx = ((View) arg).getContext();
                                        HookState.setContext(ctx);
                                    }
                                    if (ctx == null) return;

                                    View our = KeyboardFactory.obtain(ctx);
                                    if (our == null) return;
                                    if (our.getParent() == container) return;

                                    our.setLayoutParams(new RelativeLayout.LayoutParams(
                                            RelativeLayout.LayoutParams.MATCH_PARENT,
                                            RelativeLayout.LayoutParams.WRAP_CONTENT));
                                    XposedHelpers.callMethod(container, "addChildView", our, 3);
                                    XposedHelpers.callMethod(container, "updateAllChildren");
                                    HookRegistry.log("H02 injected Chiron keyboard at z=3");
                                } catch (Throwable t) {
                                    HookRegistry.log("H02 after failed");
                                    HookRegistry.log(t);
                                }
                            }
                        });
            }
        });
    }
}
