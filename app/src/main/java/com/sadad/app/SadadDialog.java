package com.sadad.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.EditText;
import android.widget.TextView;

/** One theme for all app-owned confirmation, account and form dialogs. */
final class SadadDialog {
    static boolean dark(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof MainActivity) return ((MainActivity) context).isDarkTheme();
            Context next = ((ContextWrapper) context).getBaseContext(); if (next == context) break; context = next;
        }
        return false;
    }
    static int theme(Context context) { return dark(context) ? R.style.Sadad_Dialog_Dark : R.style.Sadad_Dialog_Light; }
    static Context context(Context context) { return new ContextThemeWrapper(context, theme(context)); }
    private static int dp(Context context, int value) { return Math.round(context.getResources().getDisplayMetrics().density * value); }
    private static GradientDrawable shape(int fill, int stroke, Context context, int radius) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(fill); drawable.setCornerRadius(dp(context, radius)); if (stroke != 0) drawable.setStroke(dp(context, 1), stroke); return drawable;
    }
    static void decorate(AlertDialog dialog, boolean calendar) {
        Context context = dialog.getContext(); boolean dark = dark(context);
        int background = Color.parseColor(dark ? "#1C3931" : "#F1F7F0");
        int foreground = Color.parseColor(dark ? "#F0F7EF" : "#142C24");
        int border = Color.parseColor(dark ? "#426857" : "#CCDCD0");
        Window window = dialog.getWindow(); if (window == null) return;
        window.setBackgroundDrawable(shape(background, border, context, 24)); window.setDimAmount(dark ? .48f : .36f);
        window.setLayout(Math.min(context.getResources().getDisplayMetrics().widthPixels - dp(context, 32), dp(context, 480)), ViewGroup.LayoutParams.WRAP_CONTENT);
        View decor = window.getDecorView(); decor.setLayoutDirection(View.LAYOUT_DIRECTION_RTL); decor.setClipToOutline(true);
        if (android.os.Build.VERSION.SDK_INT >= 29) decor.setForceDarkAllowed(false);
        if (!calendar) styleText(decor, foreground, context);
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            Button button = dialog.getButton(which); if (button == null) continue;
            int fill = which == AlertDialog.BUTTON_POSITIVE ? Color.parseColor(dark ? "#397D66" : "#064B42") : Color.parseColor(dark ? "#2A4E42" : "#D6EFE4");
            button.setTextColor(which == AlertDialog.BUTTON_POSITIVE ? Color.WHITE : foreground); button.setAllCaps(false); button.setTextSize(13); button.setTypeface(Typeface.DEFAULT, Typeface.BOLD); button.setMinHeight(dp(context, 46)); button.setMinimumHeight(dp(context, 46)); button.setPadding(dp(context, 14), dp(context, 8), dp(context, 14), dp(context, 8)); button.setMaxLines(3); button.setSingleLine(false); button.setIncludeFontPadding(true);
            if (button.getParent() instanceof LinearLayout) {
                LinearLayout panel = (LinearLayout) button.getParent(); panel.setOrientation(LinearLayout.HORIZONTAL); panel.setGravity(android.view.Gravity.CENTER); panel.setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8));
                // AlertDialogLayout reserves only one button height for buttonPanel.
                // Reserve enough height for text, rounded edges and action padding.
                panel.setMinimumHeight(dp(context, 72));
                ViewGroup.LayoutParams pp = panel.getLayoutParams(); pp.height = ViewGroup.LayoutParams.WRAP_CONTENT; if (pp instanceof LinearLayout.LayoutParams) ((LinearLayout.LayoutParams) pp).weight = 0; panel.setLayoutParams(pp);
                for (int i = 0; i < panel.getChildCount(); i++) if (!(panel.getChildAt(i) instanceof Button)) panel.getChildAt(i).setVisibility(View.GONE);
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1); button.setLayoutParams(bp);
            }
            button.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(50, 120, 210, 175)), shape(fill, 0, context, 14), null));
            ViewGroup.LayoutParams parameters = button.getLayoutParams(); if (parameters instanceof ViewGroup.MarginLayoutParams) { ((ViewGroup.MarginLayoutParams) parameters).setMargins(dp(context, 4), 0, dp(context, 4), 0); button.setLayoutParams(parameters); }
        }
    }
    private static void styleText(View view, int color, Context context) {
        if (view instanceof TextView && !(view instanceof EditText) && !(view instanceof Button)) {
            TextView text = (TextView) view; text.setTextColor(color); text.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            boolean title = view.getId() == context.getResources().getIdentifier("alertTitle", "id", "android");
            text.setTextSize(title ? 20 : 15); if (title) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            if (view.getId() == android.R.id.message) { text.setLineSpacing(dp(context, 4), 1f); text.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START); }
        }
        if (view instanceof ViewGroup) { ViewGroup group = (ViewGroup) view; for (int i = 0; i < group.getChildCount(); i++) styleText(group.getChildAt(i), color, context); }
    }
    static final class Builder extends AlertDialog.Builder {
        Builder(Context context) { super(SadadDialog.context(context)); }
        @Override public AlertDialog create() {
            AlertDialog dialog = super.create(); View decor = dialog.getWindow().getDecorView();
            // Attachment listener keeps caller OnShow validation callbacks intact.
            decor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View view) { decorate(dialog, false); }
                @Override public void onViewDetachedFromWindow(View view) { }
            }); return dialog;
        }
    }
}
