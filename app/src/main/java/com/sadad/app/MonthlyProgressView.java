package com.sadad.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Smooth RTL share of this month's debt and payment movements. */
final class MonthlyProgressView extends View {
    final float ratio;
    private float progress;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int color;
    private ValueAnimator animator;
    MonthlyProgressView(Context context, int color, double ratio) {
        super(context); this.color = color; this.ratio = (float)Math.max(0, Math.min(1, ratio));
        setContentDescription("نسبة الشهر " + Math.round(this.ratio * 100) + "%");
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        animator = ValueAnimator.ofFloat(0, ratio); animator.setDuration(650); animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> { progress = (float)a.getAnimatedValue(); invalidate(); }); animator.start();
    }
    @Override protected void onDetachedFromWindow() { if(animator != null) animator.cancel(); super.onDetachedFromWindow(); }
    @Override protected void onDraw(Canvas canvas) {
        paint.setColor(color); paint.setAlpha(30); canvas.drawRoundRect(0, 0, getWidth(), getHeight(), getHeight()/2f, getHeight()/2f, paint);
        paint.setAlpha(255); float width = getWidth() * progress;
        if (width > 0) canvas.drawRoundRect(getWidth()-width, 0, getWidth(), getHeight(), getHeight()/2f, getHeight()/2f, paint);
    }
}
