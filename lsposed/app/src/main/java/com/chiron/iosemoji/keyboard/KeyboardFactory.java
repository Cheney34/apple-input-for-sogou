package com.chiron.iosemoji.keyboard;

import android.content.Context;
import android.view.View;

import com.chiron.iosemoji.hook.HookRegistry;
import com.chiron.iosemoji.core.HookState;

/**
 * 键盘 View 工厂：进程内单例。
 *
 * <p>为什么必须是单例：`setKeyboardView()` 会反复调用（悬浮键盘、表情面板、语音等界面态切换），
 * 每次都给一个新 View 会造成 View 泄漏与状态丢失（大写态、光标位置等）。
 *
 * <p>另外，键盘 View 的 Context 必须是**输入法进程的 Context**（它不是 Activity），
 * 所以这里统一用第一次拿到的 Context 构造，后续复用。
 */
public final class KeyboardFactory {

    private static volatile ChironKeyboardView sKeyboard;

    private KeyboardFactory() {
    }

    /** 取（必要时创建）键盘 View；创建失败返回 null，调用方负责跳过替换。 */
    public static View obtain(Context context) {
        if (context == null) return null;
        ChironKeyboardView v = sKeyboard;
        if (v != null) return v;
        synchronized (KeyboardFactory.class) {
            if (sKeyboard == null) {
                try {
                    sKeyboard = new ChironKeyboardView(context);
                    HookRegistry.log("keyboard view created");
                } catch (Throwable t) {
                    HookRegistry.log("keyboard view create failed");
                    HookRegistry.log(t);
                    return null;
                }
            }
            return sKeyboard;
        }
    }

    /** 当前实例（可能为 null），供诊断使用。 */
    public static View peek() {
        return sKeyboard;
    }

    /** 主题/配置变化后重建。 */
    public static void reset() {
        synchronized (KeyboardFactory.class) {
            sKeyboard = null;
        }
        HookState.setKeyboardView(null);
    }
}
