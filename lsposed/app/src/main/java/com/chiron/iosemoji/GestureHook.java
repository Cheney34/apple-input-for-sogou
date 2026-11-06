package com.chiron.iosemoji;

import android.content.Context;
import android.util.AttributeSet;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 把搜狗表情面板的「左右滑动切换分组」打开。
 *
 * 反编译 10.32.21 后确认，这套能力搜狗本来就写完了：
 *
 *   EmojiKeyboardView.initNormalEmojiGridView()
 *       → new NoScrollViewPager(context)
 *       → addOnPageChangeListener(new EmojiKeyboardView$e(this, ctx))
 *
 *   EmojiKeyboardView$e.onPageSelected(int pos)
 *       → mBottomTab.setChoosePos(pos - 1)              // 底部 tab 高亮跟随
 *       → setCollectAndHistorySelected(pos == 0)        // 「最近」按钮状态
 *       → adapter.h(pos).f(pos)                         // 刷新该页
 *
 * 页面总数 = 分组数 + 1（第 0 页是「最近使用」）。
 *
 * 唯一的闸门是 NoScrollViewPager.slide 这个布尔字段：onInterceptTouchEvent /
 * onTouchEvent 在 slide=false 时直接返回，手势永远不会往下传。而
 * showSlideAnimation() 会把它关掉 900ms，用于播放首次进入时的引导动画。
 *
 * 所以这里只做两件事：
 *   1. setScrollable 之后强制把 slide 置回 true，任何路径都关不掉；
 *   2. 跳过那次引导动画，避免它中途被打断时把 slide 卡在 false。
 */
final class GestureHook {

    private static final String TAG = "AppleInputForSogou";

    /** 定制版类名表（小米 / OPPO …）在 install 时注入。 */
    private static com.chiron.iosemoji.compat.Targets sTargets = com.chiron.iosemoji.compat.Targets.XIAOMI;


    private static final AtomicBoolean DONE = new AtomicBoolean(false);

    private GestureHook() {
    }

    static void install(ClassLoader cl) {
        install(cl, com.chiron.iosemoji.compat.Targets.XIAOMI);
    }

    static void install(ClassLoader cl, com.chiron.iosemoji.compat.Targets targets) {
        if (targets != null) sTargets = targets;
        if (cl == null) return;
        // 两个时机（handleLoadPackage / Application.attach）各调一次，
        // 哪次先成功就用哪次；重复调用只在第一次真正干活。
        if (!DONE.compareAndSet(false, true)) return;

        boolean ok = hookScrollable(cl);
        if (!ok) {
            // 类还没准备好，让下一次调用重试
            DONE.set(false);
            return;
        }
        hookIntroAnimation(cl);
    }

    private static boolean hookScrollable(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(sTargets.pagerClass, cl,
                    "setScrollable", boolean.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            unlock(param.thisObject);
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": pager.setScrollable hook failed: " + t);
            return false;
        }

        XC_MethodHook unlockOnCreate = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                unlock(param.thisObject);
            }
        };
        try {
            XposedHelpers.findAndHookConstructor(sTargets.pagerClass, cl,
                    Context.class, unlockOnCreate);
        } catch (Throwable ignored) {
            // 一个参数的构造不存在也正常
        }
        try {
            XposedHelpers.findAndHookConstructor(sTargets.pagerClass, cl,
                    Context.class, AttributeSet.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            unlock(param.thisObject);
                        }
                    });
        } catch (Throwable ignored) {
        }

        XposedBridge.log(TAG + ": pager hooked, slide forced on");
        return true;
    }

    private static void unlock(Object pager) {
        // 运行中开关：关掉「分组滑动」就立刻退回搜狗的原始限制，不需要重启输入法
        if (!com.chiron.iosemoji.config.ModuleConfig.emojiSwipeEnabled()) return;
        try {
            XposedHelpers.setBooleanField(pager, "slide", true);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": slide reset failed: " + t);
        }
    }

    private static void hookIntroAnimation(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(sTargets.emojiViewClass, cl,
                    "showSlideAnimation", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            // 开关关闭时保持搜狗原样（开场动画照旧）
                            if (!com.chiron.iosemoji.config.ModuleConfig.emojiSwipeEnabled()) {
                                return;
                            }
                            param.setResult(null);
                        }
                    });
            XposedBridge.log(TAG + ": intro animation skipped");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": intro hook failed: " + t);
        }
    }
}
