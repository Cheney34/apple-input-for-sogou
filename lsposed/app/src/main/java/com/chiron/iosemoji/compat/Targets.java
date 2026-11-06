package com.chiron.iosemoji.compat;

/**
 * 「同一套逻辑，不同定制版不同类名」的映射表。
 *
 * <p>背景：各手机厂商的搜狗定制版虽然功能一致，但混淆结果不同。
 * 例：小米版把主题模块放在 {@code com.sogou.theme.*}，OPPO 定制版用
 * {@code -repackageclasses ''} 把同一批类塞进默认包 {@code defpackage}，
 * 于是 {@code com.sogou.theme.parse.utils.b} 变成了 {@code defpackage.yy1}。
 *
 * <p>本类的每一个字段都必须是**在对应 APK 的 DEX 里核对过**的名字；
 * 没核对到的填 {@code null}，对应功能直接跳过并在日志里说明，
 * 绝不用猜的名字去 hook（猜错会静默失效，比没有更糟）。
 *
 * <p>已核对的版本：
 * <ul>
 *   <li>小米定制版 {@code com.sohu.inputmethod.sogou.xiaomi} 10.32.21.202607061714</li>
 *   <li>OPPO 定制版 {@code com.sohu.inputmethod.sogouoem} 10.32.0622.2607201151</li>
 * </ul>
 */
public final class Targets {

    /** 皮肤：ini 统一解析入口（返回 parse.entity.b，内含 ArrayMap&lt;段, ArrayMap&lt;键,值&gt;&gt;）。 */
    public final String themeIniClass;
    public final String themeIniMethod;
    /** 皮肤：ini 行解析器（仅用于诊断计数，可为 null）。 */
    public final String themeParserClass;
    public final String themeParserMethod;
    /** 皮肤：位图统一解码入口（Bitmap）。 */
    public final String bitmapClass;
    public final String bitmapMethod;

    /** 输入视图根容器（setKeyboardView / setCandidatesView / getKeyboardView）。 */
    public final String rootContainerClass;
    /** 键盘外壳（s0 实现，getRootView()）——只有小米版定位到。 */
    public final String keyboardFacadeClass;
    /** 候选视图（r2：Z7/N7）——只有小米版定位到。 */
    public final String candidateViewClass;
    public final String candidateDelegateClass;

    /** 表情工具类（内置表情包解压 / 分组枚举）。 */
    public final String expressionUtilClass;
    public final String expressionUtilAssetMethod;

    /** 表情翻页（左右滑）相关的两个类。 */
    public final String pagerClass;
    public final String emojiViewClass;

    public final String pkg;
    public final String label;

    private Targets(String pkg, String label,
                    String themeIniClass, String themeIniMethod,
                    String themeParserClass, String themeParserMethod,
                    String bitmapClass, String bitmapMethod,
                    String rootContainerClass,
                    String keyboardFacadeClass,
                    String candidateViewClass, String candidateDelegateClass,
                    String expressionUtilClass, String expressionUtilAssetMethod,
                    String pagerClass, String emojiViewClass) {
        this.pkg = pkg;
        this.label = label;
        this.themeIniClass = themeIniClass;
        this.themeIniMethod = themeIniMethod;
        this.themeParserClass = themeParserClass;
        this.themeParserMethod = themeParserMethod;
        this.bitmapClass = bitmapClass;
        this.bitmapMethod = bitmapMethod;
        this.rootContainerClass = rootContainerClass;
        this.keyboardFacadeClass = keyboardFacadeClass;
        this.candidateViewClass = candidateViewClass;
        this.candidateDelegateClass = candidateDelegateClass;
        this.expressionUtilClass = expressionUtilClass;
        this.expressionUtilAssetMethod = expressionUtilAssetMethod;
        this.pagerClass = pagerClass;
        this.emojiViewClass = emojiViewClass;
    }

    /** 小米定制版（原适配目标）。 */
    public static final Targets XIAOMI = new Targets(
            "com.sohu.inputmethod.sogou.xiaomi", "小米定制版",
            "com.sogou.theme.parse.utils.b", "a",
            "com.sogou.theme.parse.utils.c", "n",
            "com.sogou.theme.common.j", "f",
            "com.sohu.inputmethod.ui.frame.NormalIMERootContainer",
            "com.sogou.lib.bu.ui.component.keyboard.d",
            "com.sohu.inputmethod.sogou.r2",
            "com.sohu.inputmethod.sogou.AbstractImeServiceDelegate",
            "com.sogou.expressionplugin.expression.ExpressionUtil", "u",
            "com.sogou.expressionplugin.emoji.NoScrollViewPager",
            "com.sogou.expressionplugin.emoji.EmojiKeyboardView");

    /**
     * OPPO 定制版 10.32.0622.2607201151。
     *
     * <p>该版把主题/皮肤模块整体 repackage 进默认包：ini 入口 = {@code defpackage.yy1#a(String)}，
     * 位图入口 = {@code defpackage.nm1#j(Context,String,boolean,boolean)}（单例取法也从 {@code e()} 变成 {@code f()}）。
     * 表情工具类仍是同名包，只是类名从 {@code ExpressionUtil} 变成 {@code w}、方法 {@code u} 变成 {@code x}。
     * <p>未定位（填 null，对应功能跳过）：键盘外壳 s0 实现、候选视图 r2、b0/InputLogic/presenter.a 等。
     */
    public static final Targets OPPO = new Targets(
            "com.sohu.inputmethod.sogouoem", "OPPO 定制版",
            "defpackage.yy1", "a",
            null, null,
            "defpackage.nm1", "j",
            "com.sohu.inputmethod.ui.frame.NormalIMERootContainer",
            null,
            null,
            null,
            "com.sogou.expressionplugin.expression.w", "x",
            "com.sogou.expressionplugin.emoji.NoScrollViewPager",
            "com.sogou.expressionplugin.emoji.EmojiKeyboardView");

    public static final Targets[] ALL = {XIAOMI, OPPO};

    /** 没匹配上的包名给一份「只有小米名」的兜底，保证老行为不变。 */
    public static Targets forPackage(String packageName) {
        if (packageName != null) {
            for (Targets t : ALL) {
                if (t.pkg.equals(packageName)) return t;
            }
        }
        return XIAOMI;
    }

    /** 皮肤改造（当前主线功能）是否可装。 */
    public boolean supportsSkin() {
        return notEmpty(themeIniClass) && notEmpty(themeIniMethod)
                && notEmpty(bitmapClass) && notEmpty(bitmapMethod);
    }

    /** 自绘键盘（实验功能）是否可装。 */
    public boolean supportsKeyboardReplace() {
        return notEmpty(keyboardFacadeClass) && notEmpty(rootContainerClass);
    }

    /** 候选探针（只读诊断）是否可装。 */
    public boolean supportsCandidateProbe() {
        return notEmpty(candidateViewClass);
    }

    /** 内置表情分组屏蔽是否可装。 */
    public boolean supportsEmojiGroupFilter() {
        return notEmpty(expressionUtilClass) && notEmpty(expressionUtilAssetMethod);
    }

    /** 表情左右滑是否可装。 */
    public boolean supportsEmojiSwipe() {
        return notEmpty(pagerClass) && notEmpty(emojiViewClass);
    }

    public String describe() {
        return label + "(" + pkg + ")"
                + " skin=" + (supportsSkin() ? "ok" : "skip")
                + " kb=" + (supportsKeyboardReplace() ? "ok" : "skip")
                + " cand=" + (supportsCandidateProbe() ? "ok" : "skip")
                + " emojiFilter=" + (supportsEmojiGroupFilter() ? "ok" : "skip")
                + " emojiSwipe=" + (supportsEmojiSwipe() ? "ok" : "skip");
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }
}
