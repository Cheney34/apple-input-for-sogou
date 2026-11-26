package com.chiron.iosemoji.hook;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import com.chiron.iosemoji.compat.Targets;
import com.chiron.iosemoji.config.ModuleConfig;
import com.chiron.iosemoji.core.HookState;
import com.chiron.iosemoji.skin.IosSkin;
import com.chiron.iosemoji.skin.SkinPreset;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 面板 iOS 化（<b>实验功能，默认全部关闭</b>）—— 候选栏 / 长按气泡 / 表情面板。
 *
 * <p>为什么单独一个类、并且默认关：这三处都是搜狗自己的 View，改它们的背景等于替搜狗"重画 UI"，
 * 一旦某个渠道的对象树形状不同就可能出现「键盘弹不出来」这类致命问题。所以：
 * <ul>
 *   <li>每个面板一个独立开关，出问题只关一个；</li>
 *   <li>只改 {@code setBackground}（纯装饰，不改布局、不改测量、不加子 View）；</li>
 *   <li>类名一律来自 {@link Targets} 或已核实的公开类名，找不到就跳过并记日志；</li>
 *   <li>全部 try/catch，失败绝不打断搜狗。</li>
 * </ul>
 *
 * <p>诊断：{@link #state()} 会回报每个面板「接管次数」与「是否找到类」。
 */
public final class PanelIosHook {

    /** 小米/官方共有的候选栏容器（已核对存在）。 */
    private static final String[] CANDIDATE_CONTAINERS = {
            "com.sohu.inputmethod.main.view.IMEInputCandidateViewContainer",
    };
    /** 表情面板容器与表情键盘（三渠道都有，已核对存在）。 */
    private static final String[] EMOJI_CONTAINERS = {
            "com.sogou.expressionplugin.expression.ExpressionViewContainer",
            "com.sogou.expressionplugin.emoji.EmojiKeyboardView",
    };
    /** 长按气泡（小米定制版已核对存在；官方/OPPO/vivo 找不到就跳过）。 */
    private static final String[] POPUP_VIEWS = {
            "com.sohu.inputmethod.sogou.KeyboardPopupView",
            "com.sohu.inputmethod.sogou.AbsKeyPopupView",
    };

    private static final String ID = "H80_panel_ios";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile int sCandidateTakes;
    private static volatile int sEmojiTakes;
    private static volatile int sPopupTakes;
    private static volatile int sMissing;

    private PanelIosHook() {
    }

    public static void install(ClassLoader cl, Targets targets) {
        if (cl == null) return;
        HookRegistry.install(ID, cl, new HookRegistry.Installer() {
            @Override
            public void install(ClassLoader classLoader) {
                hookAll(classLoader, CANDIDATE_CONTAINERS, "candidate");
                hookAll(classLoader, EMOJI_CONTAINERS, "emoji");
                hookAll(classLoader, POPUP_VIEWS, "popup");
                HookRegistry.log("H80 panel-ios installed: candidate=" + CANDIDATE_CONTAINERS.length
                        + " emoji=" + EMOJI_CONTAINERS.length + " popup=" + POPUP_VIEWS.length);
            }
        });
    }

    private static void hookAll(ClassLoader cl, String[] classNames, final String kind) {
        for (String name : classNames) {
            try {
                Class<?> cls = XposedHelpers.findClass(name, cl);
                XposedBridge.hookAllConstructors(cls, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (!(param.thisObject instanceof View)) return;
                            if (!enabledFor(kind)) return;
                            final View v = (View) param.thisObject;
                            MAIN.post(new Runnable() {
                                @Override
                                public void run() {
                                    paint(v, kind);
                                }
                            });
                        } catch (Throwable t) {
                            HookRegistry.log("H80 " + kind + " ctor failed: " + t);
                        }
                    }
                });
                HookRegistry.log("H80 hooked " + name + " (" + kind + ")");
            } catch (Throwable notFound) {
                sMissing++;
                HookRegistry.log("H80 skip " + name + " (" + kind + "): not in this channel");
            }
        }
    }

    private static boolean enabledFor(String kind) {
        if (!ModuleConfig.masterEnabled()) return false;
        if ("candidate".equals(kind)) return ModuleConfig.candidateBarIosEnabled();
        if ("emoji".equals(kind)) return ModuleConfig.emojiPanelIosEnabled();
        if ("popup".equals(kind)) return ModuleConfig.popupIosEnabled();
        return false;
    }

    /** 只设背景：圆角 + 预设底色 + 一道发丝线。不改 layout params。 */
    private static void paint(View v, String kind) {
        try {
            if (v == null || v.getContext() == null) return;
            if (!enabledFor(kind)) return;
            Context ctx = v.getContext();
            SkinPreset p = SkinPreset.of(ModuleConfig.skinPreset(), IosSkin.isDark(null, ctx), ctx);
            if (p == null) return;
            int color;
            float topDp = 12f;
            if ("candidate".equals(kind)) {
                color = p.barBg;
                sCandidateTakes++;
            } else if ("emoji".equals(kind)) {
                color = p.kbBg;
                sEmojiTakes++;
            } else {
                color = p.popup;
                topDp = 10f;
                sPopupTakes++;
            }
            GradientDrawable d = new GradientDrawable();
            d.setColor(color);
            float r = topDp * ctx.getResources().getDisplayMetrics().density;
            // 只圆上面两个角（贴着键盘的那一侧），下面保持直角，视觉上才像一块"连着的面板"
            d.setCornerRadii(new float[]{r, r, r, r, 0f, 0f, 0f, 0f});
            d.setStroke(1, alphaOf(p.text, 0.12f));
            v.setBackground(d);
            HookRegistry.log("H80 " + kind + " bg -> #" + Integer.toHexString(color));
        } catch (Throwable t) {
            HookRegistry.log("H80 paint " + kind + " failed: " + t);
        }
    }

    private static int alphaOf(int rgb, float a) {
        int alpha = Math.max(0, Math.min(255, Math.round(255 * a)));
        return (alpha << 24) | (rgb & 0x00FFFFFF);
    }

    /** 设置页/状态串用。 */
    public static String state() {
        return sCandidateTakes + "/" + sEmojiTakes + "/" + sPopupTakes + "/" + sMissing;
    }

    public static String summary() {
        return "panel(cand=" + sCandidateTakes + ",emoji=" + sEmojiTakes
                + ",popup=" + sPopupTakes + ",miss=" + sMissing + ")";
    }

    static {
        // 供 ThemeSkinHook.dropCaches() 之类的调用方在换预设后触发重涂：
        // 面板背景是"设一次就固定"的，换预设必须重新设一遍。
    }
}