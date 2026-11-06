package com.chiron.iosemoji.keyboard;

/**
 * iOS 键盘配色 / 度量。
 *
 * <p>取色参照 iOS 系统键盘（浅色 #D1D4D9 底 + 纯白键帽，深色 #28282B 底 + #636366 键帽）。
 * 这不是搜狗主题里的颜色 —— 搜狗主题 {@code cands.ini} 用的是 0xFFF5F5F5 / 0xFFC8E0E8，
 * 那是它自己的安卓风格皮肤，我们要的是 iOS 观感，所以自绘。
 *
 * <p>尺寸全部以「键盘宽度」为基准的比例表达，和搜狗主题 {@code py_9.ini} 的做法一致
 * （{@code W=1 H=0.591667}），这样在任何屏幕上键盘高度都与原键盘对齐，
 * 不会因为改高度而让输入法窗口/insets 跳动。
 */
final class KeyboardTheme {

    /** 键盘总高 / 键盘宽 —— 与搜狗主题 py_9.ini 的 H 值一致。 */
    static final float HEIGHT_RATIO = 0.591667f;

    /** 单键圆角 / 键盘宽度（≈5dp @ 360dp 宽）。 */
    static final float CORNER_RATIO = 0.0139f;
    /** 键间距 / 键盘宽度（≈6dp）。 */
    static final float GAP_RATIO = 0.01667f;
    /** 左右留白 / 键盘宽度（≈3dp）。 */
    static final float SIDE_RATIO = 0.00833f;
    /** 行间距 / 键盘宽度（≈11dp）。 */
    static final float ROW_GAP_RATIO = 0.03056f;
    /** 上下留白 / 键盘宽度（≈6dp）。 */
    static final float VERT_PAD_RATIO = 0.01667f;
    /** 字母字号 / 键盘宽度（≈23dp）。 */
    static final float LETTER_TEXT_RATIO = 0.0639f;
    /** 功能键字号 / 键盘宽度（≈16dp）。 */
    static final float FUNC_TEXT_RATIO = 0.0444f;

    final boolean dark;
    final int keyboardBg;
    final int keyBg;
    final int keyPressed;
    final int funcBg;
    final int funcPressed;
    final int textColor;
    final int funcTextColor;
    final int shadowColor;

    private KeyboardTheme(boolean dark, int keyboardBg, int keyBg, int keyPressed,
                          int funcBg, int funcPressed, int textColor, int funcTextColor, int shadowColor) {
        this.dark = dark;
        this.keyboardBg = keyboardBg;
        this.keyBg = keyBg;
        this.keyPressed = keyPressed;
        this.funcBg = funcBg;
        this.funcPressed = funcPressed;
        this.textColor = textColor;
        this.funcTextColor = funcTextColor;
        this.shadowColor = shadowColor;
    }

    static KeyboardTheme of(boolean dark) {
        if (dark) {
            return new KeyboardTheme(true,
                    0xFF28282B,  // 键盘底
                    0xFF636366,  // 字母键
                    0xFF7C7C80,  // 按下
                    0xFF4A4A4D,  // 功能键
                    0xFF5E5E62,  // 功能键按下
                    0xFFFFFFFF,  // 键帽文字
                    0xFFFFFFFF,  // 功能键文字
                    0x66000000); // 键帽投影
        }
        return new KeyboardTheme(false,
                0xFFD1D4D9,
                0xFFFFFFFF,
                0xFFDCDDE1,
                0xFFADB3BC,
                0xFF9DA4AE,
                0xFF000000,
                0xFFFFFFFF,
                0x33000000);
    }
}
