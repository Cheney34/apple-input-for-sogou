package com.chiron.iosemoji.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * iOS 风格开关（自绘，不依赖 SwitchCompat，避免引入 androidx）。
 *
 * <p>尺寸取 iOS 的 51×31pt：轨道圆角=半高，滑块直径=轨道高-4pt，
 * 关闭态轨道灰、开启态轨道绿，滑块带一点阴影。
 */
public final class IosSwitch extends View {

    public interface OnChangeListener {
        void onChanged(boolean checked);
    }

    private static final float TRACK_W_DP = 51f;
    private static final float TRACK_H_DP = 31f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF track = new RectF();
    private final float trackW;
    private final float trackH;
    private final float thumbR;

    private boolean checked;
    private float position; // 0 = off, 1 = on
    private boolean animating;
    private OnChangeListener listener;
    private boolean touchInside;

    public IosSwitch(Context context) {
        super(context);
        trackW = IosUi.dp(context, TRACK_W_DP);
        trackH = IosUi.dp(context, TRACK_H_DP);
        thumbR = trackH / 2f - IosUi.dp(context, 2f);
        setClickable(true);
        setFocusable(true);
    }

    public void setChecked(boolean value) {
        if (this.checked == value) {
            position = value ? 1f : 0f;
            invalidate();
            return;
        }
        animateTo(value ? 1f : 0f);
        this.checked = value;
        invalidate();
    }

    public boolean isChecked() {
        return checked;
    }

    public void setOnChangeListener(OnChangeListener l) {
        this.listener = l;
    }

    private void animateTo(float target) {
        if (animating) return;
        animating = true;
        ValueAnimator animator = ValueAnimator.ofFloat(position, target);
        animator.setDuration(180L);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            position = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                animating = false;
            }
        });
        animator.start();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(Math.round(trackW) + getPaddingLeft() + getPaddingRight(),
                Math.round(trackH) + getPaddingTop() + getPaddingBottom());
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float left = getPaddingLeft();
        float top = (getHeight() - trackH) / 2f;
        track.set(left, top, left + trackW, top + trackH);

        int offColor = IosUi.isDark(getContext()) ? 0xFF39393D : 0xFFE9E9EB;
        int onColor = IosUi.GREEN;
        paint.setColor(blend(offColor, onColor, position));
        canvas.drawRoundRect(track, trackH / 2f, trackH / 2f, paint);

        float cx = left + trackH / 2f + (trackW - trackH) * position;
        float cy = top + trackH / 2f;

        paint.setColor(0x33000000);
        canvas.drawCircle(cx, cy + IosUi.dp(getContext(), 0.7f), thumbR, paint);
        paint.setColor(0xFFFFFFFF);
        canvas.drawCircle(cx, cy, thumbR, paint);

        if (touchInside) {
            paint.setColor(0x14000000);
            canvas.drawRoundRect(track, trackH / 2f, trackH / 2f, paint);
        }
    }

    private static int blend(int from, int to, float t) {
        return Color.argb(
                Math.round(Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t),
                Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * t),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * t),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t));
    }

    @Override
    public boolean performClick() {
        boolean handled = super.performClick();
        checked = !checked;
        animateTo(checked ? 1f : 0f);
        if (listener != null) listener.onChanged(checked);
        return handled;
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent event) {
        switch (event.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                touchInside = true;
                invalidate();
                return true;
            case android.view.MotionEvent.ACTION_CANCEL:
                touchInside = false;
                invalidate();
                return true;
            case android.view.MotionEvent.ACTION_UP:
                touchInside = false;
                invalidate();
                performClick();
                return true;
            default:
                return true;
        }
    }
}
