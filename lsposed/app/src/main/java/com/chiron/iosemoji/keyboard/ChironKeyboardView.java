package com.chiron.iosemoji.keyboard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;

import com.chiron.iosemoji.hook.HookRegistry;
import com.chiron.iosemoji.hook.TextInjector;

/**
 * iOS 风格键盘视图（显示层替换的核心）。
 *
 * <p><b>只做三件事</b>：把键位摆出来、把触摸翻译成「按键」、把按键交给
 * {@link TextInjector}。它**不碰**输入引擎、候选、词库 —— 拼音/联想/上屏仍然是搜狗自己做的
 * （这正是本项目「搜狗负责输入，Chiron 负责体验」的边界）。
 *
 * <p>尺寸基准与搜狗主题 {@code py_9.ini} 对齐（{@code H = 0.591667 × 宽}），
 * 所以换掉键盘后输入法窗口高度、候选栏位置都不用改。
 */
public final class ChironKeyboardView extends ViewGroup {

    private static final String TAG = "ChironIOSInput";

    private static final int PAGE_LETTER = 0;
    private static final int PAGE_SYMBOL = 1;

    private static final int REPEAT_DELAY_MS = 420;
    private static final int REPEAT_INTERVAL_MS = 65;

    /** 一个键的定义。 */
    private static final class Spec {
        final String label;
        final int type;
        final float weight;
        final char ch;

        Spec(String label, int type, float weight, char ch) {
            this.label = label;
            this.type = type;
            this.weight = weight;
            this.ch = ch;
        }

        static Spec key(char c) {
            return new Spec(String.valueOf(c), KeyView.TYPE_CHAR, 1f, c);
        }

        static Spec key(char c, float weight) {
            return new Spec(String.valueOf(c), KeyView.TYPE_CHAR, weight, c);
        }

        static Spec fn(String label, int type, float weight) {
            return new Spec(label, type, weight, (char) 0);
        }
    }

    private static final Spec[] ROW_TOP = {
            Spec.key('q'), Spec.key('w'), Spec.key('e'), Spec.key('r'), Spec.key('t'),
            Spec.key('y'), Spec.key('u'), Spec.key('i'), Spec.key('o'), Spec.key('p'),
    };

    private static final Spec[] ROW_MID = {
            Spec.key('a'), Spec.key('s'), Spec.key('d'), Spec.key('f'), Spec.key('g'),
            Spec.key('h'), Spec.key('j'), Spec.key('k'), Spec.key('l'),
    };

    private static final Spec[] ROW_BOTTOM_LETTER = {
            Spec.fn("", KeyView.TYPE_SHIFT, 1.5f),
            Spec.key('z'), Spec.key('x'), Spec.key('c'), Spec.key('v'),
            Spec.key('b'), Spec.key('n'), Spec.key('m'),
            Spec.fn("", KeyView.TYPE_DELETE, 1.5f),
    };

    private static final Spec[] ROW_SYMBOL_TOP = {
            Spec.key('1'), Spec.key('2'), Spec.key('3'), Spec.key('4'), Spec.key('5'),
            Spec.key('6'), Spec.key('7'), Spec.key('8'), Spec.key('9'), Spec.key('0'),
    };

    private static final Spec[] ROW_SYMBOL_MID = {
            Spec.key('-'), Spec.key('/'), Spec.key(':'), Spec.key(';'), Spec.key('('),
            Spec.key(')'), Spec.key('¥'), Spec.key('&'), Spec.key('@'), Spec.key('"'),
    };

    private static final Spec[] ROW_SYMBOL_BOTTOM = {
            Spec.key('，', 1f), Spec.key('。', 1f), Spec.key('、', 1f), Spec.key('？', 1f),
            Spec.key('！', 1f), Spec.key('…', 1f), Spec.fn("", KeyView.TYPE_DELETE, 1.5f),
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable repeatTask = new Runnable() {
        @Override
        public void run() {
            if (repeatKey == null) return;
            TextInjector.backspace();
            handler.postDelayed(this, REPEAT_INTERVAL_MS);
        }
    };

    private final Paint popupPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint popupTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF popupRect = new RectF();
    private final Path popupPath = new Path();

    private KeyboardTheme theme = KeyboardTheme.of(false);
    private int page = PAGE_LETTER;
    private boolean shift;

    private float gap;
    private float side;
    private float rowGap;
    private float rowHeight;
    private float vertPad;
    private float corner;
    private float letterText;
    private float funcText;
    private float rowTopInset;

    private KeyView pressedKey;
    private KeyView popupKey;
    private KeyView repeatKey;

    public ChironKeyboardView(Context context) {
        super(context);
        setWillNotDraw(false);
        setClickable(true);
        popupTextPaint.setTextAlign(Paint.Align.CENTER);
        buildPage();
    }

    // ── 对外 ────────────────────────────────────────────────────────────────

    /** 让键盘跟随系统深色模式（由 Hook 在创建时调用，也可以后续改）。 */
    public void setDarkMode(boolean dark) {
        theme = KeyboardTheme.of(dark);
        buildPage();
        invalidate();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return true;
    }

    // ── 度量 / 布局 ─────────────────────────────────────────────────────────

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (width <= 0) width = getResources().getDisplayMetrics().widthPixels;
        int desiredHeight = Math.round(width * KeyboardTheme.HEIGHT_RATIO);

        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int height;
        if (heightMode == MeasureSpec.EXACTLY) {
            height = MeasureSpec.getSize(heightMeasureSpec);
        } else if (heightMode == MeasureSpec.AT_MOST) {
            height = Math.min(desiredHeight, MeasureSpec.getSize(heightMeasureSpec));
        } else {
            height = desiredHeight;
        }

        setMeasuredDimension(width, height);
        computeMetrics(width, height);
    }

    private void computeMetrics(int width, int height) {
        gap = width * KeyboardTheme.GAP_RATIO;
        side = width * KeyboardTheme.SIDE_RATIO;
        rowGap = width * KeyboardTheme.ROW_GAP_RATIO;
        vertPad = width * KeyboardTheme.VERT_PAD_RATIO;
        corner = width * KeyboardTheme.CORNER_RATIO;
        letterText = width * KeyboardTheme.LETTER_TEXT_RATIO;
        funcText = width * KeyboardTheme.FUNC_TEXT_RATIO;
        float usable = height - 2f * vertPad - 3f * rowGap;
        rowHeight = Math.max(1f, usable / 4f);
        // 第二行（asdf…）比第一行少一个键，按 iOS 惯例整体缩进半个键位
        float topKeyWidth = (width - 2f * side - 9f * gap) / 10f;
        rowTopInset = side + (topKeyWidth + gap) / 2f;
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l;
        int rowCount = 4;
        for (int row = 0; row < rowCount; row++) {
            float top = vertPad + row * (rowHeight + rowGap);
            Spec[] specs = specsForRow(row);
            float startX = row == 1 ? rowTopInset : side;
            float available = width - 2f * startX - gap * (specs.length - 1);
            float totalWeight = 0f;
            for (Spec s : specs) totalWeight += s.weight;
            float x = startX;
            for (int i = 0; i < specs.length; i++) {
                KeyView child = childAt(row, i);
                if (child == null) continue;
                float w = available * (specs[i].weight / totalWeight);
                child.layout(Math.round(x), Math.round(top),
                        Math.round(x + w), Math.round(top + rowHeight));
                x += w + gap;
            }
        }
    }

    private KeyView childAt(int row, int index) {
        int linear = 0;
        for (int r = 0; r < row; r++) linear += specsForRow(r).length;
        int idx = linear + index;
        if (idx < 0 || idx >= getChildCount()) return null;
        View v = getChildAt(idx);
        return v instanceof KeyView ? (KeyView) v : null;
    }

    private Spec[] specsForRow(int row) {
        if (page == PAGE_LETTER) {
            switch (row) {
                case 0: return ROW_TOP;
                case 1: return ROW_MID;
                case 2: return ROW_BOTTOM_LETTER;
                default: return bottomRow();
            }
        }
        switch (row) {
            case 0: return ROW_SYMBOL_TOP;
            case 1: return ROW_SYMBOL_MID;
            case 2: return ROW_SYMBOL_BOTTOM;
            default: return bottomRow();
        }
    }

    private Spec[] bottomRow() {
        return new Spec[]{
                Spec.fn(page == PAGE_LETTER ? "123" : "ABC", KeyView.TYPE_LAYER, 1.5f),
                Spec.fn("", KeyView.TYPE_GLOBE, 1.5f),
                Spec.fn("空格", KeyView.TYPE_SPACE, 5f),
                Spec.fn("", KeyView.TYPE_RETURN, 2f),
        };
    }

    // ── 构建 ────────────────────────────────────────────────────────────────

    private void buildPage() {
        removeAllViews();
        pressedKey = null;
        popupKey = null;
        repeatKey = null;
        for (int row = 0; row < 4; row++) {
            for (Spec spec : specsForRow(row)) {
                KeyView view = new KeyView(getContext());
                view.label = spec.label;
                view.type = spec.type;
                view.funcStyle = spec.type != KeyView.TYPE_CHAR && spec.type != KeyView.TYPE_SPACE;
                view.shiftOn = shift;
                view.apply(theme, corner, letterText, funcText);
                addView(view);
            }
        }
        requestLayout();
        invalidate();
    }

    private void setPage(int newPage) {
        if (page == newPage) return;
        page = newPage;
        buildPage();
    }

    private void toggleShift() {
        shift = !shift;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof KeyView) {
                KeyView kv = (KeyView) child;
                kv.shiftOn = shift;
                kv.invalidate();
            }
        }
    }

    // ── 绘制 ────────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        bgPaint.setColor(theme.keyboardBg);
        canvas.drawRect(0, 0, getWidth(), getHeight(), bgPaint);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        drawPopup(canvas);
    }

    private void drawPopup(Canvas canvas) {
        KeyView key = popupKey;
        if (key == null || key.getWidth() <= 0) return;

        float keyCenterX = key.getLeft() + key.getWidth() / 2f;
        float bubbleW = key.getWidth() * 1.62f;
        float bubbleH = rowHeight * 1.28f;
        float bubbleBottom = key.getTop() + rowHeight * 0.18f;
        float bubbleTop = bubbleBottom - bubbleH;
        if (bubbleTop < 0) {
            bubbleTop = 0;
            bubbleBottom = bubbleH;
        }
        float left = keyCenterX - bubbleW / 2f;
        if (left < 2f) left = 2f;
        if (left + bubbleW > getWidth() - 2f) left = getWidth() - 2f - bubbleW;

        float radius = bubbleW * 0.18f;
        popupRect.set(left, bubbleTop, left + bubbleW, bubbleBottom);

        int bubbleColor = theme.dark ? 0xFF7C7C80 : 0xFFFFFFFF;
        float shadowOffset = bubbleH * 0.045f;
        popupPaint.setColor(theme.shadowColor);
        canvas.drawRoundRect(left + 1f, bubbleTop + shadowOffset,
                left + bubbleW - 1f, bubbleBottom + shadowOffset, radius, radius, popupPaint);
        popupPaint.setColor(bubbleColor);
        canvas.drawRoundRect(popupRect, radius, radius, popupPaint);

        // 小尖角
        float tailW = bubbleW * 0.13f;
        float tailH = Math.min(rowHeight * 0.16f, bubbleH * 0.16f);
        popupPath.reset();
        popupPath.moveTo(keyCenterX - tailW, bubbleBottom - 1f);
        popupPath.lineTo(keyCenterX, bubbleBottom + tailH);
        popupPath.lineTo(keyCenterX + tailW, bubbleBottom - 1f);
        popupPath.close();
        canvas.drawPath(popupPath, popupPaint);

        popupTextPaint.setColor(theme.dark ? 0xFFFFFFFF : 0xFF000000);
        popupTextPaint.setTextSize(bubbleH * 0.58f);
        String text = key.label;
        if (shift && text.length() == 1 && text.charAt(0) >= 'a' && text.charAt(0) <= 'z') {
            text = text.toUpperCase();
        }
        Paint.FontMetrics fm = popupTextPaint.getFontMetrics();
        float baseline = bubbleBottom - bubbleH / 2f - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(text, keyCenterX, baseline, popupTextPaint);
    }

    // ── 触摸 ────────────────────────────────────────────────────────────────

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                KeyView hit = hitTest(event.getX(), event.getY());
                if (hit == null) return false;
                setPressed(hit);
                if (hit.type == KeyView.TYPE_DELETE) {
                    TextInjector.backspace();
                    startRepeat(hit);
                }
                return true;
            case MotionEvent.ACTION_MOVE: {
                KeyView now = hitTest(event.getX(), event.getY());
                if (now != pressedKey) {
                    setPressed(now);
                    if (repeatKey != null && now != repeatKey) stopRepeat();
                    if (now != null && now.type == KeyView.TYPE_DELETE) {
                        TextInjector.backspace();
                        startRepeat(now);
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                KeyView hitUp = hitTest(event.getX(), event.getY());
                setPressed(null);
                stopRepeat();
                // 落在哪个键上就敲哪个（iOS 行为）；删除键在 DOWN 时已触发，这里不重复
                if (hitUp != null && hitUp.type != KeyView.TYPE_DELETE) {
                    fire(hitUp);
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                setPressed(null);
                stopRepeat();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void setPressed(KeyView key) {
        if (pressedKey != null) {
            pressedKey.pressed = false;
            pressedKey.invalidate();
        }
        pressedKey = key;
        if (key != null) {
            key.pressed = true;
            key.invalidate();
            popupKey = key.type == KeyView.TYPE_CHAR ? key : null;
        } else {
            popupKey = null;
        }
        invalidate();
    }

    private void startRepeat(KeyView key) {
        repeatKey = key;
        handler.removeCallbacks(repeatTask);
        handler.postDelayed(repeatTask, REPEAT_DELAY_MS);
    }

    private void stopRepeat() {
        repeatKey = null;
        handler.removeCallbacks(repeatTask);
    }

    private KeyView hitTest(float x, float y) {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (!(child instanceof KeyView)) continue;
            if (x >= child.getLeft() && x <= child.getRight()
                    && y >= child.getTop() && y <= child.getBottom()) {
                return (KeyView) child;
            }
        }
        return null;
    }

    private void fire(KeyView key) {
        switch (key.type) {
            case KeyView.TYPE_SHIFT:
                toggleShift();
                break;
            case KeyView.TYPE_LAYER:
                setPage(page == PAGE_LETTER ? PAGE_SYMBOL : PAGE_LETTER);
                break;
            case KeyView.TYPE_DELETE:
                // DOWN 时已经删过一次，这里不重复
                break;
            case KeyView.TYPE_SPACE:
                TextInjector.space();
                break;
            case KeyView.TYPE_RETURN:
                TextInjector.enter();
                break;
            case KeyView.TYPE_GLOBE:
                pickInputMethod();
                break;
            case KeyView.TYPE_CHAR:
            default: {
                if (key.label.length() != 1) {
                    TextInjector.type(key.label);
                    break;
                }
                char c = key.label.charAt(0);
                if (c < 128) {
                    if (shift && c >= 'a' && c <= 'z') {
                        // 大写字母直接上屏（不经拼音引擎），并复位 shift（iOS 单击 shift 只作用一次）
                        TextInjector.type(String.valueOf(Character.toUpperCase(c)));
                        toggleShift();
                    } else {
                        // 小写字母/数字走键码，进搜狗拼音/数字引擎，会出候选
                        TextInjector.key(c);
                    }
                } else {
                    // 中文标点等非 ASCII：交给搜狗自己的符号处理
                    TextInjector.type(String.valueOf(c));
                }
                break;
            }
        }
    }

    private void pickInputMethod() {
        try {
            InputMethodManager imm = (InputMethodManager) getContext()
                    .getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
        } catch (Throwable t) {
            HookRegistry.log("showInputMethodPicker failed");
            HookRegistry.log(t);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stopRepeat();
        super.onDetachedFromWindow();
    }
}
