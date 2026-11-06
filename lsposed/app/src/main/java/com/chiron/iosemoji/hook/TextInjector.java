package com.chiron.iosemoji.hook;

import android.view.KeyEvent;
import android.view.inputmethod.InputConnection;

import com.chiron.iosemoji.core.HookState;

import de.robv.android.xposed.XposedHelpers;

/**
 * 文字/按键注入器。
 *
 * <p>**不 hook 任何东西**，只调用搜狗自己的公开入口，从而保证"搜狗负责输入"：
 * <ul>
 *   <li>字符 → {@code com.sohu.inputmethod.input.InputLogic#onText(CharSequence,int)}（v=0）</li>
 *   <li>功能键 → {@code InputLogic#onKey(int,int[],boolean,int,int)}，失败再退到
 *       {@code com.sogou.bu.basic.ic.g} 的 InputConnection</li>
 * </ul>
 * 这些描述符均取自 DEX 索引（见 docs/EVENT_CALLBACK_MAP.md §4），不是猜的。
 */
public final class TextInjector {

    private static final String CLS_INPUT_SINGLETON = "com.sohu.inputmethod.input.i";
    private static final String CLS_MANAGER = "com.sohu.inputmethod.main.manager.b0";
    private static final String CLS_SERVICE = "com.sohu.inputmethod.sogou.MainImeServiceDel";

    private static final int CODE_BACKSPACE = -5;
    private static final int CODE_ENTER = 10;
    private static final int CODE_SPACE = 32;

    private TextInjector() {
    }

    private static ClassLoader cl() {
        if (HookState.context() == null) return null;
        return HookState.context().getClassLoader();
    }

    /** 取搜狗自己的 InputLogic 单例：input.i.m().l()。 */
    private static Object inputLogic() throws Throwable {
        ClassLoader cl = cl();
        if (cl == null) throw new IllegalStateException("no context/classloader");
        Class<?> cls = Class.forName(CLS_INPUT_SINGLETON, false, cl);
        Object singleton = XposedHelpers.callStaticMethod(cls, "m");
        if (singleton == null) throw new IllegalStateException("input.i.m() == null");
        Object logic = XposedHelpers.callMethod(singleton, "l");
        if (logic == null) throw new IllegalStateException("input.i.l() == null");
        return logic;
    }

    /** 字符上屏（走完整搜狗链路：符号配对、联想、学习）。 */
    public static boolean type(CharSequence text) {
        try {
            XposedHelpers.callMethod(inputLogic(), "onText", text, 0);
            return true;
        } catch (Throwable t) {
            HookRegistry.log("type failed: " + text);
            HookRegistry.log(t);
            return commitRaw(text);
        }
    }

    /**
     * 按「键码」上屏 —— 字母/数字走这条路才能真正进搜狗的拼音/英文引擎（会出候选）。
     *
     * <p>键码取值与搜狗 {@code theme.data.key} 一致：字母 97..122、数字 48..57。
     * 走 {@code InputLogic#onKey(int,int[],boolean,int,int)}，见 docs/EVENT_CALLBACK_MAP.md §4。
     */
    public static boolean key(int code) {
        try {
            XposedHelpers.callMethod(inputLogic(), "onKey", code, new int[]{code}, false, 0, 0);
            return true;
        } catch (Throwable t) {
            HookRegistry.log("key(" + code + ") via InputLogic failed, fallback to onText");
            return type(String.valueOf((char) code));
        }
    }

    public static boolean backspace() {
        try {
            XposedHelpers.callMethod(inputLogic(), "onKey",
                    CODE_BACKSPACE, new int[]{CODE_BACKSPACE}, false, 0, 0);
            return true;
        } catch (Throwable t) {
            HookRegistry.log("backspace via InputLogic failed, fallback to InputConnection");
            return deleteOneChar();
        }
    }

    public static boolean space() {
        try {
            XposedHelpers.callMethod(inputLogic(), "onKey",
                    CODE_SPACE, new int[]{CODE_SPACE}, false, 0, 0);
            return true;
        } catch (Throwable t) {
            return commitRaw(" ");
        }
    }

    public static boolean enter() {
        try {
            XposedHelpers.callMethod(inputLogic(), "onKey",
                    CODE_ENTER, new int[]{CODE_ENTER}, false, 0, 0);
            return true;
        } catch (Throwable t) {
            return sendKeyEvent(KeyEvent.KEYCODE_ENTER);
        }
    }

    /**
     * 最裸兜底：直接拿到上屏通道提交文字。
     * 只有当 InputLogic 不可用时才会走到这里（例如版本升级导致方法名变化）。
     */
    private static boolean commitRaw(CharSequence text) {
        try {
            InputConnection ic = inputConnection();
            if (ic == null) return false;
            return ic.commitText(text, 1);
        } catch (Throwable t) {
            HookRegistry.log(t);
            return false;
        }
    }

    private static boolean deleteOneChar() {
        try {
            InputConnection ic = inputConnection();
            if (ic == null) return false;
            return ic.deleteSurroundingText(1, 0);
        } catch (Throwable t) {
            HookRegistry.log(t);
            return false;
        }
    }

    private static boolean sendKeyEvent(int keyCode) {
        try {
            InputConnection ic = inputConnection();
            if (ic == null) return false;
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
            return true;
        } catch (Throwable t) {
            HookRegistry.log(t);
            return false;
        }
    }

    /** b0.f0().t0().getSogouInputConnection().l()  —— 见 docs/CLASS_MAP.md §4。 */
    private static InputConnection inputConnection() throws Throwable {
        ClassLoader cl = cl();
        if (cl == null) return null;
        Class<?> managerCls = Class.forName(CLS_MANAGER, false, cl);
        Object manager = XposedHelpers.callStaticMethod(managerCls, "f0");
        Object service = XposedHelpers.callMethod(manager, "t0");
        if (service == null && managerCls != null) {
            // 兼容：部分版本 t0() 是静态方法
            try {
                service = XposedHelpers.callStaticMethod(managerCls, "t0");
            } catch (Throwable ignored) {
                // 保持 null
            }
        }
        if (service == null) {
            Class<?> serviceCls = Class.forName(CLS_SERVICE, false, cl);
            service = XposedHelpers.callStaticMethod(serviceCls, "getInstance");
        }
        if (service == null) return null;
        Object icg = XposedHelpers.callMethod(service, "getSogouInputConnection");
        if (icg == null) return null;
        Object ic = XposedHelpers.callMethod(icg, "l");
        return ic instanceof InputConnection ? (InputConnection) ic : null;
    }
}
