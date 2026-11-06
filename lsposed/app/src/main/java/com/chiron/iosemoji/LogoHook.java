package com.chiron.iosemoji;

import android.inputmethodservice.InputMethodService;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 输入显式触发词（默认「苹果logo」「pgl」等），上屏后变成苹果私用区字形 U+F8FF。
 *
 * 为什么不去改候选条
 * ─────────────────
 * 搜狗的候选词是 native 引擎算出来的（libSogouIME 一类），Java 侧只负责把算好的
 * 字符串塞进候选 View。想在「候选列表」里凭空插一条，就得复刻它的候选对象
 * （字符串 + 类型 + 编码 + 点击后回传的 id），这套结构每个版本都在变，反编译
 * 对不上就崩输入法。所以这里选上屏这一层：候选条照常显示「苹果」，用户选定后，
 * 在文本离开输入法之前把整串换成 logo。改动只发生在一个字符串参数上，
 * 不碰搜狗任何私有类，版本升级基本不会失效。
 *
 * 真正「候选条里就出现 logo」的做法是搜狗自带的「自定义短语」，见 README。
 *
 * 挂载点
 * ──────
 *   InputMethodService.getCurrentInputConnection()  → 拿到 IME 侧的 InputConnection
 *   对它实际实现的类 hookAllMethods("commitText")
 *
 * 不用 InputConnectionWrapper 子类包装，是因为 API 33 起多了
 * commitText(CharSequence, int, TextAttribute) 这个重载，包装类只会转发到
 * target 的三参方法，绕过我们的两参重写；而在这个类上 hookAllMethods 能把
 * 所有重载一次盖住，也不用在签名里引用 API 33 才有的 TextAttribute
 * （否则 minSdk 26 的机器上类校验可能出问题）。
 */
final class LogoHook {

    private static final String TAG = "AppleInputForSogou";

    /**
     * 苹果私用区码位，iOS / macOS 上打出来就是苹果标志。
     * 只有当前字体带这个字形时才显示成 logo——即 Chiron 6.4 及以上；
     * 换回系统字体后这里会是一个豆腐块。
     */
    static final char LOGO = '\uF8FF';

    /**
     * 触发词，全等匹配（不做包含匹配，避免「苹果手机」被误替换）。
     *
     * ⚠ 默认只放「显式写法」。像 "苹果" 这种常用词不要放进来：
     *   一旦放进来，你就再也打不出「苹果」这两个字；而且如果同时还给搜狗加了
     *   「自定义短语」（编码 pg / pingguo → 🍎 / ），用户点选「苹果」后会被这里
     *   再换成 U+F8FF，两条链路打架。
     *
     * 想要「打中文直接替换成 logo」，把它加到数组里即可，比如 {"苹果", "pgl"}。
     */
    private static final String[] TRIGGERS = {
            "苹果logo", "苹果标志",
            "pgl", "PGL", "pglogo", "PGlogo",
    };

    /** 边打字边替换：开着更接近「弹出」，但会让搜狗的内部缓冲区与编辑器不一致，退格会怪。 */
    private static final boolean LIVE_PREVIEW = false;

    private static final AtomicBoolean ENTRY_DONE = new AtomicBoolean(false);

    /** 已经挂过的 InputConnection 实现类。搜狗一次输入会话只取一次连接，重复挂没有意义。 */
    private static final Set<Class<?>> HOOKED =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<Class<?>, Boolean>()));

    private static int hitCount = 0;

    private LogoHook() {
    }

    static void install() {
        if (!ENTRY_DONE.compareAndSet(false, true)) return;
        boolean ok = false;
        try {
            // 基类实现。搜狗若自己重写了这个方法，onCreate 里再补一次。
            XposedHelpers.findAndHookMethod(InputMethodService.class,
                    "getCurrentInputConnection", CONNECTION);
            ok = true;
        } catch (Throwable t) {
            EmojiService.XposedLog.e("logo hook: getCurrentInputConnection failed", t);
        }
        try {
            XposedHelpers.findAndHookMethod(InputMethodService.class, "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            hookSubclassOverride(param.thisObject);
                        }
                    });
            ok = true;
        } catch (Throwable t) {
            EmojiService.XposedLog.e("logo hook: onCreate failed", t);
        }
        if (!ok) {
            ENTRY_DONE.set(false);
            return;
        }
        EmojiService.XposedLog.i("logo hook installed, triggers=" + TRIGGERS.length);
    }

    /**
     * 子类自己声明了 getCurrentInputConnection 的话，基类那份永远不会被调用，
     * 这里补挂在真正生效的那个实现上。
     */
    private static void hookSubclassOverride(Object service) {
        if (service == null) return;
        try {
            Class<?> c = service.getClass();
            try {
                c.getDeclaredMethod("getCurrentInputConnection");
            } catch (NoSuchMethodException notOverridden) {
                return;
            }
            XposedHelpers.findAndHookMethod(c, "getCurrentInputConnection", CONNECTION);
            EmojiService.XposedLog.i("logo hook: subclass override " + c.getName());
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static final XC_MethodHook CONNECTION = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object ic = param.getResult();
                if (ic == null) return;
                hookConnectionClass(ic.getClass());
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
        }
    };

    private static void hookConnectionClass(Class<?> c) {
        if (c == null || HOOKED.contains(c)) return;
        HOOKED.add(c);
        int n = 0;
        try {
            n += XposedBridge.hookAllMethods(c, "commitText", REWRITE).size();
        } catch (Throwable t) {
            EmojiService.XposedLog.e("logo hook: commitText on " + c.getName(), t);
        }
        if (LIVE_PREVIEW) {
            try {
                n += XposedBridge.hookAllMethods(c, "setComposingText", REWRITE).size();
            } catch (Throwable t) {
                EmojiService.XposedLog.e("logo hook: setComposingText on " + c.getName(), t);
            }
        }
        EmojiService.XposedLog.i("logo hook: " + n + " method(s) on " + c.getName());
    }

    private static final XC_MethodHook REWRITE = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (param.args == null || param.args.length == 0) return;
                Object first = param.args[0];
                if (!(first instanceof CharSequence)) return;
                String text = first.toString();
                if (!isTrigger(text)) return;
                param.args[0] = String.valueOf(LOGO);
                report(text);
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
        }
    };

    private static boolean isTrigger(String s) {
        if (s == null || s.isEmpty() || s.length() > 12) return false;
        for (String t : TRIGGERS) {
            if (t.equals(s)) return true;
        }
        return false;
    }

    /** 前几次替换写日志，方便确认链路通没通；之后不再刷。 */
    private static void report(String hit) {
        synchronized (LogoHook.class) {
            if (hitCount >= 5) return;
            hitCount++;
            EmojiService.XposedLog.i("logo: \"" + hit + "\" -> U+F8FF");
        }
    }
}
