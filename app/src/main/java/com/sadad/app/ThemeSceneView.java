package com.sadad.app;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Small vector-like scenes drawn natively for the appearance choices. */
final class ThemeSceneView extends View {
    private final boolean night;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    ThemeSceneView(Context context, boolean night) { super(context); this.night = night; setContentDescription(night ? "قمر ونجوم" : "شمس وعصافير صغيرة"); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); canvas.save(); canvas.scale(getWidth() / 100f, getHeight() / 80f);
        paint.setStyle(Paint.Style.FILL); paint.setColor(Color.parseColor(night ? "#173D36" : "#DDF4E7")); canvas.drawRoundRect(new RectF(2, 2, 98, 78), 18, 18, paint);
        if (night) {
            paint.setColor(Color.parseColor("#F5DE91")); canvas.drawCircle(41, 35, 20, paint); paint.setColor(Color.parseColor("#173D36")); canvas.drawCircle(50, 28, 19, paint);
            star(canvas, 72, 19, 4); star(canvas, 79, 43, 3); star(canvas, 58, 57, 3); star(canvas, 19, 17, 2);
            paint.setColor(Color.parseColor("#A4DBBE")); canvas.drawCircle(17, 43, 1.6f, paint); canvas.drawCircle(85, 61, 1.5f, paint);
        } else {
            paint.setColor(Color.parseColor("#F7C96A")); canvas.drawCircle(35, 31, 13, paint); paint.setStrokeWidth(2.3f); paint.setStrokeCap(Paint.Cap.ROUND);
            for (int i = 0; i < 8; i++) { double angle = i * Math.PI / 4; canvas.drawLine(35 + (float)Math.cos(angle)*18, 31+(float)Math.sin(angle)*18,35+(float)Math.cos(angle)*23,31+(float)Math.sin(angle)*23,paint); }
            paint.setColor(Color.parseColor("#36735E")); paint.setStyle(Paint.Style.STROKE);
            for (int i = 0; i < 3; i++) { float x = 63+i*7, y = 21+i*10; Path bird = new Path(); bird.moveTo(x-5,y+2); bird.quadTo(x-2,y-3,x,y+2); bird.quadTo(x+3,y-3,x+6,y+2); canvas.drawPath(bird,paint); }
            paint.setStyle(Paint.Style.FILL); paint.setColor(Color.parseColor("#F5FCF6")); canvas.drawOval(new RectF(22,55,66,65),paint); canvas.drawCircle(37,55,7,paint); canvas.drawCircle(49,56,6,paint);
        }
        canvas.restore();
    }
    private void star(Canvas canvas, float x, float y, float radius) { paint.setStyle(Paint.Style.FILL); paint.setColor(Color.parseColor("#F6E8B6")); Path star=new Path();star.moveTo(x,y-radius);star.lineTo(x+radius*.35f,y-radius*.35f);star.lineTo(x+radius,y);star.lineTo(x+radius*.35f,y+radius*.35f);star.lineTo(x,y+radius);star.lineTo(x-radius*.35f,y+radius*.35f);star.lineTo(x-radius,y);star.lineTo(x-radius*.35f,y-radius*.35f);star.close();canvas.drawPath(star,paint); }
}
