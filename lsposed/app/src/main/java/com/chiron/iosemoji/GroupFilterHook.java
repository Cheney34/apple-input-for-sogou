package com.chiron.iosemoji;

import android.content.res.AssetManager;

import java.io.File;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 把搜狗内置的「组合」(id = 2) 和「拼装」(id = -2) 两个表情分组彻底从面板里摘掉。
 *
 * 为什么不直接删目录：这两个包不是用户数据，而是 APK 里 assets/exp_emoji_group 和
 * assets/assemble_emoji_info 两份清单，由 ExpressionUtil.w1() 在每次进程启动时
 * 校验后重新解压到 files/sogou/emojipackage/。删掉目录，下次启动又回来。
 *
 * 所以从两个必经之路下手，都不改磁盘，卸载模块即完全恢复：
 *
 *   1. java.io.File.list()
 *      搜狗所有分组枚举（ExpressionUtil.m0 / n0 / o0 …）都是
 *      new File(emojipackage).list() 之后按 "_" 切分目录名。在这里把
 *      2_xxx 与 -2_xxx 摘掉，上层拿到的列表里就没有这两项了，
 *      排序、分页、tab 全都是基于这份列表，自然一起消失。
 *
 *   2. ExpressionUtil.u(AssetManager, src, dst)
 *      第 1 步让 m0() 看不到内置包，w1() 会误以为本地缺失而重新解压。
 *      u() 是「把 assets 里某个文件写到 dst」的原子方法，目标属于这两个
 *      分组时直接返回 true，既不写盘也不报错，w1() 会当成已完成。
 *
 * 两步都不碰文件系统，风险可控。
 */
final class GroupFilterHook {

    private static final String TAG = "AppleInputForSogou";

    /** 分组目录的路径后缀，d.q 实际取值是 <filesDir>/sogou/emojipackage。 */
    private static final String DIR_SUFFIX = "/sogou/emojipackage";

    /** 要屏蔽的分组目录名前缀：2_1789… 与 -2_1789…。 */
    private static final String[] BLOCKED_PREFIX = {"2_", "-2_"};

    private static final String CLS_EXPR_UTIL =
            "com.sogou.expressionplugin.expression.ExpressionUtil";

    private static final Object LOCK = new Object();

    private static boolean listInstalled = false;
    private static boolean writeInstalled = false;
    private static boolean reported = false;

    private GroupFilterHook() {
    }

    static void install(ClassLoader cl) {
        if (cl == null) return;
        synchronized (LOCK) {
            if (!listInstalled && hookFileList()) listInstalled = true;
            // 目标是搜狗自己的类，进程早期可能还没加载，失败就让下次调用再试
            if (!writeInstalled && isReady(cl) && hookAssetWrite(cl)) writeInstalled = true;
            if ((listInstalled || writeInstalled) && !reported) {
                reported = true;
                EmojiService.XposedLog.i("group filter: list=" + listInstalled
                        + " write=" + writeInstalled);
            }
        }
    }

    private static boolean isReady(ClassLoader cl) {
        try {
            cl.loadClass(CLS_EXPR_UTIL);
            return true;
        } catch (Throwable notYet) {
            return false;
        }
    }

    // ── 1. 目录枚举 ─────────────────────────────────────────────────────────

    private static boolean hookFileList() {
        try {
            XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object result = param.getResult();
                        if (!(result instanceof String[])) return;
                        Object self = param.thisObject;
                        if (!(self instanceof File)) return;
                        if (!isEmojiDir(((File) self).getPath())) return;
                        String[] kept = filterNames((String[]) result);
                        if (kept != null) param.setResult(kept);
                    } catch (Throwable t) {
                        XposedBridge.log(t);
                    }
                }
            };
            XposedHelpers.findAndHookMethod(File.class, "list", hook);
            return true;
        } catch (Throwable t) {
            EmojiService.XposedLog.e("hook File.list failed", t);
            return false;
        }
    }

    // ── 2. 内置包解压 ───────────────────────────────────────────────────────

    private static boolean hookAssetWrite(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(CLS_EXPR_UTIL, cl, "u",
                    AssetManager.class, String.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object dst = param.args.length > 2 ? param.args[2] : null;
                                if (!(dst instanceof String)) return;
                                if (isBlockedTarget((String) dst)) {
                                    param.setResult(Boolean.TRUE);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(t);
                            }
                        }
                    });
            return true;
        } catch (Throwable t) {
            // 不同版本方法名可能变化，失败不影响第 1 步
            EmojiService.XposedLog.e("hook ExpressionUtil.u failed", t);
            return false;
        }
    }

    // ── 判定 ────────────────────────────────────────────────────────────────

    private static boolean isEmojiDir(String path) {
        if (path == null) return false;
        int end = path.length();
        while (end > 0 && path.charAt(end - 1) == '/') end--;
        String trimmed = end == path.length() ? path : path.substring(0, end);
        return trimmed.endsWith(DIR_SUFFIX);
    }

    private static boolean isBlockedName(String name) {
        if (name == null) return false;
        for (String prefix : BLOCKED_PREFIX) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    /** dst 形如 …/emojipackage/2_1789…/emoji/xxx.png。 */
    private static boolean isBlockedTarget(String dst) {
        int idx = dst.indexOf("/emojipackage/");
        if (idx < 0) return false;
        String rest = dst.substring(idx + "/emojipackage/".length());
        int slash = rest.indexOf('/');
        String group = slash < 0 ? rest : rest.substring(0, slash);
        return isBlockedName(group);
    }

    /** 没有命中任何一条时返回 null，避免无谓的数组复制。 */
    private static String[] filterNames(String[] names) {
        int kept = 0;
        for (String name : names) {
            if (!isBlockedName(name)) kept++;
        }
        if (kept == names.length) return null;
        String[] out = new String[kept];
        int i = 0;
        for (String name : names) {
            if (!isBlockedName(name)) out[i++] = name;
        }
        return out;
    }
}
