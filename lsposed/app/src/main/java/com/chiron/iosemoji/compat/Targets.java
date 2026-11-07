package com.chiron.iosemoji.compat;

/**
 * 渠道档案：每个搜狗定制版各自的「类名/方法名映射表」。
 *
 * <p>为什么需要它：搜狗各个定制版是<b>各自混淆</b>的，同一个功能在不同渠道里类名完全不同
 * （小米版皮肤在 {@code com.sogou.theme.*}，OPPO/vivo 被 {@code -repackageclasses ''} 塞进默认包，
 * 官方版整体搬到 {@code com.sogou.bu.*} / {@code com.sogou.imskit.*}）。
 *
 * <p><b>原则：没有核对过的名字一律 {@code null}，对应功能直接跳过。</b>
 * 用猜的名字去 hook 只会得到 ClassNotFoundError，被 catch 吞掉后表现为「静默失效」，
 * 比不装更难查（OPPO 之前就是这样白等了一个版本）。
 *
 * <p>已核对渠道：
 * <ul>
 *   <li>小米定制版 {@code com.sohu.inputmethod.sogou.xiaomi} 10.32.21.202607061714</li>
 *   <li>OPPO 定制版 {@code com.sohu.inputmethod.sogouoem} 10.32.0622.2607201151</li>
 *   <li>搜狗官方版 {@code com.sohu.inputmethod.sogou} 20.19.0</li>
 *   <li>vivo 定制版 {@code com.sohu.inputmethod.sogou.vivo} 11.41.14023.2609221718</li>
 * </ul>
 */
public final class Targets {

    /**
     * 皮肤：ini 统一解析入口。
     *
     * <p>返回形态两种：小米/官方返回 {@code com.sogou.theme.parse.entity.b}（用 {@code b()} 取 ArrayMap），
     * vivo 直接返回 {@code androidx.collection.ArrayMap}。{@code ThemeSkinHook} 两种都吃。
     */
    public final String themeIniClass;
    /** ini 入口的方法名（不同渠道不止一个重载：vivo 是 {@code d}/{@code e}/{@code f}）。 */
    public final String[] themeIniMethods;
    /** 皮肤：ini 行解析器（仅用于诊断计数，可为 null）。 */
    public final String themeParserClass;
    public final String themeParserMethod;

    /**
     * 皮肤：位图解码入口的<b>候选类</b>列表（逐个尝试，命中即用）。
     *
     * <p>各渠道解码路径不同：小米 {@code com.sogou.theme.common.j}、官方 {@code com.sogou.theme.common.g}、
     * OPPO {@code nm1}、vivo 走通用图片工具（{@code com.sogou.lib.image.utils.a} 等）。
     * 多挂几个无害——{@code ThemeSkinHook} 会用「路径 + 图名」白名单过滤，非皮肤图一律原样返回。
     */
    public final String[] bitmapClasses;
    /** 位图取值器的方法名（按名挂全部重载，因为各版参数个数不同）。 */
    public final String[] bitmapMethods;

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
                    String themeIniClass, String[] themeIniMethods,
                    String themeParserClass, String themeParserMethod,
                    String[] bitmapClasses, String[] bitmapMethods,
                    String rootContainerClass,
                    String keyboardFacadeClass,
                    String candidateViewClass, String candidateDelegateClass,
                    String expressionUtilClass, String expressionUtilAssetMethod,
                    String pagerClass, String emojiViewClass,
                    boolean xiaomiInputLayer) {
        this.pkg = pkg;
        this.label = label;
        this.themeIniClass = themeIniClass;
        this.themeIniMethods = themeIniMethods == null ? new String[0] : themeIniMethods;
        this.themeParserClass = themeParserClass;
        this.themeParserMethod = themeParserMethod;
        this.bitmapClasses = bitmapClasses == null ? new String[0] : bitmapClasses;
        this.bitmapMethods = bitmapMethods == null ? new String[0] : bitmapMethods;
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

    /** 小米定制版（原适配目标，已验证）。 */
    public static final Targets XIAOMI = new Targets(
            "com.sohu.inputmethod.sogou.xiaomi", "小米定制版",
            "com.sogou.theme.parse.utils.b", new String[]{"a"},
            "com.sogou.theme.parse.utils.c", "n",
            new String[]{"com.sogou.theme.common.j"},
            new String[]{"f", "g", "j", "k", "d", "e"},
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
     * {@code com.sogou.theme.parse.entity.b}），位图入口 = {@code nm1}（{@code j/k/e/f}）。
     * <p>注意：jadx 会把默认包类渲染成 {@code defpackage.yy1}，那是<b>伪包名</b>；DEX 描述符是 {@code Lyy1;}，
     * 运行时 FQCN 是 {@code yy1}（无包名）。写成 {@code defpackage.yy1} 会 ClassNotFound。
     * 表情工具类仍是同名包，只是类名从 {@code ExpressionUtil} 变成 {@code w}、方法 {@code u} 变成 {@code x}。
     * <p>未定位（填 null，对应功能跳过）：键盘外壳 s0 实现、候选视图 r2、b0/InputLogic/presenter.a 等。
     */
    public static final Targets OPPO = new Targets(
            "com.sohu.inputmethod.sogouoem", "OPPO 定制版",
            "yy1", new String[]{"a"},
            null, null,
            new String[]{"nm1"},
            new String[]{"j", "k", "e", "f", "g", "d"},
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
     */
    public static final Targets STANDARD = new Targets(
            "com.sohu.inputmethod.sogou", "搜狗官方版",
            "com.sogou.theme.parse.utils.b", new String[]{"a"},
            "com.sogou.theme.parse.utils.c", "n",
            new String[]{"com.sogou.theme.common.g", "com.sogou.theme.common.j"},
            new String[]{"f", "g", "j", "k", "d", "e"},
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
     *
     * <p>已用 dexdump 反查定位（依据：谁引用了 {@code assets/theme/file_name_mapping_1080.ini} /
     * {@code py_9.ini} / {@code phoneSkin.ini} 这些字符串常量，谁的签名是「返回 ArrayMap、入参含 String」）：
     * <ul>
     *   <li>ini 入口 = <b>{@code gh}</b>：{@code d(Context,String,cw0):ArrayMap}（资产分支）、
     *       {@code e(InputStream,String,cw0):ArrayMap}、{@code f(String,cw0):ArrayMap}（文件分支）
     *       —— 与小米 {@code parse.utils.b#a(String)} 同角色，且直接返回 ArrayMap</li>
     *   <li>行解析器 = {@code op2#j(BufferedReader):ArrayMap}（诊断用）</li>
     *   <li>位图入口：未定位到单一出口（{@code theme.common.*} 整类缺失）⇒ 挂通用图片工具的候选，
     *       靠「图名白名单」过滤，命中则生效、不命中则原样返回（不会改坏）</li>
     * </ul>
     * <p>已确认存在：{@code com.sogou.bu.basic.SogouInputArea}、{@code KeyboardRootComponentView}、
     * {@code main.view.IMEInputCandidateViewContainer}、{@code expression.ExpressionUtil}、
     * {@code NoScrollViewPager}、{@code EmojiKeyboardView}；
     * 未定位：键盘外壳 facade（没有 {@code controller.b}）、候选视图 {@code r2} 等价物。
     */
    public static final Targets VIVO = new Targets(
            "com.sohu.inputmethod.sogou.vivo", "vivo 定制版",
            "gh", new String[]{"d", "e", "f"},
            "op2", "j",
            new String[]{
                    "com.sogou.lib.image.utils.a",
                    "com.sogou.lib.common.picture.bitmap.b",
                    "c70"
            },
            new String[]{"e", "f", "o", "y", "b"},
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

    /** ini 层（颜色/文字/候选栏底色）是否可改。 */
    public boolean supportsIniSkin() {
        return notEmpty(themeIniClass) && themeIniMethods.length > 0;
    }

    /** 位图层（键帽/键盘底色）是否可改。 */
    public boolean supportsBitmapSkin() {
        return bitmapClasses.length > 0 && bitmapMethods.length > 0;
    }

    /** 皮肤改造（当前主线功能）是否可装：ini 与位图任一层可用即可。 */
    public boolean supportsSkin() {
        return supportsIniSkin() || supportsBitmapSkin();
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
                + " skin=" + (supportsIniSkin() ? "ini" : "-") + (supportsBitmapSkin() ? "+img" : "")
                + " kb=" + (supportsKeyboardReplace() ? "ok" : "skip")
                + " cand=" + (supportsCandidateProbe() ? "ok" : "skip")
                + " emojiFilter=" + (supportsEmojiGroupFilter() ? "ok" : "skip")
                + " emojiSwipe=" + (supportsEmojiSwipe() ? "ok" : "skip");
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }
}
