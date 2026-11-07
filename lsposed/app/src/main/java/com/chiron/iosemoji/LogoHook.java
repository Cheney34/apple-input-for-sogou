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

    /**
     * 显式快捷码 → 上屏字符（Emoji 17.0 新增的 8 个）。
     *
     * 为什么需要这一层
     * ────────────────
     * 字体（Chiron 6.5）只解决「显示」：字符进到编辑器里，能不能画出彩色图由字体决定。
     * 「输入」是另一条链路——键盘面板里有没有这个格子，由输入法自己的表情清单决定。
     * 系统键盘（Gboard / 小米键盘）的清单是 APK 内置的，改字体对它没有任何影响，
     * 所以在那些键盘上，Emoji 17.0 的字符根本点不出来。
     *
     * 搜狗的面板清单在 files/sogou/emojipackage，本模块可以替换（见 EmojiService），
     * 那条路要装面板、重启输入法才会生效。这里再给一条更短的路：打完码直接上屏，
     * 不依赖面板数据、不依赖分组有没有装、也不依赖搜狗的版本。
     *
     * 码位用 appendCodePoint 现场拼，不写 \uD83E\uDEEA 这种代理对——
     * 手算错一个数就是豆腐块，而且肉眼看不出错。
     */
    private static final String[][] EMOJI_CODES = {
            {"pg1", cp(0x1FAEA)},                     // 变形的脸
            {"pg2", cp(0x1FAEF)},                     // 打斗云团
            {"pg3", cp(0x1FAC8)},                     // 毛怪
            {"pg4", cp(0x1FACD)},                     // 虎鲸
            {"pg5", cp(0x1F6D8)},                     // 山体滑坡
            {"pg6", cp(0x1FA8A)},                     // 长号
            {"pg7", cp(0x1FA8E)},                     // 宝箱
            {"pg8", cp(0x1F9D1, 0x200D, 0x1FA70)},    // 芭蕾舞者（ZWJ 序列）
    };

    private static String cp(int... codePoints) {
        StringBuilder sb = new StringBuilder(codePoints.length * 2);
        for (int c : codePoints) sb.appendCodePoint(c);
        return sb.toString();
    }

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
                // 运行中开关：关掉「Logo 触发词」就立刻恢复原文提交
                if (!com.chiron.iosemoji.config.ModuleConfig.logoTriggerEnabled()) return;
                if (param.args == null || param.args.length == 0) return;
                Object first = param.args[0];
                if (!(first instanceof CharSequence)) return;
                String text = first.toString();
                String out = rewrite(text);
                if (out == null) return;
                param.args[0] = out;
                report(text, out);
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
        }
    };

    /**
     * 全等匹配。命中返回替换后的字符串，未命中返回 null。
     *
     * 只做全等，不做包含：commitText 收到的是「这一下要提交的整串」，
     * 中文输入法上屏时是整词提交，所以全等就够；用包含会把「pg1很好用」这种
     * 正常句子里的词也吃掉。
     */
    private static String rewrite(String s) {
        if (s == null || s.isEmpty() || s.length() > 12) return null;
        // 英文候选上屏常常自带一个尾随空格（"pg1 "），要把空格留下来，
        // 否则替换完句子里会少一个分隔，下一个词直接粘上来。
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ' ') end--;
        String core = s.substring(0, end);
        String tail = s.substring(end);
        for (String t : TRIGGERS) {
            if (t.equals(core)) return LOGO + tail;
        }
        for (String[] pair : EMOJI_CODES) {
            if (pair[0].equalsIgnoreCase(core)) return pair[1] + tail;
        }
        return null;
    }

    /** 前几次替换写日志，方便确认链路通没通；之后不再刷。 */
    private static void report(String hit, String out) {
        synchronized (LogoHook.class) {
            if (hitCount >= 8) return;
            hitCount++;
            EmojiService.XposedLog.i("code: \"" + hit + "\" -> " + describeCodePoint(out));
        }
    }

    /** 日志里写码位而不是字形本身：LSPosed 日志页对补充平面字符经常显示成方块。 */
    private static String describeCodePoint(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); ) {
            int c = s.codePointAt(i);
            if (i > 0) sb.append(' ');
            sb.append(String.format("U+%04X", c));
            i += Character.charCount(c);
        }
        return sb.toString();
    }
}
