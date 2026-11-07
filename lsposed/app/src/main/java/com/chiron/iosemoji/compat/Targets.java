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

    /** 是否使用小米那套「输入/提交层」类（input.i / main.manager.b0 / MainImeServiceDel）。 */
    public final boolean xiaomiInputLayer;

    private Targets(String pkg, String label,
                    String themeIniClass, String themeIniMethod,
                    String themeParserClass, String themeParserMethod,
                    String bitmapClass, String bitmapMethod,
                    String rootContainerClass,
                    String keyboardFacadeClass,
                    String candidateViewClass, String candidateDelegateClass,
                    String expressionUtilClass, String expressionUtilAssetMethod,
                    String pagerClass, String emojiViewClass,
                    boolean xiaomiInputLayer) {
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
        this.xiaomiInputLayer = xiaomiInputLayer;
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
            "com.sogou.expressionplugin.emoji.EmojiKeyboardView",
            true);

    /**
     * OPPO 定制版 10.32.0622.2607201151。
     *
     * <p>该版把主题/皮肤模块整体 repackage 进<b>默认包</b>：ini 入口 = {@code yy1#a(String)}（返回类型仍是
     * {@code com.sogou.theme.parse.entity.b}），位图入口 = {@code nm1#j(Context,String,boolean,boolean)}。
     * <p>注意：jadx 会把默认包类渲染成 {@code defpackage.yy1}，那是<b>伪包名</b>；DEX 描述符是 {@code Lyy1;}，
     * 运行时 FQCN 是 {@code yy1}（无包名）。写成 {@code defpackage.yy1} 会 ClassNotFound。
     * 表情工具类仍是同名包，只是类名从 {@code ExpressionUtil} 变成 {@code w}、方法 {@code u} 变成 {@code x}。
     * <p>未定位（填 null，对应功能跳过）：键盘外壳 s0 实现、候选视图 r2、b0/InputLogic/presenter.a 等。
     */
    public static final Targets OPPO = new Targets(
            "com.sohu.inputmethod.sogouoem", "OPPO 定制版",
            "yy1", "a",
            null, null,
            "nm1", "j",
            "com.sohu.inputmethod.ui.frame.NormalIMERootContainer",
            null,
            null,
            null,
            "com.sogou.expressionplugin.expression.w", "x",
            "com.sogou.expressionplugin.emoji.NoScrollViewPager",
            "com.sogou.expressionplugin.emoji.EmojiKeyboardView",
            false);

    /**
     * 搜狗官方版 20.19.0（{@code com.sohu.inputmethod.sogou}，versionCode 2640）。
     *
     * <p>架构同源、包名整体搬家：类名大多保留，只是从 {@code com.sohu.inputmethod.*} / {@code com.sogou.lib.*}
     * 搬到 {@code com.sogou.bu.*} / {@code com.sogou.imskit.*}。逐项取证见 docs/CHANNEL_STANDARD_200190.md。
     * <ul>
     *   <li>ini 入口与小米<b>同名同签名</b>：{@code com.sogou.theme.parse.utils.b#a(String)}</li>
     *   <li>位图入口从 {@code common.j#f(Ctx,Str,Z,Z)} 变成 {@code common.g#f(Ctx,Str,Z)} / {@code #g(Ctx,Str,Z,Z)}</li>
     *   <li>根容器从 {@code NormalIMERootContainer} 变成 {@code com.sogou.bu.basic.SogouInputArea}</li>
     *   <li>候选视图 / 候选代理：未定位（{@code showCandidateWordView} 全 APK 0 命中）→ null</li>
     * </ul>
     * <p>⚠️ 尚未真机验证：皮肤入口「类存在」不等于「真被调用」，需看日志 {@code H40 … patched: N} / {@code H41 … hooked: M/6}。
     */
    public static final Targets STANDARD = new Targets(
            "com.sohu.inputmethod.sogou", "搜狗官方版",
            "com.sogou.theme.parse.utils.b", "a",
            "com.sogou.theme.parse.utils.c", "n",
            "com.sogou.theme.common.g", "f",
            "com.sogou.bu.basic.SogouInputArea",
            "com.sogou.bu.ui.keyboard.controller.b",
            null,
            null,
            "com.sogou.expressionplugin.expression.ExpressionUtil", "u",
            "com.sogou.expressionplugin.emoji.NoScrollViewPager",
            "com.sogou.expressionplugin.emoji.EmojiKeyboardView",
            false);

    /**
     * vivo 定制版 11.41.14023.2609221718（{@code com.sohu.inputmethod.sogou.vivo}，versionCode 6034）。
     *
     * <p>与官方版同源（IME 链同样是 {@code com.sogou.bu.ims.support.BaseInputMethodService}），
     * 但<b>主题/皮肤模块被整体 repackage 进默认包</b>：{@code theme.parse.utils.b}、{@code utils.c}、
     * {@code theme.common.g/j}、{@code parse.entity.b}、{@code parse.impl.a/b} <b>全部缺失</b>。
     * 位图入口的强候选是 {@code mc4#f(Context,String,Z)} / {@code mc4#g(Context,String,Z,Z)}
     * （签名与官方 {@code common.g} 同形）—— <b>未运行验证，故皮肤层暂不启用</b>。
     * <ul>
     *   <li>已确认存在：{@code com.sogou.bu.basic.SogouInputArea}、{@code com.sogou.bu.ui.keyboard.KeyboardRootComponentView}、
     *       {@code main.view.IMEInputCandidateViewContainer}、{@code expression.ExpressionUtil}、
     *       {@code NoScrollViewPager}、{@code EmojiKeyboardView}</li>
     *   <li>未定位：键盘外壳 facade（没有 {@code controller.b}）、候选视图 {@code r2} 等价物</li>
     * </ul>
     */
    public static final Targets VIVO = new Targets(
            "com.sohu.inputmethod.sogou.vivo", "vivo 定制版",
            null, null,                       // 皮肤 ini 入口未定位（类被重打包）
            null, null,
            null, null,                       // 位图入口候选 mc4 未验证 ⇒ 皮肤层不启用
            "com.sogou.bu.basic.SogouInputArea",
            null,
            null,
            null,
            "com.sogou.expressionplugin.expression.ExpressionUtil", "u",
            "com.sogou.expressionplugin.emoji.NoScrollViewPager",
            "com.sogou.expressionplugin.emoji.EmojiKeyboardView",
            false);

    public static final Targets[] ALL = {XIAOMI, STANDARD, OPPO, VIVO};

    /**
     * 按包名取渠道档案。
     *
     * <p><b>未知包名返回 null</b>（不是回落小米）：手册要求「未识别的渠道默认不修改原始行为」，
     * 拿小米类名去别的渠道里找类只会制造无意义的 ClassNotFound 与错误日志。
     * 调用方必须处理 null（打印一行日志后什么都不装）。
     */
    public static Targets forPackage(String packageName) {
        if (packageName != null) {
            for (Targets t : ALL) {
                if (t.pkg.equals(packageName)) return t;
            }
        }
        return null;
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
