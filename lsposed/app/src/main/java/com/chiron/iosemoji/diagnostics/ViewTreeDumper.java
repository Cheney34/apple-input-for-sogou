package com.chiron.iosemoji.diagnostics;

import android.view.View;
import android.view.ViewGroup;

import com.chiron.iosemoji.core.HookState;
import com.chiron.iosemoji.hook.HookRegistry;

import de.robv.android.xposed.XposedHelpers;

/**
 * Phase 1 诊断：打印输入视图树。
 *
 * <p>它回答静态分析无法确定的问题（docs/VIEW_HIERARCHY.md §9）：
 * z=2 / z=3 实际装的是什么类、尺寸多少、是否被我们的 View 顶替。
 *
 * <p>输出示例：
 * <pre>
 * IMEView[0] StyleConstraintLayout#7f0a0b1c vis=0 1080x1280
 *   IMEView[1] WindowBackgroundLayout#7f0a0b1d vis=0 1080x1280
 *   IMEView[1] NormalIMERootContainer#7f0a0b1e vis=0 1080x900
 *     IMEView[2] IMEInputCandidateViewContainer#7f0a0b20 vis=0 1080x180
 *     IMEView[2] ChironKeyboardView#-1 vis=0 1080x640
 * </pre>
 */
public final class ViewTreeDumper {

    private ViewTreeDumper() {
    }

    /** 打印当前输入窗口的完整视图树（需要搜狗已经创建过根容器）。 */
    public static void dumpInputView() {
        try {
            View root = findInputViewRoot();
            if (root == null) {
                HookRegistry.log("dump: input view root not found yet");
                return;
            }
            HookRegistry.log("dump: input view tree\n" + dump(root));
        } catch (Throwable t) {
            HookRegistry.log("dump failed");
            HookRegistry.log(t);
        }
    }

    /** 递归 dump，返回多行字符串。 */
    public static String dump(View v) {
        StringBuilder sb = new StringBuilder();
        dump(v, 0, sb);
        return sb.toString();
    }

    private static void dump(View v, int depth, StringBuilder sb) {
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append("IMEView[").append(depth).append("] ")
                .append(v.getClass().getSimpleName())
                .append('#').append(Integer.toHexString(v.getId()))
                .append(" vis=").append(v.getVisibility())
                .append(' ').append(v.getWidth()).append('x').append(v.getHeight());
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            sb.append(" children=").append(g.getChildCount());
        }
        sb.append('\n');
        if (v instanceof ViewGroup && depth < 12) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                dump(g.getChildAt(i), depth + 1, sb);
            }
        }
    }

    /** b0.f0().e0() → NormalIMERootContainer（见 docs/CLASS_MAP.md §4）。 */
    private static View findInputViewRoot() throws Throwable {
        if (HookState.context() == null) return null;
        ClassLoader cl = HookState.context().getClassLoader();
        Class<?> managerCls = Class.forName("com.sohu.inputmethod.main.manager.b0", false, cl);
        Object manager = XposedHelpers.callStaticMethod(managerCls, "f0");
        if (manager == null) return null;
        Object container = XposedHelpers.callMethod(manager, "e0");
        return container instanceof View ? (View) container : null;
    }
}
