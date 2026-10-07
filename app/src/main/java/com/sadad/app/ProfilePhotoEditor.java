package com.sadad.app;

import android.app.AlertDialog;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;

/** Shared store/account portrait preview, crop, alignment and rotation. */
final class ProfilePhotoEditor {
    static int dp(MainActivity host, int value) { return Math.round(value * host.getResources().getDisplayMetrics().density); }
    static AlertDialog show(MainActivity host, Bitmap image, String account) {
        boolean dark = host.isDarkTheme(); int ink = Color.parseColor(dark ? "#F0F7EF" : "#142C24");
        LinearLayout body = new LinearLayout(host); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(host, 18), dp(host, 6), dp(host, 18), dp(host, 6)); body.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        CropView crop = new CropView(host, image); crop.setContentDescription("معاينة الصورة؛ اسحب للمحاذاة وقرّب بإصبعين"); body.addView(crop, new LinearLayout.LayoutParams(-1, dp(host, 230)));
        TextView help = new TextView(host); help.setText("اسحب الصورة لمحاذاتها، وحرّك الشريط للتكبير"); help.setTextColor(ink); help.setTextSize(13); help.setGravity(Gravity.CENTER); help.setPadding(0, dp(host, 10), 0, 0); body.addView(help);
        SeekBar zoom = new SeekBar(host); zoom.setMax(300); body.addView(zoom, new LinearLayout.LayoutParams(-1, dp(host, 44)));
        zoom.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() { public void onStartTrackingTouch(SeekBar bar) {} public void onStopTrackingTouch(SeekBar bar) {} public void onProgressChanged(SeekBar bar, int progress, boolean user) { if (user) crop.setZoom(1f + progress / 100f); } });
        crop.zoomChanged = () -> zoom.setProgress(Math.round((crop.zoom - 1f) * 100));
        LinearLayout actions = new LinearLayout(host); actions.setGravity(Gravity.CENTER); body.addView(actions);
        final AlertDialog[] dialog = {null};
        action(host, actions, "تدوير ٩٠°", () -> { crop.rotate(); zoom.setProgress(0); });
        action(host, actions, "توسيط", () -> { crop.center(); zoom.setProgress(0); });
        action(host, actions, "صورة جديدة", () -> { dialog[0].dismiss(); host.pickProfilePhoto(false); });
        dialog[0] = new SadadDialog.Builder(host).setTitle("معاينة وتعديل الصورة").setView(body).setNegativeButton("إلغاء", null).setPositiveButton("حفظ الصورة", (d, w) -> { Bitmap result = crop.export(); host.saveProfilePhoto(result, account); result.recycle(); }).create();
        dialog[0].setOnDismissListener(d -> crop.release()); dialog[0].show(); return dialog[0];
    }
    private static void action(MainActivity host, LinearLayout row, String title, Runnable task) {
        TextView button = new TextView(host); button.setText(title); button.setTextSize(12); button.setTextColor(Color.parseColor(host.isDarkTheme() ? "#F0F7EF" : "#064B42")); button.setGravity(Gravity.CENTER); button.setPadding(dp(host, 4), dp(host, 8), dp(host, 4), dp(host, 8));
        GradientDrawable fill = new GradientDrawable(); fill.setColor(Color.parseColor(host.isDarkTheme() ? "#2A4E42" : "#D6EFE4")); fill.setCornerRadius(dp(host, 9)); button.setBackground(fill); button.setOnClickListener(v -> task.run()); LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(host, 42), 1); params.setMargins(dp(host, 3), 0, dp(host, 3), 0); row.addView(button, params);
    }
    static final class CropView extends View {
        Bitmap source; float zoom = 1f, offsetX, offsetY, lastX, lastY; Runnable zoomChanged;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG); final ScaleGestureDetector pinch;
        CropView(MainActivity host, Bitmap image) {
            super(host); source = image.copy(Bitmap.Config.ARGB_8888, false);
            pinch = new ScaleGestureDetector(host, new ScaleGestureDetector.SimpleOnScaleGestureListener() { @Override public boolean onScale(ScaleGestureDetector detector) { setZoom(zoom * detector.getScaleFactor()); if (zoomChanged != null) zoomChanged.run(); return true; } });
        }
        float side() { return Math.min(getWidth(), getHeight()); }
        float scale() { return Math.max(side() / source.getWidth(), side() / source.getHeight()) * zoom; }
        void clamp() { float scale = scale(); float maxX = Math.max(0, (source.getWidth() * scale - side()) / 2f), maxY = Math.max(0, (source.getHeight() * scale - side()) / 2f); offsetX = Math.max(-maxX, Math.min(maxX, offsetX)); offsetY = Math.max(-maxY, Math.min(maxY, offsetY)); }
        void setZoom(float value) { zoom = Math.max(1f, Math.min(4f, value)); clamp(); invalidate(); }
        void center() { zoom = 1f; offsetX = offsetY = 0; invalidate(); }
        void rotate() { Matrix matrix = new Matrix(); matrix.postRotate(90); Bitmap rotated = Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true); source.recycle(); source = rotated; center(); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); if (source == null || source.isRecycled()) return; float edge = side(), left = (getWidth() - edge) / 2f, top = (getHeight() - edge) / 2f; clamp();
            canvas.save(); canvas.clipRect(left, top, left + edge, top + edge); float scale = scale(); Matrix matrix = new Matrix(); matrix.setScale(scale, scale); matrix.postTranslate(left + (edge - source.getWidth() * scale) / 2f + offsetX, top + (edge - source.getHeight() * scale) / 2f + offsetY); canvas.drawBitmap(source, matrix, paint); canvas.restore();
            paint.setStyle(Paint.Style.STROKE); paint.setColor(Color.rgb(120, 220, 190)); paint.setStrokeWidth(2); canvas.drawRect(left + 1, top + 1, left + edge - 1, top + edge - 1, paint); paint.setStyle(Paint.Style.FILL);
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            pinch.onTouchEvent(event); if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { lastX = event.getX(); lastY = event.getY(); getParent().requestDisallowInterceptTouchEvent(true); }
            else if (event.getActionMasked() == MotionEvent.ACTION_MOVE) { if (!pinch.isInProgress() && event.getPointerCount() == 1) { offsetX += event.getX() - lastX; offsetY += event.getY() - lastY; clamp(); invalidate(); } lastX = event.getX(); lastY = event.getY(); }
            else if (event.getActionMasked() == MotionEvent.ACTION_UP) { performClick(); getParent().requestDisallowInterceptTouchEvent(false); } return true;
        }
        @Override public boolean performClick() { super.performClick(); return true; }
        Bitmap export() { clamp(); Bitmap output = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888); Canvas canvas = new Canvas(output); float factor = 1024f / Math.max(1, side()), scale = scale() * factor; Matrix matrix = new Matrix(); matrix.setScale(scale, scale); matrix.postTranslate((1024 - source.getWidth() * scale) / 2f + offsetX * factor, (1024 - source.getHeight() * scale) / 2f + offsetY * factor); canvas.drawBitmap(source, matrix, paint); return output; }
        void release() { if (source != null && !source.isRecycled()) source.recycle(); }
    }
}
