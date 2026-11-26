package com.chiron.iosemoji.hook;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import com.chiron.iosemoji.compat.Targets;
import com.chiron.iosemoji.config.ModuleConfig;
import com.chiron.iosemoji.skin.IosSkin;
import com.chiron.iosemoji.skin.SkinPreset;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 面板 iOS 化（<b>实验功能，默认全部关闭</b>）—— 候选栏 / 长按气泡 / 表情面板。
 *
 * <p>设计原则（v2.4.0-beta2 按真机反馈收紧）：
 * <ul>
 *   <li>只改 {@code setBackground} + {@code setClipToOutline}：不改布局、不改测量、不加子 View；
 *       {@code clipToOutline} 会把子 View 也裁进圆角里，专门解决"我们画了圆角、底下还有个方框"；</li>
 *   <li>颜色一律取<b>相邻那块面板也在用的那个色</b>：候选栏与表情面板都用 {@code barBg}，
 *       这样圆角切掉的四个小三角露出来的是同色，不会出现"和上面工具栏之间一道缝"；</li>
 *   <li>类名来自 {@link Targets} 或已核实的公开类名，找不到就跳过并记日志；</li>
 *   <li>全程 try/catch，失败绝不打断搜狗。</li>
 * </ul>
 *
 * <p>接管时机不止构造器：候选栏在搜狗里会被反复 {@code setCandidatesView()} 装上/换下，
 * 所以额外挂根容器的 {@code setCandidatesView(View)}，参数本身就是那个候选栏 View —— 这是
 * beta1「候选栏没被画」的直接原因（构造器只跑一次，而且可能早于我们装 hook）。
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
            "com.sohu.inputmethod.sogou.PopupPreview",
    };

    private static final String ID = "H80_panel_ios";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 三个面板的圆角（dp）：气泡最小，面板大一号。 */
    private static final float R_PANEL_DP = 14f;
    private static final float R_POPUP_DP = 12f;

    private static volatile int sCandidateTakes;
    private static volatile int sEmojiTakes;
    private static volatile int sPopupTakes;
    private static volatile int sMissing;
    private static volatile int sClipOn;
    /** 留平的上 / 下边缘计数 —— 用来确认"接缝一侧确实没有倒角"。 */
    private static volatile int sSquareTop;
    private static volatile int sSquareBottom;
    private static volatile String sLastCandidateName = "-";

    private PanelIosHook() {
    }

    public static void install(final ClassLoader cl, Targets targets) {
        if (cl == null) return;
        HookRegistry.install(ID, cl, new HookRegistry.Installer() {
            @Override
            public void install(ClassLoader classLoader) {
                hookCtorAll(classLoader, CANDIDATE_CONTAINERS, "candidate");
                hookCtorAll(classLoader, EMOJI_CONTAINERS, "emoji");
                hookCtorAll(classLoader, POPUP_VIEWS, "popup");
                // 候选栏真正的接管点：根容器 setCandidatesView(View)
                hookSetCandidates(classLoader, Targets.forPackage(pkgOf()));
                HookRegistry.log("H80 panel-ios installed: candidate=" + CANDIDATE_CONTAINERS.length
                        + " emoji=" + EMOJI_CONTAINERS.length + " popup=" + POPUP_VIEWS.length);
            }
        });
    }

    private static String pkgOf() {
        try {
            Context c = com.chiron.iosemoji.core.HookState.context();
            return c == null ? null : c.getPackageName();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 挂根容器的 {@code setCandidatesView(View)}：每次搜狗装上/换下候选栏都会经过这里。
     *
     * <p>这是 beta1「候选栏完全没变化」的根因修复 —— 之前在候选栏自己的构造器里上色，
     * 而那个对象可能在我们装 hook 之前就建好了（也可能被换成另一个实例）。
     */
    private static void hookSetCandidates(ClassLoader cl, Targets targets) {
        if (targets == null || targets.rootContainerClass == null) return;
        try {
            XposedHelpers.findAndHookMethod(targets.rootContainerClass, cl, "setCandidatesView",
                    View.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object a0 = param.args == null || param.args.length == 0
                                        ? null : param.args[0];
                                if (!(a0 instanceof View)) return;
                                sLastCandidateName = a0.getClass().getName();
                                if (!ModuleConfig.candidateBarIosEnabled()) return;
                                final View v = (View) a0;
                                MAIN.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        paint(v, "candidate");
                                    }
                                });
                            } catch (Throwable t) {
                                HookRegistry.log("H80 setCandidatesView callback failed: " + t);
                            }
                        }
                    });
            HookRegistry.log("H80 hooked " + targets.rootContainerClass + "#setCandidatesView");
        } catch (Throwable t) {
            HookRegistry.log("H80 setCandidatesView not found on " + targets.rootContainerClass
                    + ": " + t);
        }
    }

    private static void hookCtorAll(ClassLoader cl, String[] classNames, final String kind) {
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

    /**
     * 上色：<b>按拼接方向只倒外侧两个角</b> + 把子 View 一起裁进这个形状（{@code clipToOutline}）。
     *
     * <p>为什么要 clip：搜狗这些面板里，真正的「方框」往往是我们设的那层背景<b>下面的子 View</b>
     * 自己画的，只设背景就会被它盖住一角 —— 真机反馈「长按气泡背景还有个方框没贴合」就是这个。
     *
     * <p>v2.4.0-beta3 的关键修正（真机反馈「工具栏和面板的衔接处圆角被切成两半」）：
     * 候选栏和表情面板是<b>上下叠在一起的一块面</b>（候选栏在上、表情面板在下，色值都是 {@code barBg}），
     * 如果两块都把四个角倒圆，接缝处就是<b>两条相反的弧线</b>，中间露出窗口底色，看起来像两张卡片拼起来。
     * 真实的 iOS 键盘是一整块：只有最上沿和最下沿是圆的。
     * 所以这里改成按槽位只倒外侧角：
     * <ul>
     *   <li>候选栏（在上面）→ 只倒<b>上面两个角</b>，下边缘留平，与下方同色的面板严丝合缝；</li>
     *   <li>表情面板（在下面）→ 只倒<b>下面两个角</b>，上边缘留平；</li>
     *   <li>长按气泡是浮动卡片 → 四个角都倒，并且保留发丝描边。</li>
     * </ul>
     * 接缝一侧同时<b>不画描边</b>（{@code GradientDrawable} 的 stroke 只能描一整圈，
     * 留着就会在接缝上画出一条横线，那正是「被分割成两个部分」的另一半原因）。
     */
    private static void paint(View v, String kind) {
        try {
            if (v == null || v.getContext() == null) return;
            if (!enabledFor(kind)) return;
            Context ctx = v.getContext();
            SkinPreset p = SkinPreset.of(ModuleConfig.skinPreset(), IosSkin.isDark(null, ctx), ctx);
            if (p == null) return;
            int color;
            float rDp;
            boolean roundTop;
            boolean roundBottom;
            boolean stroke;
            String cornerNote;
            if ("candidate".equals(kind)) {
                color = p.barBg;
                rDp = R_PANEL_DP;
                // 候选栏与下方的表情面板是同一块面：接缝那一侧（下沿）必须留平，
                // 否则两块各画两道相反的弧，中间露出窗口底色 = "被分割成两个部分"。
                // 只保留最上沿的圆角（iOS 键盘最外侧的那道弧）。
                roundTop = true;
                roundBottom = false;
                stroke = false;
                cornerNote = "top-only";
                sCandidateTakes++;
            } else if ("emoji".equals(kind)) {
                // 用 barBg 而不是 kbBg：表情面板正上方是候选栏/工具条，两者同色 → 接缝不可见
                color = p.barBg;
                rDp = R_PANEL_DP;
                roundTop = false;
                roundBottom = true;
                stroke = false;
                cornerNote = "bottom-only";
                sEmojiTakes++;
            } else {
                color = p.popup;
                rDp = R_POPUP_DP;
                roundTop = true;
                roundBottom = true;
                stroke = true;
                cornerNote = "all";
                sPopupTakes++;
            }
            if (!roundTop) sSquareTop++;
            if (!roundBottom) sSquareBottom++;
            float r = rDp * ctx.getResources().getDisplayMetrics().density;
            float z = 0f;
            // setCornerRadii 顺序：左上、右上、右下、左下（每个角 x/y 两个 float）
            float[] radii = roundTop
                    ? (roundBottom ? new float[]{r, r, r, r, r, r, r, r}
                    : new float[]{r, r, r, r, z, z, z, z})
                    : (roundBottom ? new float[]{z, z, z, z, r, r, r, r}
                    : new float[]{z, z, z, z, z, z, z, z});
            GradientDrawable d = new GradientDrawable();
            d.setColor(color);
            d.setCornerRadii(radii);
            if (stroke) {
                d.setStroke(1, alphaOf(p.text, 0.12f));
            }
            v.setBackground(d);
            try {
                v.setClipToOutline(true);
                sClipOn++;
            } catch (Throwable ignored) {
                // 极老系统没有这个 API，忽略
            }
            HookRegistry.log("H80 " + kind + " bg -> #" + Integer.toHexString(color)
                    + " r=" + Math.round(rDp) + "dp corners=" + cornerNote + " clip=on");
        } catch (Throwable t) {
            HookRegistry.log("H80 paint " + kind + " failed: " + t);
        }
    }

    /** 供 H02（键盘被装上）调用：键盘每次弹出顺手把候选栏也对齐一遍。 */
    public static void refreshCandidates(Object rootContainer) {
        try {
            if (rootContainer == null) return;
            if (!ModuleConfig.candidateBarIosEnabled()) return;
            final Object cand = XposedHelpers.callMethod(rootContainer, "getCandiatesView");
            if (!(cand instanceof View)) return;
            sLastCandidateName = cand.getClass().getName();
            final View v = (View) cand;
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    paint(v, "candidate");
                }
            });
        } catch (Throwable t) {
            HookRegistry.log("H80 refreshCandidates failed: " + t);
        }
    }

    private static int alphaOf(int rgb, float a) {
        int alpha = Math.max(0, Math.min(255, Math.round(255 * a)));
        return (alpha << 24) | (rgb & 0x00FFFFFF);
    }

    /**
     * 状态卡：候选/表情/气泡/未找到/被裁数/留平上沿/留平下沿/最后一个候选栏类名。
     *
     * <p>「留平上沿 / 留平下沿」是 v2.4.0-beta3 新增的两段：接缝那一侧应当<b>不倒角</b>，
     * 这两段只要大于 0，就说明拼接逻辑真的跑到了。
     */
    public static String state() {
        return sCandidateTakes + "/" + sEmojiTakes + "/" + sPopupTakes + "/" + sMissing
                + "/" + sClipOn + "/" + sSquareTop + "/" + sSquareBottom
                + "/" + sLastCandidateName;
    }

    public static String summary() {
        return "panel(cand=" + sCandidateTakes + ",emoji=" + sEmojiTakes
                + ",popup=" + sPopupTakes + ",miss=" + sMissing + ",clip=" + sClipOn
                + ",flatT=" + sSquareTop + ",flatB=" + sSquareBottom + ")";
    }
}