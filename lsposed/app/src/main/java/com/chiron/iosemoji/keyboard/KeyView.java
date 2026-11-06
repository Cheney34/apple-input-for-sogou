package com.chiron.iosemoji.keyboard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * 一颗键帽。只负责画，**不处理触摸** —— 命中判定与状态机都在
 * {@link ChironKeyboardView} 里统一做，这样滑动跨键、长按连发才好实现。
 */
final class KeyView extends View {

    static final int TYPE_CHAR = 0;
    static final int TYPE_SHIFT = 1;
    static final int TYPE_DELETE = 2;
    static final int TYPE_SPACE = 3;
    static final int TYPE_RETURN = 4;
    static final int TYPE_GLOBE = 5;
    static final int TYPE_LAYER = 6;

    private final Paint rectPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();

    String label = "";
    int type = TYPE_CHAR;
    boolean funcStyle;
    boolean pressed;
    boolean shiftOn;

    private KeyboardTheme theme = KeyboardTheme.of(false);
    private float radius;

    KeyView(Context context) {
        super(context);
        setWillNotDraw(false);
        glyphPaint.setStyle(Paint.Style.STROKE);
        glyphPaint.setStrokeCap(Paint.Cap.ROUND);
        glyphPaint.setStrokeJoin(Paint.Join.ROUND);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    void apply(KeyboardTheme theme, float cornerRadius, float textSize, float funcTextSize) {
        this.theme = theme;
        this.radius = cornerRadius;
        float size = funcStyle ? funcTextSize : textSize;
        textPaint.setTextSize(size);
        textPaint.setColor(funcStyle ? theme.funcTextColor : theme.textColor);
        glyphPaint.setStrokeWidth(Math.max(2f, size * 0.10f));
        glyphPaint.setColor(funcStyle ? theme.funcTextColor : theme.textColor);
        setBackgroundColor(theme.keyboardBg);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        int bg = funcStyle ? theme.funcBg : theme.keyBg;
        if (pressed) bg = funcStyle ? theme.funcPressed : theme.keyPressed;

        float shadowOffset = h * 0.024f;
        rect.set(0, 0, w, h);
        rectPaint.setColor(theme.shadowColor);
        rect.offset(0, shadowOffset);
        canvas.drawRoundRect(rect, radius, radius, rectPaint);
        rect.offset(0, -shadowOffset);

        rectPaint.setColor(bg);
        canvas.drawRoundRect(rect, radius, radius, rectPaint);

        if (type == TYPE_CHAR || type == TYPE_SPACE || type == TYPE_LAYER) {
            drawLabel(canvas, w, h);
        } else {
            drawGlyph(canvas, w, h);
        }
    }

    private void drawLabel(Canvas canvas, float w, float h) {
        String text = label;
        if (type == TYPE_CHAR && shiftOn && text.length() == 1 && text.charAt(0) >= 'a' && text.charAt(0) <= 'z') {
            text = text.toUpperCase();
        }
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float baseline = h / 2f - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(text, w / 2f, baseline, textPaint);
    }

    private void drawGlyph(Canvas canvas, float w, float h) {
        float cx = w / 2f;
        float cy = h / 2f;
        float s = Math.min(w, h) * 0.26f;
        switch (type) {
            case TYPE_SHIFT: {
                path.reset();
                path.moveTo(cx, cy - s * 1.05f);
                path.lineTo(cx + s * 1.15f, cy + s * 0.18f);
                path.lineTo(cx + s * 0.52f, cy + s * 0.18f);
                path.lineTo(cx + s * 0.52f, cy + s * 1.05f);
                path.lineTo(cx - s * 0.52f, cy + s * 1.05f);
                path.lineTo(cx - s * 0.52f, cy + s * 0.18f);
                path.lineTo(cx - s * 1.15f, cy + s * 0.18f);
                path.close();
                glyphPaint.setStyle(shiftOn ? Paint.Style.FILL : Paint.Style.STROKE);
                canvas.drawPath(path, glyphPaint);
                glyphPaint.setStyle(Paint.Style.STROKE);
                break;
            }
            case TYPE_DELETE: {
                // 左侧带尖角的删除键轮廓 + 中间一个 ×
                float hw = Math.min(w * 0.30f, h * 0.42f);
                float hh = Math.min(w * 0.20f, h * 0.28f);
                path.reset();
                path.moveTo(cx - hw, cy);
                path.lineTo(cx - hw + hh, cy - hh);
                path.lineTo(cx + hw, cy - hh);
                path.lineTo(cx + hw, cy + hh);
                path.lineTo(cx - hw + hh, cy + hh);
                path.close();
                glyphPaint.setStyle(Paint.Style.STROKE);
                canvas.drawPath(path, glyphPaint);
                float x = hh * 0.45f;
                canvas.drawLine(cx - x, cy - x, cx + x, cy + x, glyphPaint);
                canvas.drawLine(cx - x, cy + x, cx + x, cy - x, glyphPaint);
                break;
            }
            case TYPE_RETURN: {
                float hw = Math.min(w * 0.30f, h * 0.44f);
                path.reset();
                path.moveTo(cx + hw, cy - hw * 0.75f);
                path.lineTo(cx + hw, cy + hw * 0.35f);
                path.lineTo(cx - hw, cy + hw * 0.35f);
                path.moveTo(cx - hw + hw * 0.55f, cy - hw * 0.12f);
                path.lineTo(cx - hw, cy + hw * 0.35f);
                path.lineTo(cx - hw + hw * 0.55f, cy + hw * 0.82f);
                canvas.drawPath(path, glyphPaint);
                break;
            }
            case TYPE_GLOBE: {
                float r = Math.min(w, h) * 0.22f;
                canvas.drawCircle(cx, cy, r, glyphPaint);
                canvas.drawLine(cx, cy - r, cx, cy + r, glyphPaint);
                float rr = r * 0.55f;
                RectF oval = new RectF(cx - rr, cy - r, cx + rr, cy + r);
                canvas.drawOval(oval, glyphPaint);
                canvas.drawLine(cx - r, cy, cx + r, cy, glyphPaint);
                break;
            }
            default:
                drawLabel(canvas, w, h);
                break;
        }
    }
}
