package com.sadad.app;

import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.pdf.PdfDocument;
import android.graphics.drawable.GradientDrawable;
import android.text.Layout;
import android.text.Editable;
import android.text.StaticLayout;
import android.text.TextDirectionHeuristics;
import android.text.TextPaint;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.text.SimpleDateFormat;

/** Small, native Android view layer. Each page uses a real ScrollView and standard Android controls. */
final class NativeScreens {
    private final MainActivity host;
    private LinearLayout root;
    private LinearLayout content;
    private int bg, surface, soft, primary, accent, text, muted, line, warn;
    private String route = "welcome";
    private String searchText = "";
    private int contactFilter;
    private int contactSort;
    private LinearLayout contactRows, contactChips;
    private int contactLimit = 60;
    private int historyLimit = 100;
    private int personHistoryPage; private long personHistoryOwner;
    private int homeHistoryLimit = 5;
    private static final int HOME_HISTORY_BATCH = 50;
    private int reportContactLimit = 60;
    private String reportSearchText = "";
    private long reportStartMillis = Long.MIN_VALUE;
    private long reportEndMillis = Long.MAX_VALUE;
    private String reportRangeLabel = "كل المدة";
    private Set<Long> reportContactIds = new HashSet<>();
    private boolean onlyReceivableDebtors;
    private boolean historyOpened;
    private boolean settingsPeriodInitialized;
    private boolean scrollContactsToBottom;
    long editingContactId;
    long contactId;

    NativeScreens(MainActivity host) { this.host = host; }

    void show(String page) {
        route = page;
        palette();
        if ("welcome".equals(page)) { buildWelcome(); return; }
        if ("login".equals(page)) { buildLogin(); return; }
        if ("customer_login".equals(page)) { buildCustomerLogin(); return; }
        if ("customer_portal".equals(page)) { buildCustomerPortal(); return; }
        if ("force_password".equals(page)) { buildForcedPasswordChange(); return; }
        if ("unlock".equals(page)) { buildUnlock(); return; }
        buildShell(page);
    }

    private void palette() {
        boolean dark = host.isDarkTheme();
        bg = Color.parseColor(dark ? "#102721" : "#F5F8F3");
        surface = Color.parseColor(dark ? "#1C3931" : "#E7F3ED");
        soft = Color.parseColor(dark ? "#2A4E42" : "#D6EFE4");
        primary = Color.parseColor(dark ? "#256C5A" : "#064B42");
        accent = Color.parseColor(dark ? "#78DEC0" : "#159D89");
        text = Color.parseColor(dark ? "#F0F7EF" : "#142C24");
        muted = Color.parseColor(dark ? "#B5CEC0" : "#65796C");
        line = Color.parseColor(dark ? "#375A4B" : "#D5E5D9");
        warn = Color.parseColor(dark ? "#FFD08A" : "#9A5A13");
    }

    private void buildWelcome() {
        root = new LinearLayout(host); root.setOrientation(LinearLayout.VERTICAL); root.setGravity(Gravity.CENTER);
        root.setPadding(dp(28), dp(28), dp(28), dp(28)); root.setBackground(pageBackground()); root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.addView(new LogoAssemblyView(), new LinearLayout.LayoutParams(dp(238), dp(238)));
        addGap(root, 15);
        TextView eyebrow = label("إدارة أسهل لمتجرك", 14, accent, true); eyebrow.setGravity(Gravity.CENTER); root.addView(eyebrow);
        TextView title = label("أهلاً بك في سدد", 27, text, true); title.setGravity(Gravity.CENTER); title.setPadding(0, dp(5), 0, 0); root.addView(title);
        TextView subtitle = label("حسابات واضحة، وسجل مرتب، ومتابعة أسرع", 15, muted, false);
        subtitle.setGravity(Gravity.CENTER); subtitle.setPadding(0, dp(8), 0, dp(24)); root.addView(subtitle);
        root.addView(button("ابدأ استخدام التطبيق", primary, Color.WHITE, () -> host.finishWelcome()), match());
        TextView footer = label("أدخل حساب المتجر الذي أنشأه الأدمن", 13, muted, false); footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(18), 0, 0); root.addView(footer);
        host.setContentView(root);
    }

    /** Reveals the three exact vertical portions of the supplied logo artwork, then leaves them assembled. */
    private final class LogoAssemblyView extends View {
        private final Bitmap artwork;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private float progress;
        private boolean animated;

        LogoAssemblyView() {
            super(host);
            Drawable logo = host.getDrawable(host.brandLogoResource());
            if (logo == null) artwork = null;
            else {
                Bitmap rendered = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888);
                Canvas rasterCanvas = new Canvas(rendered);
                logo.setBounds(0, 0, rendered.getWidth(), rendered.getHeight());
                logo.draw(rasterCanvas);
                artwork = rendered;
            }
            setLayerType(View.LAYER_TYPE_HARDWARE, null);
            setContentDescription("شعار سدد يتحرك ويتجمع");
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (animated) return;
            animated = true;
            ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(1800L);
            animator.setInterpolator(new DecelerateInterpolator(1.5f));
            animator.addUpdateListener(value -> { progress = (float) value.getAnimatedValue(); invalidate(); });
            animator.start();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (artwork == null) return;
            float size = Math.min(getWidth(), getHeight()) * .94f;
            float left = (getWidth() - size) / 2f, top = (getHeight() - size) / 2f;
            float[] fromX = {-.19f * size, 0f, .19f * size};
            float[] fromY = {-.08f * size, .14f * size, -.08f * size};
            for (int part = 0; part < 3; part++) {
                float startAt = part * .11f;
                float t = Math.max(0f, Math.min(1f, (progress - startAt) / (1f - startAt)));
                float eased = t * t * (3f - 2f * t);
                canvas.save();
                canvas.clipRect(left + size * part / 3f, top, left + size * (part + 1) / 3f, top + size);
                canvas.translate(fromX[part] * (1f - eased), fromY[part] * (1f - eased));
                paint.setAlpha(Math.round(255f * eased));
                canvas.drawBitmap(artwork, null, new RectF(left, top, left + size, top + size), paint);
                canvas.restore();
            }
            paint.setAlpha(255);
        }
    }

    private void buildLogin() {
        root = new LinearLayout(host); root.setOrientation(LinearLayout.VERTICAL); root.setGravity(Gravity.CENTER);
        root.setPadding(dp(24), dp(20), dp(24), dp(20)); root.setBackground(pageBackground()); root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        ScrollView scroll = scroll();
        LinearLayout centering = new LinearLayout(host); centering.setOrientation(LinearLayout.VERTICAL); centering.setGravity(Gravity.CENTER);
        LinearLayout body = new LinearLayout(host); body.setOrientation(LinearLayout.VERTICAL); body.setGravity(Gravity.CENTER_HORIZONTAL);
        body.setPadding(dp(22), dp(28), dp(22), dp(28)); body.setBackground(shape(surface, 26));
        body.addView(logo(dp(112)), new LinearLayout.LayoutParams(dp(112), dp(112)));
        TextView eyebrow = label("إدارة حسابات المتجر", 14, accent, true); eyebrow.setGravity(Gravity.CENTER); eyebrow.setPadding(0, dp(16), 0, dp(4)); body.addView(eyebrow);
        TextView title = label("تسجيل الدخول", 25, text, true); title.setGravity(Gravity.CENTER); body.addView(title);
        TextView subtitle = label("أدخل بيانات حسابك للمتابعة", 14, muted, false); subtitle.setGravity(Gravity.CENTER); subtitle.setPadding(0, dp(4), 0, dp(18)); body.addView(subtitle);
        EditText username = input("اسم المستخدم", false); body.addView(username, matchWithBottom(12));
        EditText password = input("كلمة المرور", true); body.addView(password, matchWithBottom(16));
        EditText staffName = input("اسم الشخص على هذا الجهاز", false); staffName.setSingleLine(true);
        body.addView(staffName, matchWithBottom(12));
        if (BuildConfig.STANDALONE_MODE) {
            body.addView(button("دخول آمن", primary, Color.WHITE, () -> host.loginLocal(username.getText().toString(), password.getText().toString(), staffName.getText().toString())), match());
        } else {
            EditText serverUrl = input("رابط الخادم وينتهي بـ /api", false); serverUrl.setSingleLine(true); serverUrl.setText(host.sessions.apiBaseUrl());
            body.addView(serverUrl, matchWithBottom(16));
            body.addView(button("دخول آمن", primary, Color.WHITE, () -> {
                if (host.configureApiBaseUrl(serverUrl.getText().toString())) host.loginLocal(username.getText().toString(), password.getText().toString(), staffName.getText().toString());
            }), match());
        }

        TextView support = label("تواصل معنا لتفعيل حسابك", 14, primary, true);
        support.setGravity(Gravity.CENTER); support.setPadding(0, dp(15), 0, dp(4));
        support.setPaintFlags(support.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        support.setContentDescription("تواصل معنا على واتساب لتفعيل حسابك");
        support.setOnClickListener(v -> host.openSupportWhatsApp()); body.addView(support, match());
        centering.addView(body, new LinearLayout.LayoutParams(-1, -2));
        scroll.addView(centering, new ScrollView.LayoutParams(-1, -1));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, -1)); host.setContentView(root);
    }

    private void buildCustomerLogin() {
        root = new LinearLayout(host); root.setOrientation(LinearLayout.VERTICAL); root.setGravity(Gravity.CENTER);
        root.setPadding(dp(24), dp(20), dp(24), dp(20)); root.setBackground(pageBackground()); root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        LinearLayout card = new LinearLayout(host); card.setOrientation(LinearLayout.VERTICAL); card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(22), dp(28), dp(22), dp(26)); card.setBackground(shape(surface, 26));
        card.addView(logo(dp(98)), new LinearLayout.LayoutParams(dp(98), dp(98)));
        TextView eyebrow = label("بوابة الزبون", 14, accent, true); eyebrow.setGravity(Gravity.CENTER); eyebrow.setPadding(0, dp(14), 0, dp(4)); card.addView(eyebrow);
        TextView title = label("عرض حساباتي", 24, text, true); title.setGravity(Gravity.CENTER); card.addView(title);
        if (!BuildConfig.STANDALONE_MODE) {
            TextView notice = label("دخول الزبائن الرسمي يتطلب تفعيل التحقق من ملكية رقم الهاتف عبر SMS وربطه بالخادم. لم يتم تفعيل تسجيل حقيقي قبل اختيار مزود الرسائل.", 14, muted, false);
            notice.setGravity(Gravity.CENTER); notice.setPadding(0, dp(12), 0, dp(12)); card.addView(notice);
            card.addView(button("العودة لتسجيل دخول المتجر", primary, Color.WHITE, () -> host.show("login")), matchWithBottom(10));
            card.addView(button("تواصل معنا", soft, softForeground(), host::openSupportWhatsApp), match());
        } else {
            TextView hint = label("أدخل رقم الهاتف وكلمة مرور حساب الاختبار.", 14, muted, false); hint.setGravity(Gravity.CENTER); hint.setPadding(0, dp(6), 0, dp(14)); card.addView(hint);
            EditText phone = input("رقم الهاتف", false); phone.setSingleLine(true); phone.setInputType(3); phone.setTextDirection(View.TEXT_DIRECTION_LTR);
            card.addView(phone, matchWithBottom(10));
            EditText password = input("كلمة المرور", true); password.setSingleLine(true); card.addView(password, matchWithBottom(14));
            TextView demo = label("بيانات حساب الاختبار: 0599999999 / 123123", 13, accent, true); demo.setGravity(Gravity.CENTER); demo.setPadding(0, 0, 0, dp(12)); card.addView(demo);
            card.addView(button("دخول الزبون", primary, Color.WHITE, () -> host.loginDemoCustomer(phone.getText().toString(), password.getText().toString())), matchWithBottom(10));
            card.addView(button("العودة لتسجيل دخول المتجر", soft, softForeground(), () -> host.show("login")), match());
        }
        root.addView(card, new LinearLayout.LayoutParams(-1, -2)); host.setContentView(root);
    }

    /** Local, read-only demonstration. It never adds sample balances and only shows exact phone matches. */
    private void buildCustomerPortal() {
        root = new LinearLayout(host); root.setOrientation(LinearLayout.VERTICAL); root.setBackground(pageBackground()); root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        ScrollView scroller = scroll(); LinearLayout page = new LinearLayout(host); page.setOrientation(LinearLayout.VERTICAL); page.setPadding(dp(18), dp(14), dp(18), dp(20));
        page.addView(logo(dp(64)), new LinearLayout.LayoutParams(dp(64), dp(64)));
        TextView title = label("حساباتي لدى المتاجر", 23, text, true); page.addView(title, topBottom(12, 3));
        page.addView(label("عرض محلي تجريبي للبيانات المرتبطة برقم الهاتف فقط.", 13, muted, false), bottomMargin(12));
        TextView privacy = label("هذه نسخة اختبار مستقلة. تظهر هنا السجلات الفعلية الموجودة على هذا الجهاز فقط؛ لا توجد بيانات تجريبية مضافة ولا اتصال بمتاجر أخرى.", 12, muted, false);
        privacy.setPadding(dp(12), dp(10), dp(12), dp(10)); privacy.setBackground(shape(soft, 16)); page.addView(privacy, bottomMargin(14));

        JSONObject data = snapshot(); JSONArray people = array(data, "contacts"); JSONArray transactions = array(data, "transactions"); JSONArray debts = array(data, "debts");
        int matched = 0; String signedPhone = "0599999999";
        for (int i = 0; i < people.length(); i++) {
            JSONObject person = people.optJSONObject(i); if (person == null || !normalizePhone(signedPhone).equals(normalizePhone(person.optString("phone")))) continue;
            matched++;
            LinearLayout shop = card(surface); shop.setPadding(dp(14), dp(14), dp(14), dp(14)); shop.setElevation(dp(2));
            shop.addView(label(host.storeName().isEmpty() ? "متجر محفوظ على هذا الجهاز" : host.storeName(), 17, text, true));
            shop.addView(label("الاسم المسجل: " + person.optString("name", "زبون"), 13, muted, false), topMargin(4));
            shop.addView(label("الرصيد المستحق: " + money(person.optDouble("receivable")), 18, primary, true), topMargin(9));
            if (person.optDouble("receivable") > 0d) {
                String store = host.storeName().trim().isEmpty() ? "متجر سدد" : host.storeName().trim();
                shop.addView(label("إشعار: لديك دين مسجل لدى متجر " + store + ".", 13, warn, true), topMargin(6));
            }
            int transactionCount = 0;
            for (int t = 0; t < transactions.length(); t++) {
                JSONObject tx = transactions.optJSONObject(t); if (tx == null || tx.optLong("contactId") != person.optLong("id") || !"receivable".equals(tx.optString("direction"))) continue;
                transactionCount++;
                String heading = "payment".equals(tx.optString("kind")) ? "دفعة مسجلة" : "دين مسجل";
                shop.addView(label(heading + " · " + money(tx.optDouble("amount")), 13, text, true), topMargin(7));
                shop.addView(label(dayAndTime(tx.optLong("createdAt")), 11, muted, false), topMargin(2));
                String note = tx.optString("note", "").trim(); if (!note.isEmpty()) shop.addView(label("البيان: " + note, 12, muted, false), topMargin(2));
            }
            int open = 0;
            for (int d = 0; d < debts.length(); d++) {
                JSONObject debt = debts.optJSONObject(d); if (debt == null || debt.optLong("contactId") != person.optLong("id") || !"receivable".equals(debt.optString("direction")) || debt.optDouble("remaining") <= 0) continue;
                open++;
                String due = debt.optString("dueDate", "").trim();
                if (!due.isEmpty()) shop.addView(label("استحقاق " + formatDueDate(due) + " · متبقٍ " + money(debt.optDouble("remaining")), 12, accent, false), topMargin(4));
            }
            if (transactionCount == 0) shop.addView(label("لا توجد حركات مسجلة.", 12, muted, false), topMargin(8));
            if (open == 0) shop.addView(label("لا يوجد رصيد دين مفتوح.", 12, accent, false), topMargin(6));
            page.addView(shop, bottomMargin(12));
        }
        if (matched == 0) {
            LinearLayout empty = card(surface); empty.setPadding(dp(16), dp(18), dp(16), dp(18));
            empty.addView(label("لا توجد سجلات لهذا الرقم على هذا الجهاز.", 15, text, true));
            empty.addView(label("أضف الرقم نفسه في حساب متجر الاختبار ثم افتح بوابة الزبون مجددًا.", 12, muted, false), topMargin(7));
            page.addView(empty, bottomMargin(14));
        }
        page.addView(button("تسجيل الخروج", soft, softForeground(), host::exitDemoCustomer), bottomMargin(8));
        scroller.addView(page); root.addView(scroller, new LinearLayout.LayoutParams(-1, -1)); host.setContentView(root);
    }

    private String normalizePhone(String value) {
        if (value == null) return "";
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '\u0660' && c <= '\u0669') c = (char) ('0' + c - '\u0660');
            else if (c >= '\u06F0' && c <= '\u06F9') c = (char) ('0' + c - '\u06F0');
            if (c >= '0' && c <= '9') digits.append(c);
        }
        String n = digits.toString();
        if (n.startsWith("00970")) return n.substring(2);
        if (n.startsWith("970")) return n;
        if (n.startsWith("0")) return "970" + n.substring(1);
        return n;
    }

    private void buildForcedPasswordChange() {
        root = new LinearLayout(host); root.setOrientation(LinearLayout.VERTICAL); root.setGravity(Gravity.CENTER);
        root.setPadding(dp(24), dp(20), dp(24), dp(20)); root.setBackground(pageBackground()); root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        LinearLayout card = new LinearLayout(host); card.setOrientation(LinearLayout.VERTICAL); card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(24), dp(28), dp(24), dp(26)); card.setBackground(shape(surface, 26));
        card.addView(logo(dp(94)), new LinearLayout.LayoutParams(dp(94), dp(94)));
        TextView title = label("غيّر كلمة المرور المؤقتة", 23, text, true); title.setGravity(Gravity.CENTER); title.setPadding(0, dp(16), 0, dp(6)); card.addView(title);
        TextView hint = label("يجب تعيين كلمة مرور جديدة للمتابعة إلى التطبيق.", 14, muted, false); hint.setGravity(Gravity.CENTER); hint.setPadding(0, 0, 0, dp(18)); card.addView(hint);
        EditText previous = input("كلمة المرور المؤقتة", true); card.addView(previous, matchWithBottom(10));
        EditText next = input("كلمة المرور الجديدة (12 محرفًا على الأقل)", true); card.addView(next, matchWithBottom(10));
        EditText confirm = input("تأكيد كلمة المرور الجديدة", true); card.addView(confirm, matchWithBottom(16));
        card.addView(button("حفظ ومتابعة", primary, Color.WHITE, () -> {
            String newPassword = next.getText().toString();
            if (!newPassword.equals(confirm.getText().toString())) { confirm.setError("كلمتا المرور غير متطابقتين."); return; }
            if (newPassword.length() < 12) { next.setError("استخدم 12 محرفًا على الأقل."); return; }
            host.changeServerPassword(previous.getText().toString(), newPassword, (success, message) -> {
                if (!success) { previous.setError(message); host.showBrandedMessage(message); }
            });
        }), match());
        root.addView(card, new LinearLayout.LayoutParams(-1, -2)); host.setContentView(root);
    }

    private void buildUnlock() {
        root = new LinearLayout(host); root.setOrientation(LinearLayout.VERTICAL); root.setGravity(Gravity.CENTER);
        root.setPadding(dp(24), dp(20), dp(24), dp(20)); root.setBackground(pageBackground()); root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        LinearLayout card = new LinearLayout(host); card.setOrientation(LinearLayout.VERTICAL); card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(24), dp(28), dp(24), dp(26)); card.setBackground(shape(surface, 26));
        card.addView(logo(dp(100)), new LinearLayout.LayoutParams(dp(100), dp(100)));
        TextView title = label("التطبيق مقفل", 24, text, true); title.setGravity(Gravity.CENTER); title.setPadding(0, dp(18), 0, dp(6)); card.addView(title);
        TextView hint = label("أدخل رمز القفل للعودة إلى الصفحة الرئيسية", 14, muted, false); hint.setGravity(Gravity.CENTER); hint.setPadding(0, 0, 0, dp(20)); card.addView(hint);
        EditText pin = input("رمز القفل", true); pin.setInputType(2 | 0x10); pin.setGravity(Gravity.CENTER); pin.setLetterSpacing(.18f);
        card.addView(pin, matchWithBottom(14));
        card.addView(button("فتح التطبيق", primary, Color.WHITE, () -> {
            if (!host.verifyAppPin(pin.getText().toString())) {
                long waitMs = host.localSecurity.pinLockoutRemainingMs();
                String error = waitMs > 0L ? "محاولات كثيرة؛ انتظر " + Math.max(1L, (waitMs + 999L) / 1000L) + " ثانية." : "رمز القفل غير صحيح";
                pin.setError(error); pin.setText("");
            }
        }), match());
        if (host.getPreferences(0).getBoolean("biometric_enabled", false)) {
            TextView bio = button("استخدام البصمة أو الوجه", soft, text, () -> host.requestBiometric(false));
            LinearLayout.LayoutParams p = matchWithBottom(10); p.topMargin = dp(10); card.addView(bio, p);
        }
        root.addView(card, new LinearLayout.LayoutParams(-1, -2)); host.setContentView(root);
    }

    private void buildShell(String page) {
        root = new LinearLayout(host); root.setOrientation(LinearLayout.VERTICAL); root.setBackground(pageBackground()); root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        String title = titleFor(page);
        FrameLayout header = new FrameLayout(host); header.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        header.setPadding(dp(16), dp(8), dp(16), dp(8)); header.setBackgroundColor(bg);
        LinearLayout brand = new LinearLayout(host); brand.setGravity(Gravity.CENTER_VERTICAL); brand.setOrientation(LinearLayout.HORIZONTAL);
        ImageView icon = logo(dp(42)); brand.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));
        View iconGap = new View(host); brand.addView(iconGap, new LinearLayout.LayoutParams(dp(10), 1));
        LinearLayout titleBox = new LinearLayout(host); titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.addView(label("سدد", 18, primary, true)); titleBox.addView(label(title, 12, muted, false));
        brand.addView(titleBox, new LinearLayout.LayoutParams(-2, -2));
        FrameLayout.LayoutParams brandParams = new FrameLayout.LayoutParams(-2, -2, Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        header.addView(brand, brandParams);


        FrameLayout bell = new FrameLayout(host); bell.setContentDescription("الإشعارات");
        ImageButton bellButton = new ImageButton(host); bellButton.setImageResource(R.drawable.ic_notifications);
        bellButton.setColorFilter(softForeground()); bellButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        bellButton.setBackground(shape(soft, 22)); bellButton.setContentDescription("الإشعارات");
        bell.addView(bellButton, new FrameLayout.LayoutParams(-1, -1));
        int unread = DueReminderManager.unreadCount(host);
        if (unread > 0) {
            TextView badge = label(unread > 9 ? "9+" : String.valueOf(unread), 9, Color.WHITE, true);
            badge.setGravity(Gravity.CENTER); badge.setBackground(shape(Color.rgb(193, 54, 52), 10));
            FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.RIGHT | Gravity.TOP);
            badge.setElevation(dp(2)); bell.addView(badge, badgeParams);
        }
        bell.setOnClickListener(v -> host.show("notifications")); bellButton.setOnClickListener(v -> host.show("notifications"));
        FrameLayout.LayoutParams bellPosition = new FrameLayout.LayoutParams(dp(38), dp(38), Gravity.LEFT | Gravity.CENTER_VERTICAL);
        bellPosition.leftMargin = dp(44); header.addView(bell, bellPosition);
        ImageView account = portrait(false, 38); account.setContentDescription("حسابات المتجر");
        account.setOnClickListener(v -> showAccountMenu());
        header.addView(account, new FrameLayout.LayoutParams(dp(38), dp(38), Gravity.LEFT | Gravity.CENTER_VERTICAL));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(58)));
        View headerDivider = new View(host);
        headerDivider.setBackgroundColor(Color.argb(72, Color.red(line), Color.green(line), Color.blue(line)));
        root.addView(headerDivider, new LinearLayout.LayoutParams(-1, dp(1)));
        ScrollView scroller = scroll(); content = new LinearLayout(host); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(6), dp(18), dp(22)); content.setClipToPadding(false); scroller.addView(content);
        root.addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1));
        addPage(page);
        if (showBottomNav(page)) root.addView(bottomNav(page), new LinearLayout.LayoutParams(-1, -2));
        host.setContentView(root);
        if (scrollContactsToBottom && "contacts".equals(page)) {
            scrollContactsToBottom = false;
            scroller.post(() -> scroller.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void addPage(String page) {
        switch (page) {
            case "home": buildHome(); break;
            case "contacts": buildContacts(); break;
            case "contact_detail": buildContactDetail(); break;
            case "contact_form": buildContactForm(); break;
            case "debt_form": buildDebtForm(); break;
            case "payment_form": buildPaymentForm(); break;
            case "history": historyOpened = false; buildHistory(); break;
            case "history_results": historyOpened = true; buildHistory(); break;
            case "reports": buildReports(); break;
            case "notifications": buildNotifications(); break;
            case "settings": buildSettings(); break;
            case "trash": buildTrash(); break;
            default: buildHome();
        }
    }

    private void buildHome() {
        JSONObject snap = snapshot(); JSONObject totals = snap.optJSONObject("totals"); if (totals == null) totals = new JSONObject();
        JSONArray contacts = array(snap, "contacts"), transactions = array(snap, "transactions");
        content.addView(label("مرحبًا بك، " + (host.storeName().isEmpty() ? "متجري" : host.storeName()), 23, text, true), topBottom(12, 12));
        LinearLayout hero = card(primary); hero.setOrientation(LinearLayout.HORIZONTAL); hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setPadding(dp(20), dp(22), dp(20), dp(22));
        hero.setBackground(new GradientDrawable(GradientDrawable.Orientation.TR_BL, new int[]{primary, Color.rgb(2, 48, 43)}));
        ((GradientDrawable) hero.getBackground()).setCornerRadius(dp(18)); hero.setElevation(dp(4));
        LinearLayout balance = new LinearLayout(host); balance.setOrientation(LinearLayout.VERTICAL);
        TextView badge = label("▤  إجمالي الدين", 11, Color.rgb(198, 240, 224), false);
        badge.setPadding(dp(10), dp(5), dp(10), dp(5)); badge.setBackground(shape(Color.argb(40, 255, 255, 255), 12)); balance.addView(badge, new LinearLayout.LayoutParams(-2, -2));
        balance.addView(label("لك عند الزبائن", 12, Color.rgb(198, 240, 224), false), topMargin(14));
        balance.addView(label(money(totals.optDouble("receivable")), 34, Color.WHITE, true), topMargin(2));
        hero.addView(balance, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView book = iconView(R.drawable.ledger_art, Color.WHITE); book.clearColorFilter();
        hero.addView(book, new LinearLayout.LayoutParams(dp(100), dp(110)));
        content.addView(hero, topBottom(14, 20));
        dashboardHeading("حركة الشهر الحالي", new SimpleDateFormat("MMMM yyyy", new Locale("ar")).format(new Date()));
        Calendar month = Calendar.getInstance(); month.set(Calendar.DAY_OF_MONTH, 1); month.set(Calendar.HOUR_OF_DAY, 0); month.set(Calendar.MINUTE, 0); month.set(Calendar.SECOND, 0); month.set(Calendar.MILLISECOND, 0);
        double debt = 0, paid = 0;
        for (int i = 0; i < transactions.length(); i++) { JSONObject tx = transactions.optJSONObject(i); if (!isReceivableTransaction(tx) || tx.optLong("createdAt") < month.getTimeInMillis()) continue;
            if ("payment".equals(tx.optString("kind"))) paid += tx.optDouble("amount"); else debt += tx.optDouble("amount"); }
        JSONObject monthly = snap.optJSONObject("monthly"); if(monthly != null) { debt=monthly.optDouble("debt");paid=monthly.optDouble("paid"); }
        LinearLayout metrics = new LinearLayout(host); metrics.setOrientation(LinearLayout.HORIZONTAL);
        metrics.addView(monthMetric("ديون مسجلة", debt, debt + paid == 0 ? 0 : debt / (debt + paid), host.isDarkTheme() ? Color.rgb(245, 154, 159) : Color.rgb(171, 50, 58)), new LinearLayout.LayoutParams(0, -2, 1)); metrics.addView(spaceWidth(10));
        metrics.addView(monthMetric("دفعات مستلمة", paid, debt + paid == 0 ? 0 : paid / (debt + paid), accent), new LinearLayout.LayoutParams(0, -2, 1)); content.addView(metrics, bottomMargin(20));
        dashboardHeading("آخر الزبائن", String.valueOf(contacts.length()));
        dashboardHeading("حركات اليوم", new SimpleDateFormat("EEEE، d MMMM", new Locale("ar")).format(new Date()));
        Calendar today = Calendar.getInstance(); today.set(Calendar.HOUR_OF_DAY, 0); today.set(Calendar.MINUTE, 0); today.set(Calendar.SECOND, 0); today.set(Calendar.MILLISECOND, 0);
        int shown = 0;
        for (int i = 0; i < transactions.length() && shown < 3; i++) { JSONObject tx = transactions.optJSONObject(i); if (!isReceivableTransaction(tx) || tx.optLong("createdAt") < today.getTimeInMillis()) continue; addDashboardTransaction(tx); shown++; }
        if (shown == 0) { TextView empty = label("لا توجد حركات اليوم", 13, muted, false); empty.setGravity(Gravity.CENTER); empty.setPadding(0, dp(18), 0, dp(18)); content.addView(empty, bottomMargin(10)); }

    }

    private void dashboardHeading(String title, String subtitle) {
        LinearLayout row = new LinearLayout(host); row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(label(title, 15, text, true), new LinearLayout.LayoutParams(0, -2, 1)); row.addView(label(subtitle, 10, muted, false)); content.addView(row, bottomMargin(10));
    }

    private LinearLayout monthMetric(String title, double value, double ratio, int color) {
        LinearLayout box = card(host.isDarkTheme() ? surface : Color.WHITE); box.setPadding(dp(15), dp(13), dp(15), dp(10));
        box.addView(label(title, 11, muted, false)); box.addView(label(money(value), 21, color, true), topMargin(5));
        MonthlyProgressView bar = new MonthlyProgressView(host, color, ratio); box.addView(bar, new LinearLayout.LayoutParams(-1, dp(4))); return box;
    }

    private void addDashboardTransaction(JSONObject tx) {
        boolean payment = "payment".equals(tx.optString("kind")); int color = payment ? (host.isDarkTheme() ? Color.rgb(249, 165, 168) : Color.rgb(172, 47, 60)) : (host.isDarkTheme() ? accent : primary);
        LinearLayout row = card(host.isDarkTheme() ? surface : Color.WHITE); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(12), dp(10), dp(12), dp(10));
        TextView arrow = label(payment ? "↗" : "↙", 23, color, true); arrow.setGravity(Gravity.CENTER); arrow.setBackground(shape(payment ? (host.isDarkTheme() ? Color.rgb(88, 59, 61) : Color.rgb(255, 225, 225)) : soft, 12)); row.addView(arrow, new LinearLayout.LayoutParams(dp(38), dp(38)));
        LinearLayout names = new LinearLayout(host); names.setOrientation(LinearLayout.VERTICAL); names.setPadding(dp(10), 0, dp(6), 0);
        names.addView(label(tx.optString("contactName", "شخص"), 14, text, true));
        names.addView(label((payment ? "دفعة مستلمة" : "دين مسجل") + " · " + new SimpleDateFormat("HH:mm", Locale.US).format(new Date(tx.optLong("createdAt"))), 10, muted, false), topMargin(3));
        if (payment && !tx.optString("createdBy").trim().isEmpty()) names.addView(label("سجّلها: " + tx.optString("createdBy"), 11, accent, false), topMargin(3));
        row.addView(names, new LinearLayout.LayoutParams(0, -2, 1)); row.addView(label((payment ? "−" : "+") + money(tx.optDouble("amount")), 16, color, true));
        row.setOnClickListener(v -> { contactId = tx.optLong("contactId"); host.show("contact_detail"); }); content.addView(row, bottomMargin(8));
    }

    private void renderHomeHistory(LinearLayout holder, JSONArray transactions, int total) {
        holder.removeAllViews();
        if (total == 0) {
            emptyCardInto(holder, "لا توجد حركات بعد", "أضف شخصاً ثم سجّل ديناً أو دفعة.");
            return;
        }
        int visibleLimit = Math.min(homeHistoryLimit, total);
        int shown = 0;
        for (int i = 0; i < transactions.length() && shown < visibleLimit; i++) {
            JSONObject tx = transactions.optJSONObject(i);
            if (isReceivableTransaction(tx)) { addTransactionTo(holder, tx, false); shown++; }
        }
        if (visibleLimit < total) {
            String label = homeHistoryLimit <= 5 ? "عرض كل السجل" : "عرض المزيد من الحركات";
            holder.addView(button(label, soft, softForeground(), () -> {
                homeHistoryLimit = Math.min(total, homeHistoryLimit + HOME_HISTORY_BATCH);
                renderHomeHistory(holder, transactions, total);
            }), topMargin(8));
        } else if (homeHistoryLimit > 5) {
            holder.addView(button("عرض أقل", soft, softForeground(), () -> {
                homeHistoryLimit = 5;
                renderHomeHistory(holder, transactions, total);
            }), topMargin(8));
        }
    }

    private void buildContacts() {
        JSONArray people = array(snapshot(), "contacts");
        if (onlyReceivableDebtors) { contactFilter = 1; onlyReceivableDebtors = false; }
        pageHeading("الزبائن", people.length() + " أشخاص مسجلين");
        EditText search = input("ابحث بالاسم أو رقم الهاتف", false); search.setSingleLine(true); search.setText(searchText);
        Drawable glyph = host.getDrawable(R.drawable.ic_search); if (glyph != null) { glyph.setTint(muted); glyph.setBounds(0, 0, dp(20), dp(20)); search.setCompoundDrawablesRelative(null, null, glyph, null); search.setCompoundDrawablePadding(dp(10)); }
        content.addView(search, bottomMargin(12));
        contactChips = new LinearLayout(host); contactChips.setGravity(Gravity.CENTER_VERTICAL); contactChips.setOrientation(LinearLayout.HORIZONTAL); content.addView(contactChips, bottomMargin(10));
        renderContactChips(people);
        TextView sort = button("الترتيب: " + contactSortLabel() + "  ⌄", surface, text, () -> new SadadDialog.Builder(host).setTitle("ترتيب الزبائن").setSingleChoiceItems(new String[]{"حسب الاسم", "الأعلى دينًا", "الأحدث إضافة"}, contactSort, (dialog, which) -> { contactSort = which; dialog.dismiss(); host.refreshCurrentScreen(); }).show());
        sort.setTextSize(13); sort.setMinHeight(dp(44)); sort.setPadding(dp(14), dp(10), dp(14), dp(10)); content.addView(sort, bottomMargin(14));
        contactRows = new LinearLayout(host); contactRows.setOrientation(LinearLayout.VERTICAL); content.addView(contactRows); drawContactRows(contactRows, people, searchText);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) { searchText = value.toString(); drawContactRows(contactRows, array(snapshot(), "contacts"), searchText); }
            @Override public void afterTextChanged(Editable value) { }
        });
    }

    private String contactSortLabel() { return contactSort == 1 ? "الأعلى دينًا" : contactSort == 2 ? "الأحدث إضافة" : "حسب الاسم"; }

    private void renderContactChips(JSONArray people) {
        contactChips.removeAllViews(); int owing = contactsWithBalance(people, "receivable");
        String[] titles = {"الكل (" + people.length() + ")", "عليهم دين (" + owing + ")", "تم تسديده (" + (people.length() - owing) + ")"};
        for (int i = 0; i < 3; i++) {
            final int filter = i; TextView chip = button(titles[i], contactFilter == i ? primary : surface, contactFilter == i ? Color.WHITE : muted, () -> { contactFilter = filter; contactLimit = 60; renderContactChips(array(snapshot(), "contacts")); drawContactRows(contactRows, array(snapshot(), "contacts"), searchText); });
            chip.setTextSize(11); chip.setMinHeight(dp(36)); chip.setPadding(dp(5), dp(6), dp(5), dp(6)); if (i > 0) contactChips.addView(spaceWidth(6)); contactChips.addView(chip, new LinearLayout.LayoutParams(0, dp(38), 1));
        }
    }

    private void drawContactRows(LinearLayout holder, JSONArray contacts, String query) {
        holder.removeAllViews(); String q = westernDigits(query == null ? "" : query.trim()).toLowerCase(Locale.ROOT); int found = 0, shown = 0;
        ArrayList<JSONObject> ordered = new ArrayList<>(); for (int i = 0; i < contacts.length(); i++) if (contacts.optJSONObject(i) != null) ordered.add(contacts.optJSONObject(i));
        Collections.sort(ordered, (a, b) -> { if (contactSort == 1) { int balance = Double.compare(b.optDouble("receivable"), a.optDouble("receivable")); if (balance != 0) return balance; } if (contactSort == 2) { int date = Long.compare(b.optLong("createdAt"), a.optLong("createdAt")); if (date != 0) return date; } return a.optString("name").compareToIgnoreCase(b.optString("name")); });
        for (JSONObject person : ordered) {
            double due = person.optDouble("receivable"); if (contactFilter == 1 && due <= .005 || contactFilter == 2 && due > .005) continue;
            if (!q.isEmpty() && !westernDigits(person.optString("name")).toLowerCase(Locale.ROOT).contains(q) && !westernDigits(person.optString("phone")).contains(q)) continue;
            found++; if (shown >= contactLimit) continue; shown++;
            LinearLayout row = card(host.isDarkTheme() ? surface : Color.rgb(255, 254, 248)); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(14), dp(14), dp(14), dp(14)); row.setElevation(dp(1));
            TextView avatar = label(initials(person.optString("name")), 18, softForeground(), true); avatar.setGravity(Gravity.CENTER); avatar.setBackground(shape(host.isDarkTheme() ? soft : Color.rgb(163, 236, 216), 25)); row.addView(avatar, new LinearLayout.LayoutParams(dp(48), dp(48)));
            LinearLayout names = new LinearLayout(host); names.setOrientation(LinearLayout.VERTICAL); names.setPadding(dp(12), 0, dp(8), 0);
            names.addView(label(person.optString("name"), 15, text, true)); names.addView(label(person.optString("phone").isEmpty() ? "رقم الهاتف غير مسجل" : person.optString("phone"), 12, muted, false), topMargin(4)); row.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
            TextView amount = label(money(due), 17, host.isDarkTheme() ? accent : primary, true); amount.setGravity(Gravity.CENTER); row.addView(amount); row.addView(spaceWidth(12)); row.addView(label("‹", 25, muted, false));
            row.setContentDescription("فتح سجل " + person.optString("name")); row.setOnClickListener(v -> { contactId = person.optLong("id"); host.show("contact_detail"); }); holder.addView(row, bottomMargin(11));
        }
        if (found == 0) emptyCardInto(holder, q.isEmpty() ? "لا يوجد زبائن ضمن هذا التصنيف" : "لا توجد نتائج", q.isEmpty() ? "أضف زبونًا من زر + أو اختر تصنيفًا آخر." : "جرّب اسمًا أو رقم هاتف آخر.");
        else if (found > contactLimit) holder.addView(button("عرض المزيد من الزبائن", soft, softForeground(), () -> { contactLimit += 60; drawContactRows(holder, array(snapshot(), "contacts"), searchText); }), bottomMargin(8));
    }

    private void buildContactDetail() {
        JSONObject person = findContact(contactId);
        if (person == null) { pageHeading("الشخص غير موجود", "قد يكون قد حُذف من السجلات"); return; }
        String contactInfo = person.optString("category", "عميل");
        if (!person.optString("phone").trim().isEmpty()) contactInfo += "  ·  " + person.optString("phone");
        pageHeading(person.optString("name"), contactInfo);
        if (!person.optString("note").trim().isEmpty()) content.addView(label("ملاحظات: " + person.optString("note"), 13, muted, false), bottomMargin(10));
        if (person.optDouble("creditLimit") > 0d) content.addView(label("سقف الائتمان: " + money(person.optDouble("creditLimit")), 12, muted, false), bottomMargin(7));
        LinearLayout summary = card(primary); summary.setPadding(dp(16), dp(16), dp(16), dp(16));
        summary.addView(label("الدين الحالي  " + money(person.optDouble("receivable")), 16, Color.WHITE, true));
        content.addView(summary, bottomMargin(12));
        LinearLayout actions = new LinearLayout(host); actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(button("＋ تسجيل دين", primary, Color.WHITE, () -> { contactId = person.optLong("id"); host.show("debt_form"); }), new LinearLayout.LayoutParams(0, dp(50), 1));
        actions.addView(spaceWidth(8));
        actions.addView(button("تسجيل دفعة", soft, softForeground(), () -> { contactId = person.optLong("id"); host.show("payment_form"); }), new LinearLayout.LayoutParams(0, dp(50), 1));
        content.addView(actions, bottomMargin(9));
        content.addView(button("تعديل بيانات الشخص", soft, softForeground(), () -> { editingContactId = person.optLong("id"); host.show("contact_form"); }), bottomMargin(18));
        JSONArray transactions = array(snapshot(), "transactions"); int personTransactions = 0;
        for (int i = 0; i < transactions.length(); i++) if (isReceivableTransaction(transactions.optJSONObject(i)) && transactions.optJSONObject(i).optLong("contactId") == person.optLong("id")) personTransactions++;
        sectionTitle("سجل حركات هذا الشخص", personTransactions + " حركة، مرتبة من الأحدث");
        if(personHistoryOwner != contactId) { personHistoryOwner=contactId;personHistoryPage=0; }
        int shown = 0, skipped = 0;
        for (int i = 0; i < transactions.length() && shown < 100; i++) {
            JSONObject tx = transactions.optJSONObject(i);
            if (isReceivableTransaction(tx) && tx.optLong("contactId") == person.optLong("id")) { if(skipped++ < personHistoryPage*100)continue; addTransaction(tx, false); shown++; }
        }
        if(personHistoryPage>0)content.addView(button("الحركات الأحدث",soft,softForeground(),()->{personHistoryPage--;host.show("contact_detail");}),bottomMargin(8));
        if((personHistoryPage+1)*100<personTransactions)content.addView(button("الحركات الأقدم",soft,softForeground(),()->{personHistoryPage++;host.show("contact_detail");}),bottomMargin(8));
        if (personTransactions == 0) emptyCard("لا توجد حركات لهذا الشخص", "ستظهر الديون والدفعات هنا عند تسجيلها.");
        content.addView(button("مشاركة كشف PDF لهذا الشخص", soft, softForeground(), () -> shareContactPdf(person.optLong("id"))), topMargin(12));
        if (host.devicePermissionAllowed("deleteRecords")) content.addView(button("حذف السجل", Color.rgb(255, 238, 235), Color.rgb(157, 47, 43), () -> confirmDeleteLedger(person)), topMargin(16));
        if (host.devicePermissionAllowed("deleteContacts")) content.addView(button("حذف الزبون", Color.rgb(255, 228, 223), Color.rgb(157, 47, 43), () -> confirmDeleteContact(person)), topMargin(8));
    }

    private void confirmDeleteContact(JSONObject person) {
        if (!host.devicePermissionAllowed("deleteContacts")) { host.showBrandedMessage("الأدمن قيّد حذف الأشخاص لهذا المستخدم."); return; }
        long id = person.optLong("id");
        String name = person.optString("name", "هذا الزبون");
        if (hasOutstandingDebt(person)) { promptSettleBeforeDelete(person); return; }
        String message = BuildConfig.STANDALONE_MODE
                ? "سيُنقل الزبون وسجل حركاته إلى سلة المحذوفات بعد انتهاء مهلة التراجع. يمكنك استعادته من الإعدادات."
                : "سيُنقل الزبون وسجل حركاته إلى سلة المحذوفات بعد انتهاء مهلة التراجع. ويمكن استعادته من الإعدادات أو من لوحة الأدمن.";
        new SadadDialog.Builder(host).setTitle("تأكيد حذف الزبون").setMessage(message)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("حذف الزبون", (dialog, which) -> host.showUndoBanner(
                        "الزبون " + name,
                        () -> host.showBrandedMessage("تم التراجع عن حذف " + name + "."),
                        () -> {
                            try {
                                if (!host.database.deleteContactToTrash(id)) return;
                                host.markLedgerChanged();
                                if ("contact_detail".equals(host.currentRoute()) && contactId == id) showContactsAtBottom();
                                host.showBrandedMessage("تم نقل " + name + " إلى سلة المحذوفات.");
                            } catch (Exception error) {
                                host.showBrandedMessage(error.getMessage() == null ? "تعذر حذف السجل." : error.getMessage());
                            }
                        })).show();
    }

    private boolean hasOutstandingDebt(JSONObject person) {
        return person.optDouble("receivable") > 0.005 || person.optDouble("payable") > 0.005;
    }

    private void promptSettleBeforeDelete(JSONObject person) {
        double balance = Math.max(person.optDouble("receivable"), person.optDouble("payable"));
        AlertDialog.Builder dialog = new SadadDialog.Builder(host)
                .setTitle("لا يمكن حذف السجل")
                .setMessage("سدّد الدين المتبقي " + money(balance) + " أولاً، ثم يمكنك حذف السجل أو الزبون.")
                .setNegativeButton("إغلاق", null);
        if (person.optDouble("receivable") > 0.005) dialog.setPositiveButton("تسجيل دفعة", (d, w) -> {
            contactId = person.optLong("id"); host.show("payment_form");
        });
        dialog.show();
    }

    private void confirmDeleteLedger(JSONObject person) {
        if (!host.devicePermissionAllowed("deleteRecords")) { host.showBrandedMessage("الأدمن قيّد حذف السجلات لهذا المستخدم."); return; }
        String name = person.optString("name", "هذا الشخص");
        if (hasOutstandingDebt(person)) { promptSettleBeforeDelete(person); return; }
        JSONArray transactions = array(snapshot(), "transactions");
        boolean hasHistory = false;
        for (int i = 0; i < transactions.length(); i++) {
            JSONObject tx = transactions.optJSONObject(i);
            if (tx != null && tx.optLong("contactId") == person.optLong("id")) { hasHistory = true; break; }
        }
        if (!hasHistory) {
            host.showBrandedMessage("لا يوجد سجل حركات لحذفه."); showContactsAtBottom(); return;
        }
        new SadadDialog.Builder(host).setTitle("حذف سجل الحركات")
                .setMessage("سيُحذف سجل حركات " + name + " فقط، وسيبقى الزبون في قائمة الأشخاص. يمكنك استعادة السجل من سلة المحذوفات.")
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("حذف السجل", (dialog, which) -> host.showUndoBanner("سجل " + name,
                        () -> host.showBrandedMessage("تم التراجع عن حذف السجل."), () -> {
                            try {
                                if (!host.database.deleteContactLedgerToTrash(person.optLong("id"))) {
                                    host.showBrandedMessage("لا يوجد سجل حركات لحذفه."); showContactsAtBottom(); return;
                                }
                                host.markLedgerChanged(); showContactsAtBottom();
                                host.showBrandedMessage("تم حذف سجل الحركات ونقله إلى سلة المحذوفات.");
                            } catch (Exception error) {
                                host.showBrandedMessage(error.getMessage() == null ? "تعذر حذف السجل." : error.getMessage());
                            }
                        })).show();
    }

    private void showContactsAtBottom() {
        contactLimit = Math.max(contactLimit, array(snapshot(), "contacts").length());
        scrollContactsToBottom = true;
        host.show("contacts");
    }

    private void shareContactPdf(long onlyContactId) {
        JSONObject person = findContact(onlyContactId);
        if (person == null) { host.showBrandedMessage("لم يتم العثور على سجل الشخص."); return; }
        JSONObject snap = snapshot();
        new SadadDialog.Builder(host).setTitle("اختر صيغة التصدير").setItems(new String[]{"PDF", "Excel (.xlsx)"}, (dialog, which) -> {
            final String store = host.storeName(), owner = host.ownerName(), period = reportRangeLabel;
            final long from = reportStartMillis, to = reportEndMillis;
            final SadadDatabase reportDatabase = host.database;
            String name = person.optString("name", "شخص").replaceAll("[\\/:*?<>|]", "_");
            host.generateReport(() -> which == 0 ? LedgerTablePdf.create(store, owner, period, reportDatabase.getSnapshot(), onlyContactId, from, to)
                    : LedgerTableExcel.create(store, owner, period, reportDatabase.getSnapshot(), onlyContactId, from, to),
                    "كشف-" + name + (which == 0 ? ".pdf" : ".xlsx"), which == 0 ? "application/pdf" : LedgerTableExcel.MIME, true);
        }).show();
    }

    private byte[] createContactStatementPdf(JSONObject person) throws Exception {
        return LedgerTablePdf.create(host.storeName(), host.ownerName(), reportRangeLabel, snapshot(), person.optLong("id"), reportStartMillis, reportEndMillis);
    }

    private final class StatementPdfWriter {
        private static final int WIDTH = 595, HEIGHT = 842, TOP_BAND = 102;
        private final PdfDocument document;
        private final JSONObject person;
        private final int pageWidth, pageHeight, margin, bodyWidth;
        private PdfDocument.Page page;
        private Canvas canvas;
        private int pageNumber;
        private float y;

        StatementPdfWriter(PdfDocument document, JSONObject person, int pageWidth, int pageHeight, int margin) {
            this.document = document; this.person = person; this.pageWidth = pageWidth; this.pageHeight = pageHeight;
            this.margin = margin; this.bodyWidth = pageWidth - margin * 2;
        }

        void startPage() {
            PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(pageWidth, pageHeight, ++pageNumber).create();
            page = document.startPage(info); canvas = page.getCanvas(); canvas.drawColor(Color.WHITE);
            Paint band = new Paint(Paint.ANTI_ALIAS_FLAG); band.setColor(Color.rgb(4, 70, 61));
            canvas.drawRect(0, 0, pageWidth, TOP_BAND, band);
            drawText(canvas, "سدد  |  كشف حساب", margin, 23, bodyWidth, 24, Color.WHITE, true);
            drawText(canvas, "كشف خاص بـ " + person.optString("name"), margin, 54, bodyWidth, 18, Color.rgb(220, 247, 238), false);
            drawText(canvas, "صفحة " + pageNumber, margin, 78, bodyWidth, 14, Color.rgb(220, 247, 238), false);
            y = TOP_BAND + 20;
        }

        void addBlock(String value, float size, int color, boolean bold, int gap, boolean card) {
            TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            paint.setTextSize(size); paint.setColor(color); paint.setTypeface(bold ? Typeface.create("sans-serif", Typeface.BOLD) : Typeface.create("sans-serif", Typeface.NORMAL));
            StaticLayout layout = StaticLayout.Builder.obtain(value, 0, value.length(), paint, bodyWidth - (card ? 22 : 0))
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setTextDirection(TextDirectionHeuristics.RTL).setIncludePad(true).build();
            float cardHeight = layout.getHeight() + (card ? 18 : 0);
            if (y + cardHeight > pageHeight - 40) { document.finishPage(page); startPage(); }
            if (card) {
                Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG); fill.setColor(Color.rgb(241, 249, 246));
                canvas.drawRoundRect(new RectF(margin, y, pageWidth - margin, y + cardHeight), 10, 10, fill);
                Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG); stroke.setStyle(Paint.Style.STROKE); stroke.setStrokeWidth(1); stroke.setColor(Color.rgb(215, 232, 225));
                canvas.drawRoundRect(new RectF(margin, y, pageWidth - margin, y + cardHeight), 10, 10, stroke);
                canvas.save(); canvas.translate(margin + 11, y + 9); layout.draw(canvas); canvas.restore();
            } else {
                canvas.save(); canvas.translate(margin, y); layout.draw(canvas); canvas.restore();
            }
            y += cardHeight + gap;
        }

        private void drawText(Canvas target, String value, int x, int top, int width, int size, int color, boolean bold) {
            TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG); paint.setTextSize(size); paint.setColor(color);
            paint.setTypeface(bold ? Typeface.create("sans-serif", Typeface.BOLD) : Typeface.create("sans-serif", Typeface.NORMAL));
            StaticLayout layout = StaticLayout.Builder.obtain(value, 0, value.length(), paint, width)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setTextDirection(TextDirectionHeuristics.RTL).setIncludePad(true).build();
            target.save(); target.translate(x, top); layout.draw(target); target.restore();
        }

        void finish() { if (page != null) { document.finishPage(page); page = null; } }
    }

    private void buildContactForm() {
        boolean edit = editingContactId > 0;
        JSONObject old = edit ? findContact(editingContactId) : null;
        pageHeading(edit ? "تعديل بيانات الشخص" : "إضافة شخص", "الاسم ورقم الهاتف يساعدان على وضوح الكشوفات");
        EditText name = input("الاسم الكامل", false); name.setSingleLine(true); name.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        name.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT); if (old != null) name.setText(old.optString("name")); addField("اسم الشخص", name);
        EditText phone = input("رقم الهاتف", false); phone.setInputType(3); if (old != null) phone.setText(old.optString("phone")); addField("رقم الهاتف", phone);
        EditText category = input("مثال: عميل، مورد، صديق", false); category.setText(old == null ? "عميل" : old.optString("category", "عميل"));
        EditText creditLimit = input("0.00 — اتركه فارغاً بلا حد", false); creditLimit.setInputType(8194 | 4096);
        if (old != null && old.optDouble("creditLimit") > 0d) creditLimit.setText(String.format(Locale.US, "%.2f", old.optDouble("creditLimit")));
        addField("سقف الائتمان الاختياري", creditLimit);
        EditText note = input("ملاحظات اختيارية", false); note.setMinLines(2); note.setGravity(Gravity.TOP | Gravity.RIGHT); if (old != null) note.setText(old.optString("note")); addField("ملاحظات", note);
        addVoiceDraftButton(note);
        Switch addInitialDebt = null; EditText initialAmount = null, initialDue = null, initialDebtNote = null;
        if (!edit) {
            addInitialDebt = switchRow("تسجيل دين عند إضافة الشخص", false); content.addView(addInitialDebt, bottomMargin(8));
            LinearLayout debtFields = new LinearLayout(host); debtFields.setOrientation(LinearLayout.VERTICAL); debtFields.setPadding(dp(12), dp(8), dp(12), dp(10));
            debtFields.setBackground(shape(surface, 15));
            initialAmount = input("0.00", false); initialAmount.setInputType(8194 | 4096);
            debtFields.addView(label("مبلغ الدين", 13, text, true), topBottom(2, 5)); debtFields.addView(initialAmount, bottomMargin(8));
            initialDue = input("اختيار موعد الاستحقاق من التقويم (اختياري)", false); initialDue.setFocusable(false); initialDue.setClickable(true);
            EditText dueField = initialDue;
            dueField.setOnClickListener(v -> {
                Calendar selected = Calendar.getInstance();
                DatePickerDialog picker = new SadadDatePickerDialog(host, (view, year, month, day) ->
                        dueField.setText(String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day)),
                        selected.get(Calendar.YEAR), selected.get(Calendar.MONTH), selected.get(Calendar.DAY_OF_MONTH));
                picker.show();
            });
            debtFields.addView(label("موعد الاستحقاق", 13, text, true), topBottom(2, 5)); debtFields.addView(dueField, bottomMargin(8));
            initialDebtNote = input("سبب الدين أو تفاصيله", false); initialDebtNote.setMinLines(2); initialDebtNote.setGravity(Gravity.TOP | Gravity.RIGHT);
            debtFields.addView(label("تفاصيل الدين", 13, text, true), topBottom(2, 5)); debtFields.addView(initialDebtNote);
            EditText firstDebtNoteTarget = initialDebtNote;
            LinearLayout firstVoice = new LinearLayout(host); firstVoice.setGravity(Gravity.CENTER); firstVoice.addView(voiceControl(firstDebtNoteTarget), new LinearLayout.LayoutParams(dp(54), dp(54))); debtFields.addView(firstVoice, topMargin(7));
            debtFields.setVisibility(View.GONE); content.addView(debtFields, bottomMargin(8));
            addInitialDebt.setOnCheckedChangeListener((button, checked) -> debtFields.setVisibility(checked ? View.VISIBLE : View.GONE));
        }
        Switch initialDebtSwitch = addInitialDebt; EditText firstDebtAmount = initialAmount, firstDebtDue = initialDue, firstDebtNote = initialDebtNote;
        final boolean[] creditOverride = {false};
        final boolean[] initialDebtReviewed = {false};
        final View[] saveButton = {null};
        saveButton[0] = button(edit ? "حفظ التعديلات" : "حفظ الشخص", primary, Color.WHITE, () -> {
            String personName = name.getText().toString().trim();
            String personPhone = phone.getText().toString().trim();
            boolean withDebt = !edit && initialDebtSwitch != null && initialDebtSwitch.isChecked();
            String amount = withDebt && firstDebtAmount != null ? firstDebtAmount.getText().toString().trim() : "";
            if (withDebt && amount.isEmpty()) { firstDebtAmount.setError("أدخل مبلغ الدين أو أوقف خيار تسجيل الدين."); return; }
            if (withDebt) {
                try { if (new java.math.BigDecimal(westernDigits(amount).replace(',', '.')).signum() <= 0) { firstDebtAmount.setError("يجب أن يكون مبلغ الدين أكبر من صفر."); return; } }
                catch (NumberFormatException invalidAmount) { firstDebtAmount.setError("أدخل مبلغاً صحيحاً."); return; }
            }
            long matchingPhoneContactId = host.database.findContactIdByPhone(personPhone, edit ? editingContactId : -1L);
            if (matchingPhoneContactId > 0L) {
                JSONObject matchingPerson = findContact(matchingPhoneContactId);
                String matchingName = matchingPerson == null ? "الشخص المسجل" : matchingPerson.optString("name", "الشخص المسجل");
                new SadadDialog.Builder(host).setTitle("رقم الهاتف مستخدم مسبقاً")
                        .setMessage("هذا الرقم مسجل باسم " + matchingName + ". سيتم فتح ملفه لمراجعة بياناته، ولن تتم إضافة شخص جديد بهذا الرقم.")
                        .setNegativeButton("مراجعة البيانات", null)
                        .setPositiveButton("فتح سجل الشخص", (d, w) -> {
                            contactId = matchingPhoneContactId;
                            host.show("contact_detail");
                        }).show();
                return;
            }
            if (withDebt && !creditOverride[0] && exceedsLimit(0d, amount, creditLimit.getText().toString())) {
                confirmCreditLimit(0d, amount, creditLimit.getText().toString(), () -> creditOverride[0] = true,
                        () -> saveButton[0].performClick());
                return;
            }
            if (withDebt && !initialDebtReviewed[0]) {
                String dueText = firstDebtDue == null || firstDebtDue.getText().toString().trim().isEmpty() ? "غير محدد" : firstDebtDue.getText().toString();
                new SadadDialog.Builder(host).setTitle("مراجعة الشخص والدين")
                        .setMessage("الشخص: " + personName + "\nالهاتف: " + (personPhone.isEmpty() ? "غير مسجل" : personPhone)
                                + "\nالدين الأول: " + amount + " ₪\nالاستحقاق: " + dueText + "\n\nتأكد من صحة البيانات قبل الحفظ.")
                        .setNegativeButton("مراجعة البيانات", null)
                        .setPositiveButton("تأكيد وحفظ", (d, w) -> { initialDebtReviewed[0] = true; saveButton[0].performClick(); }).show();
                return;
            }
            try {
                JSONObject input = new JSONObject().put("name", personName).put("phone", personPhone)
                        .put("category", category.getText().toString().trim().isEmpty() ? "عميل" : category.getText().toString())
                        .put("note", note.getText().toString()).put("creditLimit", creditLimit.getText().toString()).put("createdBy", host.currentOperatorName());
                if (edit) { input.put("id", editingContactId); host.database.updateContact(input); contactId = editingContactId; }
                else {
                    String debtNote = withDebt && firstDebtNote != null ? firstDebtNote.getText().toString() : "";
                    if (creditOverride[0]) debtNote = appendAuditNote(debtNote, "تم تجاوز سقف الائتمان بموافقة صاحب المتجر");
                    contactId = host.database.saveContactWithInitialDebt(input, amount,
                            withDebt ? firstDebtDue.getText().toString() : "", debtNote).optLong("id");
                }
                host.markLedgerChanged(); host.showBrandedMessage(withDebt ? "تم حفظ الشخص وتسجيل الدين الأول." : "تم حفظ بيانات الشخص."); host.show("contact_detail");
            } catch (Exception e) { String message = e.getMessage() == null ? "تعذر الحفظ" : e.getMessage(); name.setError(message); host.showBrandedMessage(message); }
        });
        content.addView(saveButton[0], topMargin(10));
    }

    private void buildDebtForm() {
        JSONObject person = findContact(contactId);
        if (person == null) { pageHeading("اختر شخصاً أولاً", "أضف جهة اتصال قبل تسجيل الدين"); content.addView(button("إضافة شخص", primary, Color.WHITE, () -> { editingContactId = 0; host.show("contact_form"); })); return; }
        pageHeading("تسجيل دين", "إضافة مبلغ مستحق لك من " + person.optString("name"));
        EditText amount = input("0.00", false); amount.setInputType(8194 | 4096); addField("المبلغ بالشيكل", amount);
        EditText due = input("اختر موعد الاستحقاق من التقويم (اختياري)", false);
        due.setFocusable(false); due.setClickable(true);
        due.setOnClickListener(v -> {
            Calendar selected = Calendar.getInstance();
            try { Date parsed = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(due.getText().toString()); if (parsed != null) selected.setTime(parsed); } catch (Exception ignored) { }
            DatePickerDialog picker = new SadadDatePickerDialog(host, (view, year, month, day) -> due.setText(String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day)),
                    selected.get(Calendar.YEAR), selected.get(Calendar.MONTH), selected.get(Calendar.DAY_OF_MONTH));
            picker.show();
        });
        addField("موعد الاستحقاق", due);
        content.addView(button("إزالة موعد الاستحقاق", soft, softForeground(), () -> due.setText("")), bottomMargin(8));
        EditText note = input("سبب الدين أو تفاصيله", false); note.setMinLines(2); note.setGravity(Gravity.TOP | Gravity.RIGHT); addField("البيان", note); addVoiceDraftButton(note);
        final boolean[] anomalyReviewed = {false};
        final boolean[] creditOverride = {false};
        final boolean[] finalReviewed = {false};
        final View[] saveButton = {null};
        saveButton[0] = button("حفظ الدين", primary, Color.WHITE, () -> {
            try {
                String value = amount.getText().toString().trim();
                double numeric = Double.parseDouble(westernDigits(value).replace(',', '.'));
                if (!anomalyReviewed[0] && numeric >= 5000d) {
                    String reason = "المبلغ مرتفع بشكل غير معتاد.";
                    new SadadDialog.Builder(host).setTitle("مراجعة مبلغ الدين").setMessage(reason + " راجع القيمة قبل تسجيلها.")
                            .setNegativeButton("مراجعة", null)
                            .setPositiveButton("المبلغ صحيح، متابعة", (d, w) -> { anomalyReviewed[0] = true; saveButton[0].performClick(); }).show();
                    return;
                }
                if (!creditOverride[0] && exceedsLimit(person.optDouble("receivable"), value, String.valueOf(person.optDouble("creditLimit")))) {
                    confirmCreditLimit(person.optDouble("receivable"), value, String.valueOf(person.optDouble("creditLimit")),
                            () -> creditOverride[0] = true, () -> saveButton[0].performClick());
                    return;
                }
                if (!finalReviewed[0]) {
                    String dueText = due.getText().toString().trim().isEmpty() ? "غير محدد" : due.getText().toString();
                    new SadadDialog.Builder(host).setTitle("مراجعة تسجيل الدين")
                            .setMessage("الشخص: " + person.optString("name") + "\nرقم الهاتف: " + (person.optString("phone").isEmpty() ? "غير مسجل" : person.optString("phone"))
                                    + "\nالمبلغ: " + value + " ₪\nموعد الاستحقاق: " + dueText
                                    + (note.getText().toString().trim().isEmpty() ? "" : "\nالملاحظة: " + note.getText().toString().trim())
                                    + "\n\nراجع المعلومات قبل التسجيل.")
                            .setNegativeButton("مراجعة البيانات", null)
                            .setPositiveButton("تأكيد تسجيل الدين", (d, w) -> { finalReviewed[0] = true; saveButton[0].performClick(); }).show();
                    return;
                }
                host.database.saveDebt(new JSONObject().put("contactId", person.optLong("id"))
                        .put("direction", "receivable")
                        .put("amount", value).put("dueDate", due.getText().toString()).put("note", appendDebtAudit(note.getText().toString(), creditOverride[0], anomalyReviewed[0]))
                        .put("createdBy", host.currentOperatorName()));
                host.markLedgerChanged(); host.showBrandedMessage("تم تسجيل الدين وتحديث الرصيد."); host.show("contact_detail");
            } catch (Exception e) {
                String message = e.getMessage() == null ? "تحقق من المبلغ" : e.getMessage();
                amount.setError(message); host.showBrandedMessage(message);
            }
        });
        content.addView(saveButton[0], topMargin(10));
    }

    private boolean exceedsLimit(double current, String amountText, String limitText) {
        try {
            java.math.BigDecimal limit = new java.math.BigDecimal(westernDigits(limitText == null ? "" : limitText.trim()).replace(',', '.'));
            java.math.BigDecimal total = java.math.BigDecimal.valueOf(current).add(new java.math.BigDecimal(westernDigits(amountText.trim()).replace(',', '.')));
            return limit.signum() > 0 && total.compareTo(limit) > 0;
        } catch (Exception invalidLimit) { return false; }
    }

    private void confirmCreditLimit(double current, String amount, String limitText, Runnable approved, Runnable proceed) {
        try {
            java.math.BigDecimal limit = new java.math.BigDecimal(westernDigits(limitText.trim()).replace(',', '.'));
            java.math.BigDecimal total = java.math.BigDecimal.valueOf(current).add(new java.math.BigDecimal(westernDigits(amount.trim()).replace(',', '.')));
            new SadadDialog.Builder(host).setTitle("تجاوز سقف الائتمان")
                    .setMessage("سيصبح إجمالي الدين " + money(total.doubleValue()) + "، أعلى من السقف " + money(limit.doubleValue()) + ". هل تريد المتابعة بموافقة صاحب المتجر؟")
                    .setNegativeButton("إلغاء", null).setPositiveButton("متابعة بموافقة", (d, w) -> { approved.run(); proceed.run(); }).show();
        } catch (Exception invalidValue) { proceed.run(); }
    }

    private String appendDebtAudit(String original, boolean creditApproved, boolean anomalyReviewed) {
        String note = original == null ? "" : original.trim();
        if (creditApproved) note = appendAuditNote(note, "تم تجاوز سقف الائتمان بموافقة صاحب المتجر");
        if (anomalyReviewed) note = appendAuditNote(note, "تمت مراجعة تنبيه حركة غير معتادة");
        return note;
    }

    private static String appendAuditNote(String original, String extra) {
        String note = original == null ? "" : original.trim();
        return note.isEmpty() ? extra : note + " · " + extra;
    }

    private void addVoiceDraftButton(EditText target) {
        LinearLayout row = new LinearLayout(host); row.setGravity(Gravity.CENTER); row.setPadding(0, dp(4), 0, dp(7));
        row.addView(voiceControl(target), new LinearLayout.LayoutParams(dp(54), dp(54))); content.addView(row, bottomMargin(6));
        TextView hint = label("راجع النص بعد الإملاء وعدّله قبل الحفظ.", 11, muted, false); hint.setGravity(Gravity.CENTER); content.addView(hint, bottomMargin(10));
    }

    private ImageButton voiceControl(EditText target) {
        ImageButton mic = new ImageButton(host); mic.setImageResource(R.drawable.ic_mic); mic.setColorFilter(primary); mic.setPadding(dp(13), dp(13), dp(13), dp(13)); mic.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(accent), shape(soft, 27), null)); mic.setContentDescription("إملاء صوتي كمسودة"); mic.setOnClickListener(v -> host.startVoiceDraft(target)); return mic;
    }

    private void buildPaymentForm() {
        JSONObject person = findContact(contactId);
        pageHeading("تسجيل دفعة", person == null ? "اختر شخصاً أولاً" : "دفعة من إجمالي حساب " + person.optString("name"));
        if (!host.devicePermissionAllowed("registerPayments")) { emptyCard("تسجيل الدفعات مقيّد", "اطلب من صاحب المتجر أو الأدمن تفعيل هذه الصلاحية لهذا الجهاز."); return; }
        if (person == null || person.optDouble("receivable") <= 0) { emptyCard("لا يوجد رصيد مفتوح", "سجّل ديناً لهذا الشخص قبل تسجيل الدفعة."); return; }
        content.addView(label("الرصيد الإجمالي الحالي: " + money(person.optDouble("receivable")) + "\nتُخصم الدفعة من إجمالي الحساب وتوزّع على الديون الأقدم أولاً.", 14, muted, false), bottomMargin(13));
        EditText amount = input("0.00", false); amount.setInputType(8194 | 4096); addField("مبلغ الدفعة بالشيكل", amount);
        Spinner method = spinner(new String[]{"نقدي", "بنكي", "محفظة جوال", "محفظة بال بي"}); addField("طريقة الدفع", method);
        EditText note = input("ملاحظة عن هذه الدفعة (اختياري)", false); note.setMinLines(2); note.setGravity(Gravity.TOP | Gravity.RIGHT);
        addField("ملاحظة الدفعة", note);
        addVoiceDraftButton(note);
        final boolean[] finalReviewed = {false};
        final View[] saveButton = {null};
        saveButton[0] = button("مراجعة وحفظ الدفعة", primary, Color.WHITE, () -> {
            String[] methods = {"cash", "bank", "wallet_jawwal", "wallet_palpay"};
            try {
                String value = amount.getText().toString().trim();
                double numeric = Double.parseDouble(westernDigits(value).replace(',', '.'));
                String methodName = methods[method.getSelectedItemPosition()];
                if (!finalReviewed[0]) {
                    new SadadDialog.Builder(host).setTitle("مراجعة تسجيل الدفعة")
                            .setMessage("الشخص: " + person.optString("name") + "\nرقم الهاتف: " + (person.optString("phone").isEmpty() ? "غير مسجل" : person.optString("phone"))
                                    + "\nالمبلغ: " + money(numeric) + "\nطريقة الدفع: " + method.getSelectedItem().toString()
                                    + (note.getText().toString().trim().isEmpty() ? "" : "\nالملاحظة: " + note.getText().toString().trim())
                                    + "\n\nتحقق من المبلغ والاسم قبل تسجيل الدفعة.")
                            .setNegativeButton("مراجعة البيانات", null)
                            .setPositiveButton("تأكيد تسجيل الدفعة", (d, w) -> { finalReviewed[0] = true; saveButton[0].performClick(); }).show();
                    return;
                }
                JSONObject receipt = host.database.savePayment(new JSONObject().put("contactId", person.optLong("id")).put("amount", value)
                        .put("method", methodName).put("note", note.getText().toString()).put("createdBy", host.currentOperatorName()));
                host.markLedgerChanged();
                new SadadDialog.Builder(host).setTitle("تم تسجيل الدفعة")
                        .setMessage("تم خصم " + money(receipt.optDouble("amount")) + " من إجمالي حساب " + person.optString("name") + ". هل تريد طباعة سند قبض؟")
                        .setNegativeButton("لاحقاً", (d, w) -> host.show("contact_detail"))
                        .setPositiveButton("طباعة سند قبض", (d, w) -> {
                            try { host.printPdf(createVoucherPdf("سند قبض", person.optString("name"), person.optString("phone"),
                                    receipt.optDouble("amount"), method.getSelectedItem().toString(), note.getText().toString(), receipt.optLong("createdAt")),
                                    "سند قبض " + person.optString("name")); }
                            catch (Exception error) { host.showBrandedMessage("تعذر تجهيز سند القبض للطباعة."); }
                            host.show("contact_detail");
                        }).show();
            } catch (Exception e) {
                String message = e.getMessage() == null ? "تحقق من المبلغ" : e.getMessage();
                amount.setError(message); host.showBrandedMessage(message);
            }
        });
        content.addView(saveButton[0], topMargin(10));
    }

    private void buildHistory() {
        JSONObject snapshot = snapshot(); JSONArray transactions;try{transactions=host.database.historyActivities(reportStartMillis,reportEndMillis);}catch(JSONException e){host.showBrandedMessage("تعذر فتح السجل.");return;}
        LinkedHashMap<Long, JSONObject> latest = new LinkedHashMap<>();
        for (int i = 0; i < transactions.length(); i++) {
            JSONObject tx = transactions.optJSONObject(i); if (!isReceivableTransaction(tx)) continue;
            if (!inReportRange(tx.optLong("createdAt"))) continue;
            long id = tx.optLong("contactId");
            if (!latest.containsKey(id)) latest.put(id, tx);
        }
        pageHeading("سجل الأشخاص", latest.size() + " شخصاً لديهم حركات ضمن " + reportRangeLabel);
        addDateFilterControls();
        content.addView(button("فتح التقارير والكشوفات", primary, Color.WHITE, () -> host.show("reports")), bottomMargin(12));
        if (!historyOpened) return;
        if (latest.isEmpty()) emptyCard("السجل فارغ", "ستظهر الديون والدفعات هنا بعد تسجيلها.");
        else {
            int shown = 0;
            for (Map.Entry<Long, JSONObject> entry : latest.entrySet()) {
                if (shown >= historyLimit) break;
                addHistoryPersonSummary(entry.getKey(), entry.getValue()); shown++;
            }
            if (latest.size() > historyLimit) content.addView(button("عرض المزيد من الأشخاص", soft, softForeground(), () -> { historyLimit += 100; show("history_results"); }), bottomMargin(10));
        }
    }

    private void addHistoryPersonSummary(long id, JSONObject latest) {
        JSONObject person = findContact(id); String name = person == null ? latest.optString("contactName", "شخص") : person.optString("name", "شخص");
        double remaining = person == null ? 0d : person.optDouble("receivable"); JSONObject lastDebt = null;
        try{lastDebt=host.database.latestPersonDebt(id);}catch(JSONException ignored){}
        Runnable open = () -> { contactId = id; host.show("contact_detail"); };
        LinearLayout row = card(surface); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(16), dp(15), dp(16), dp(15));
        LinearLayout names = new LinearLayout(host); names.setOrientation(LinearLayout.VERTICAL); TextView title = label(name, 16, text, true); title.setContentDescription("فتح سجل " + name); title.setOnClickListener(v -> open.run()); names.addView(title);
        names.addView(label("المتبقي: " + money(remaining), 13, muted, false), topMargin(5)); row.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout amount = new LinearLayout(host); amount.setOrientation(LinearLayout.VERTICAL); amount.addView(label("آخر دين", 12, muted, false)); amount.addView(label(lastDebt == null ? "لا يوجد" : money(lastDebt.optDouble("amount")), 17, host.isDarkTheme() ? accent : primary, true), topMargin(4)); row.addView(amount);
        row.setOnClickListener(v -> open.run()); content.addView(row, bottomMargin(10));
    }

    private void addTransaction(JSONObject tx, boolean allowOpen) {
        addTransactionTo(content, tx, allowOpen);
    }

    private void addTransactionTo(LinearLayout holder, JSONObject tx, boolean allowOpen) {
        if (tx == null) return;
        LinearLayout item = card(surface); item.setPadding(dp(14), dp(12), dp(14), dp(12)); item.setOrientation(LinearLayout.HORIZONTAL); item.setGravity(Gravity.CENTER_VERTICAL);
        item.setElevation(dp(2));
        boolean payment = "payment".equals(tx.optString("kind"));
        TextView mark = label(payment ? "↓" : "＋", 20, payment ? accent : softForeground(), true); mark.setGravity(Gravity.CENTER); mark.setBackground(shape(soft, 22)); item.addView(mark, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout details = new LinearLayout(host); details.setOrientation(LinearLayout.VERTICAL); details.setPadding(dp(11), 0, 0, 0);
        details.addView(label(tx.optString("contactName", "شخص"), 15, text, true));
        String kind = payment ? "دفعة مسجلة" : "دين مسجل";
        details.addView(label(kind + "  •  " + dayAndTime(tx.optLong("createdAt")), 12, muted, false), topMargin(4));
        if (!tx.optString("createdBy").trim().isEmpty()) details.addView(label("سجّلها: " + tx.optString("createdBy"), 11, accent, false), topMargin(2));
        if (!payment) {
            details.addView(label("الاستحقاق: " + formatDueDate(tx.optString("dueDate")), 12, accent, true), topMargin(3));

        }
        if (!tx.optString("note").isEmpty()) details.addView(label(tx.optString("note"), 12, muted, false), topMargin(3));
        item.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
        TextView amount = label((payment ? "−" : "+") + money(tx.optDouble("amount")), 14, payment ? accent : primary, true); amount.setGravity(Gravity.CENTER);
        item.addView(amount); if (allowOpen) item.setOnClickListener(v -> { contactId = tx.optLong("contactId"); host.show("contact_detail"); });
        holder.addView(item, bottomMargin(8));
    }

    private void buildReports() {
        JSONObject snapshot = snapshot(); JSONObject totals = snapshot.optJSONObject("totals"); if (totals == null) totals = new JSONObject();
        JSONArray contacts = array(snapshot, "contacts"), transactions = array(snapshot, "transactions");
        pageHeading("التقارير والكشوفات", "اختر المدة لعرض السجل والكشوفات");
        addDateFilterControls();
        reportContactIds = new HashSet<>(); double debtTotal = 0d, paymentTotal = 0d; int debtCount = 0, paymentCount = 0;
        for (int i = 0; i < transactions.length(); i++) {
            JSONObject tx = transactions.optJSONObject(i); if (tx == null || !isReceivableTransaction(tx) || !inReportRange(tx.optLong("createdAt"))) continue;
            reportContactIds.add(tx.optLong("contactId"));
            if ("payment".equals(tx.optString("kind"))) { paymentCount++; paymentTotal += tx.optDouble("amount"); }
            else { debtCount++; debtTotal += tx.optDouble("amount"); }
        }
        content.addView(label("الفترة المحددة: " + reportRangeLabel, 12, accent, true), bottomMargin(10));
        content.addView(metric("الأشخاص ذوو الحركة", String.valueOf(reportContactIds.size()), "ضمن الفترة المحددة", accent), bottomMargin(10));
        content.addView(metric("الديون المسجلة بالفترة", money(debtTotal), debtCount + " عملية دين", primary), bottomMargin(10));
        content.addView(metric("الدفعات المسجلة بالفترة", money(paymentTotal), paymentCount + " حركة سداد", accent), bottomMargin(16));
        content.addView(label("الرصيد المفتوح الآن لكل المتجر: " + money(totals.optDouble("receivable")), 13, muted, true), bottomMargin(12));
        sectionTitle("التصدير", "احفظ كشفاً كاملاً ومنسقاً بصيغة PDF.");
        content.addView(button("حفظ كشف السجلات الكامل PDF", primary, Color.WHITE, this::shareCompletePdf), bottomMargin(9));
        content.addView(button("طباعة سند صرف", soft, softForeground(), this::promptPrintDisbursement), bottomMargin(9));
        content.addView(button("عرض سجل الحركات", soft, softForeground(), () -> host.show("history")), bottomMargin(16));
        sectionTitle("كشوفات الأشخاص", "ابحث بالاسم أو رقم الهاتف وافتح كشف الشخص بصيغة PDF.");
        LinearLayout searchRow = new LinearLayout(host); searchRow.setGravity(Gravity.CENTER_VERTICAL); searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setPadding(dp(11), 0, dp(11), 0); searchRow.setBackground(shape(surface, 13));
        searchRow.addView(iconView(R.drawable.ic_search, accent), new LinearLayout.LayoutParams(dp(22), dp(22)));
        EditText search = input("ابحث بالاسم أو رقم الهاتف", false); search.setSingleLine(true); search.setText(reportSearchText);
        searchRow.addView(search, new LinearLayout.LayoutParams(0, -2, 1)); content.addView(searchRow, bottomMargin(10));
        LinearLayout results = new LinearLayout(host); results.setOrientation(LinearLayout.VERTICAL); content.addView(results);
        renderReportPeople(results, contacts);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { reportSearchText = s.toString(); renderReportPeople(results, contacts); }
            @Override public void afterTextChanged(Editable s) { }
        });
    }

    private void addDateFilterControls() {
        sectionTitle("مدة السجل", "اختر المدة لفتح السجل");
        content.addView(button("يومي", "اليوم".equals(reportRangeLabel) ? primary : soft, "اليوم".equals(reportRangeLabel) ? Color.WHITE : softForeground(), () -> selectReportPreset("daily")), bottomMargin(8));
        content.addView(button("شهري", "هذا الشهر".equals(reportRangeLabel) ? primary : soft, "هذا الشهر".equals(reportRangeLabel) ? Color.WHITE : softForeground(), () -> selectReportPreset("monthly")), bottomMargin(8));
        content.addView(button("سنوي", "هذه السنة".equals(reportRangeLabel) ? primary : soft, "هذه السنة".equals(reportRangeLabel) ? Color.WHITE : softForeground(), () -> selectReportPreset("yearly")), bottomMargin(8));
        content.addView(button("فترة مخصصة", soft, softForeground(), this::selectCustomReportPeriod), bottomMargin(8));
        content.addView(button("عرض كل المدة", "كل المدة".equals(reportRangeLabel) ? primary : soft, "كل المدة".equals(reportRangeLabel) ? Color.WHITE : softForeground(), () -> {
            reportStartMillis = Long.MIN_VALUE; reportEndMillis = Long.MAX_VALUE; reportRangeLabel = "كل المدة"; openFilteredHistory();
        }), bottomMargin(12));
    }

    private void openFilteredHistory() {
        if ("settings".equals(route) || "reports".equals(route) || "history_results".equals(route)) host.refreshCurrentScreen();
        else host.show("history_results");
    }

    private void selectReportPreset(String preset) { setReportPreset(preset); openFilteredHistory(); }

    private void setReportPreset(String preset) {
        Calendar start = Calendar.getInstance();
        start.set(Calendar.HOUR_OF_DAY, 0); start.set(Calendar.MINUTE, 0); start.set(Calendar.SECOND, 0); start.set(Calendar.MILLISECOND, 0);
        Calendar end = (Calendar) start.clone();
        if ("daily".equals(preset)) { end.add(Calendar.DAY_OF_MONTH, 1); reportRangeLabel = "اليوم"; }
        else if ("monthly".equals(preset)) { start.set(Calendar.DAY_OF_MONTH, 1); end = (Calendar) start.clone(); end.add(Calendar.MONTH, 1); reportRangeLabel = "هذا الشهر"; }
        else { start.set(Calendar.DAY_OF_YEAR, 1); end = (Calendar) start.clone(); end.add(Calendar.YEAR, 1); reportRangeLabel = "هذه السنة"; }
        reportStartMillis = start.getTimeInMillis(); reportEndMillis = end.getTimeInMillis() - 1L;
    }

    private void selectCustomReportPeriod() {
        Calendar now = Calendar.getInstance();
        new SadadDatePickerDialog(host, (firstPicker, year, month, day) -> {
            Calendar start = Calendar.getInstance(); start.clear(); start.set(year, month, day, 0, 0, 0);
            new SadadDatePickerDialog(host, (secondPicker, endYear, endMonth, endDay) -> {
                Calendar end = Calendar.getInstance(); end.clear(); end.set(endYear, endMonth, endDay, 23, 59, 59); end.set(Calendar.MILLISECOND, 999);
                if (end.before(start)) { host.showBrandedMessage("تاريخ النهاية يجب أن يكون بعد تاريخ البداية."); return; }
                reportStartMillis = start.getTimeInMillis(); reportEndMillis = end.getTimeInMillis();
                reportRangeLabel = "من " + formatDateOnly(reportStartMillis) + " إلى " + formatDateOnly(reportEndMillis);
                openFilteredHistory();
            }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show();
        }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show();
    }

    private boolean inReportRange(long timestamp) { return timestamp >= reportStartMillis && timestamp <= reportEndMillis; }

    private void promptPrintDisbursement() {
        LinearLayout form = new LinearLayout(host); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(20), dp(12), dp(20), dp(8));
        EditText recipient = new EditText(host); recipient.setSingleLine(true); recipient.setHint("اسم المستلم");
        EditText phone = new EditText(host); phone.setSingleLine(true); phone.setHint("رقم الهاتف (اختياري)"); phone.setInputType(3);
        EditText amount = new EditText(host); amount.setSingleLine(true); amount.setHint("المبلغ بالشيكل"); amount.setInputType(8194 | 4096);
        EditText reason = new EditText(host); reason.setMinLines(2); reason.setHint("سبب الصرف");
        form.addView(label("سند صرف للطباعة. لا يسجل مصروفاً في دفتر الديون.", 12, muted, false), bottomMargin(7));
        form.addView(recipient); form.addView(phone); form.addView(amount); form.addView(reason);
        AlertDialog dialog = new SadadDialog.Builder(host).setTitle("إعداد سند صرف")
                .setView(form).setNegativeButton("إلغاء", null).setPositiveButton("طباعة السند", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (recipient.getText().toString().trim().isEmpty()) { recipient.setError("أدخل اسم المستلم."); return; }
            try {
                double value = Double.parseDouble(westernDigits(amount.getText().toString().trim()).replace(',', '.'));
                if (value <= 0) { amount.setError("أدخل مبلغاً أكبر من صفر."); return; }
                host.printPdf(createVoucherPdf("سند صرف", recipient.getText().toString(), phone.getText().toString(), value,
                        "نقداً", reason.getText().toString(), System.currentTimeMillis()), "سند صرف " + recipient.getText().toString().trim());
                dialog.dismiss();
            } catch (Exception invalid) { amount.setError("أدخل مبلغاً صحيحاً."); }
        }));
        dialog.show();
    }

    private byte[] createVoucherPdf(String title, String personName, String phone, double amount,
                                    String method, String note, long createdAt) throws Exception {
        PdfDocument document = new PdfDocument();
        JSONObject cover = new JSONObject().put("name", personName == null ? "" : personName);
        StatementPdfWriter writer = new StatementPdfWriter(document, cover, 595, 842, 38);
        writer.startPage();
        writer.addBlock(title, 24, Color.rgb(6, 75, 66), true, 10, false);
        writer.addBlock("رقم السند: " + Long.toString(Math.abs(createdAt), 36).toUpperCase(Locale.ROOT), 12, Color.rgb(75, 91, 85), false, 5, true);
        writer.addBlock("المتجر: " + (host.storeName().trim().isEmpty() ? "سدد" : host.storeName()), 14, Color.rgb(25, 43, 38), true, 5, true);
        if (!host.ownerName().trim().isEmpty()) writer.addBlock("صاحب المتجر: " + host.ownerName(), 12, Color.rgb(75, 91, 85), false, 5, true);
        writer.addBlock("الاسم: " + (personName == null || personName.trim().isEmpty() ? "غير محدد" : personName.trim()), 15, Color.rgb(25, 43, 38), true, 5, true);
        if (phone != null && !phone.trim().isEmpty()) writer.addBlock("رقم الهاتف: " + phone.trim(), 12, Color.rgb(75, 91, 85), false, 5, true);
        writer.addBlock("المبلغ: " + money(amount) + " ₪", 20, Color.rgb(6, 75, 66), true, 7, true);
        writer.addBlock("طريقة الدفع: " + (method == null ? "نقداً" : method), 12, Color.rgb(75, 91, 85), false, 5, true);
        writer.addBlock("التاريخ والساعة: " + dayAndTime(createdAt), 12, Color.rgb(75, 91, 85), false, 5, true);
        if (note != null && !note.trim().isEmpty()) writer.addBlock("البيان: " + note.trim(), 12, Color.rgb(35, 50, 45), false, 8, true);
        writer.addBlock("توقيع المستلم: ____________________", 13, Color.rgb(35, 50, 45), false, 10, true);
        writer.addBlock("توقيع صاحب المتجر: ____________________", 13, Color.rgb(35, 50, 45), false, 10, true);
        writer.finish(); ByteArrayOutputStream output = new ByteArrayOutputStream(); document.writeTo(output); document.close(); return output.toByteArray();
    }

    private void renderReportPeople(LinearLayout results, JSONArray contacts) {
        results.removeAllViews();
        String query = reportSearchText == null ? "" : reportSearchText.trim().toLowerCase(Locale.ROOT);
        int shown = 0, matched = 0;
        for (int i = 0; i < contacts.length(); i++) {
            JSONObject person = contacts.optJSONObject(i); if (person == null) continue;
            if (reportRangeLabel != null && !"كل المدة".equals(reportRangeLabel) && !reportContactIds.contains(person.optLong("id"))) continue;
            String haystack = (person.optString("name") + " " + person.optString("phone")).toLowerCase(Locale.ROOT);
            if (!query.isEmpty() && !haystack.contains(query)) continue;
            matched++;
            if (query.isEmpty() && shown >= reportContactLimit) continue;
            long id = person.optLong("id");
            results.addView(button("كشف PDF: " + person.optString("name") + "  ·  " + money(person.optDouble("receivable")), soft, softForeground(),
                    () -> shareContactPdf(id)), bottomMargin(7));
            shown++;
        }
        if (matched == 0) emptyCardInto(results, query.isEmpty() ? "لا توجد أشخاص بعد" : "لا توجد نتائج", query.isEmpty() ? "أضف شخصاً لتظهر كشوفاته هنا." : "ابحث بجزء من الاسم أو رقم الهاتف.");
        else if (query.isEmpty() && matched > reportContactLimit) results.addView(button("عرض المزيد من الأشخاص", soft, softForeground(), () -> { reportContactLimit += 60; renderReportPeople(results, contacts); }), bottomMargin(10));
    }

    private void shareCompletePdf() { exportCompletePdf(true); }

    private void exportCompletePdf(boolean share) {
        JSONObject snap = snapshot();
        if (array(snap, "contacts").length() == 0) { host.showBrandedMessage("أضف أشخاصًا وحركات قبل إنشاء كشف السجلات."); return; }
        new SadadDialog.Builder(host).setTitle("اختر صيغة التصدير").setItems(new String[]{"PDF", "Excel (.xlsx)"}, (dialog, which) -> {
            final String store = host.storeName(), owner = host.ownerName(), period = reportRangeLabel;
            final long from = reportStartMillis, to = reportEndMillis;
            final SadadDatabase reportDatabase = host.database;
            host.generateReport(() -> which == 0 ? LedgerTablePdf.create(store, owner, period, reportDatabase.getSnapshot(), 0L, from, to)
                    : LedgerTableExcel.create(store, owner, period, reportDatabase.getSnapshot(), 0L, from, to),
                    "كشف-سدد-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date()) + (which == 0 ? ".pdf" : ".xlsx"),
                    which == 0 ? "application/pdf" : LedgerTableExcel.MIME, share);
        }).show();
    }

    private byte[] createCompleteStatementPdf(JSONObject snap) throws Exception {
        return LedgerTablePdf.create(host.storeName(), host.ownerName(), reportRangeLabel, snap, 0L, reportStartMillis, reportEndMillis);
    }

    private void buildNotifications() {
        pageHeading("الإشعارات", "مواعيد الاستحقاق قبل يوم وفي يومها");
        LinearLayout notificationList = content;
        JSONArray alerts = DueReminderManager.inbox(host);
        DueReminderManager.markAllRead(host);
        if (alerts.length() == 0) { emptyCard("لا توجد إشعارات استحقاق حالياً", "سيظهر التنبيه هنا عندما يقترب موعد دين محفوظ."); return; }
        for (int i = 0; i < alerts.length(); i++) {
            JSONObject alert = alerts.optJSONObject(i); if (alert == null) continue;
            boolean today = "today".equals(alert.optString("stage"));
            String storeName = alert.optString("storeName", host.storeName()).trim();
            if (storeName.isEmpty()) storeName = "المتجر";
            String title = storeName + " · " + (today ? "يستحق اليوم: " : "يستحق غداً: ");
            String detail = alert.optString("contactName", "شخص") + "  ·  " + money(alert.optDouble("amount")) +
                    "\nموعد الاستحقاق: " + formatDueDate(alert.optString("dueDate")) + " · المتجر: " + storeName;
            LinearLayout card = card(surface); card.setPadding(dp(14), dp(13), dp(14), dp(13)); card.setOnClickListener(v -> {
                contactId = alert.optLong("contactId"); host.show("contact_detail");
            });
            card.setOrientation(LinearLayout.HORIZONTAL); card.setGravity(Gravity.CENTER_VERTICAL); card.setElevation(dp(4));
            LinearLayout copy = new LinearLayout(host); copy.setOrientation(LinearLayout.VERTICAL);
            copy.addView(label(title + alert.optString("contactName", "شخص"), 15, today ? warn : primary, true));
            copy.addView(label(detail, 13, text, false), topMargin(5));
            card.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout controls = new LinearLayout(host); controls.setGravity(Gravity.CENTER_VERTICAL); controls.setOrientation(LinearLayout.HORIZONTAL);
            TextView done = label("", 13, Color.rgb(6, 75, 66), true); done.setGravity(Gravity.CENTER);
            done.setContentDescription("تحديد الإشعار كمكتمل"); done.setBackground(notificationCheckbox(false));
            controls.addView(done, new LinearLayout.LayoutParams(dp(23), dp(23)));
            card.addView(controls);
            boolean[] selected = {false};
            done.setOnClickListener(v -> {
                if (selected[0]) return;
                selected[0] = true; done.setText("✓"); done.setBackground(notificationCheckbox(true));
                done.animate().scaleX(1.08f).scaleY(1.08f).setDuration(130L).withEndAction(() ->
                        done.animate().scaleX(1f).scaleY(1f).setDuration(150L).start()).start();
                String removedName = alert.optString("contactName", "الزبون");
                host.showUndoBanner("إشعار " + removedName, () -> {
                    selected[0] = false; done.setText(""); done.setBackground(notificationCheckbox(false));
                }, () -> {
                    JSONObject removed = DueReminderManager.dismiss(host, alert.optString("id"));
                    if (removed == null) return;
                    try { host.database.addTrashItem("notification", removedName, removed); }
                    catch (Exception error) { host.showBrandedMessage("تعذر حفظ الإشعار في سلة المحذوفات."); }
                    ViewParent parent = card.getParent();
                    if (parent instanceof ViewGroup) {
                        card.animate().alpha(0f).setDuration(170L).withEndAction(() -> ((ViewGroup) parent).removeView(card)).start();
                    }
                    if (DueReminderManager.inbox(host).length() == 0)
                        emptyCardInto(notificationList, "لا توجد إشعارات استحقاق حالياً", "سيظهر هنا التنبيه عندما يقترب موعد دين محفوظ.");
                    host.showBrandedMessage("تم حذف الإشعار ونقله إلى سلة المحذوفات.");
                });
            });
            content.addView(card, bottomMargin(8));
        }
    }

    private GradientDrawable notificationCheckbox(boolean checked) {
        GradientDrawable box = new GradientDrawable(); box.setColor(Color.WHITE); box.setCornerRadius(dp(5));
        box.setStroke(dp(1), Color.rgb(6, 75, 66)); return box;
    }

    private void buildSettings() {
        if (!settingsPeriodInitialized) { settingsPeriodInitialized = true; setReportPreset("monthly"); }
        TextView back = backArrow(() -> host.show("home")); content.addView(back, new LinearLayout.LayoutParams(dp(38), dp(38)));
        LinearLayout profile = card(primary); profile.setOrientation(LinearLayout.HORIZONTAL); profile.setGravity(Gravity.CENTER_VERTICAL); profile.setPadding(dp(18), dp(22), dp(18), dp(22));
        GradientDrawable profileFill = new GradientDrawable(GradientDrawable.Orientation.TR_BL, new int[]{primary, Color.rgb(2, 48, 43)}); profileFill.setCornerRadius(dp(18)); profile.setBackground(profileFill); profile.setElevation(dp(4));
        LinearLayout details = new LinearLayout(host); details.setOrientation(LinearLayout.VERTICAL);
        LinearLayout heading = new LinearLayout(host); heading.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        heading.addView(label("بيانات المتجر", 20, Color.WHITE, true), new LinearLayout.LayoutParams(-2, -2)); heading.addView(spaceWidth(6));
        TextView edit = button("✎", Color.argb(45, 255, 255, 255), Color.WHITE, this::promptStoreProfile); edit.setPadding(0, 0, 0, 0); edit.setMinHeight(0); edit.setContentDescription("تعديل اسم المتجر وصاحب المتجر"); heading.addView(edit, new LinearLayout.LayoutParams(dp(40), dp(40))); details.addView(heading);
        TextView store = label("اسم المتجر: " + (host.storeName().isEmpty() ? "متجري" : host.storeName()), 18, Color.WHITE, true); store.setContentDescription("تعديل بيانات المتجر"); store.setOnClickListener(v -> promptStoreProfile()); details.addView(store, topMargin(12));
        TextView owner = label("اسم صاحب المتجر: " + (host.ownerName().isEmpty() ? "غير مسجل" : host.ownerName()), 14, Color.rgb(198, 240, 224), false); owner.setOnClickListener(v -> promptStoreProfile()); details.addView(owner, topMargin(7));
        profile.addView(details, new LinearLayout.LayoutParams(0, -2, 1)); profile.addView(spaceWidth(12));
        ImageView photo = portrait(true, 86); photo.setContentDescription("معاينة وتعديل صورة المتجر"); photo.setOnClickListener(v -> host.editProfilePhoto()); profile.addView(photo, new LinearLayout.LayoutParams(dp(86), dp(86))); content.addView(profile, topBottom(18, 8));
        divider();
        LinearLayout exportHeading = new LinearLayout(host); exportHeading.setGravity(Gravity.CENTER_VERTICAL);
        exportHeading.addView(label("البيانات والتصدير", 20, text, true), new LinearLayout.LayoutParams(0, -2, 1));
        String[] periods = {"يومي", "شهري", "سنوي", "فترة مخصصة", "كل المدة"}; Spinner period = spinner(periods);
        period.setContentDescription("مدة تصدير كشف السجلات");
        int selected = "اليوم".equals(reportRangeLabel) ? 0 : "هذا الشهر".equals(reportRangeLabel) ? 1 : "هذه السنة".equals(reportRangeLabel) ? 2 : "كل المدة".equals(reportRangeLabel) ? 4 : 3;
        period.setSelection(selected); exportHeading.addView(period, new LinearLayout.LayoutParams(dp(128), dp(48))); content.addView(exportHeading, bottomMargin(12));
        period.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            boolean first = true;
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (first) { first = false; return; }
                if (position == 3) { selectCustomReportPeriod(); return; }
                if (position == 4) { reportStartMillis = Long.MIN_VALUE; reportEndMillis = Long.MAX_VALUE; reportRangeLabel = "كل المدة"; return; }
                setReportPreset(position == 0 ? "daily" : position == 1 ? "monthly" : "yearly");
            }
        });
        content.addView(button("حفظ كشف السجلات", surface, text, () -> exportCompletePdf(false)), bottomMargin(12));
        content.addView(button("مشاركة كشف السجلات", surface, text, () -> exportCompletePdf(true)), bottomMargin(12));
        content.addView(button("سلة المحذوفات", surface, text, () -> host.show("trash")), bottomMargin(12));
        content.addView(button("تواصل معنا على WhatsApp", surface, text, host::openSupportWhatsApp), bottomMargin(6));
        divider(); sectionTitle("قفل التطبيق", "");
        Switch lock = switchRow("تفعيل", host.getPreferences(0).getBoolean("lock_enabled", false)); content.addView(lock, bottomMargin(8));
        lock.setOnCheckedChangeListener((button, checked) -> {
            if (checked && !host.localSecurity.hasPin()) { button.setChecked(false); promptSetPin(); return; } host.setLockEnabled(checked);
        });
        LinearLayout security = new LinearLayout(host); security.setGravity(Gravity.CENTER_VERTICAL);
        security.addView(button(host.localSecurity.hasPin() ? "تغيير رمز القفل" : "إنشاء كلمة مرور", surface, text, this::promptSetPin), new LinearLayout.LayoutParams(0, -2, 1)); security.addView(spaceWidth(10));
        Switch biometric = switchRow("تفعيل البصمة", host.getPreferences(0).getBoolean("biometric_enabled", false)); security.addView(biometric, new LinearLayout.LayoutParams(0, -2, 1)); content.addView(security, bottomMargin(8));
        biometric.setOnCheckedChangeListener((button, checked) -> { if (checked) { button.setChecked(false); if (!host.localSecurity.hasPin()) promptSetPin(); else host.requestBiometric(true); } else host.getPreferences(0).edit().putBoolean("biometric_enabled", false).apply(); });
        divider(); sectionTitle("الحساب", "");
        LinearLayout account = new LinearLayout(host); account.setGravity(Gravity.CENTER_VERTICAL);
        account.addView(button("تغيير كلمة المرور", surface, text, this::promptChangeServerPassword), new LinearLayout.LayoutParams(0, -2, 1)); account.addView(spaceWidth(10));
        account.addView(button("تسجيل الخروج", surface, text, () -> new SadadDialog.Builder(host).setTitle("تسجيل الخروج").setMessage("هل تريد تسجيل الخروج؟").setNegativeButton("إلغاء", null).setPositiveButton("تسجيل الخروج", (d, w) -> host.logout()).show()), new LinearLayout.LayoutParams(0, -2, 1)); content.addView(account, bottomMargin(14));
        TextView developer = label("المطور : شركة AORCA", 14, primary, true); developer.setGravity(Gravity.CENTER); developer.setOnClickListener(v -> host.openSupportWhatsApp()); content.addView(developer, topBottom(15, 10));
    }

    private void divider() { View divider = new View(host); divider.setBackgroundColor(line); content.addView(divider, topBottom(16, 16)); divider.getLayoutParams().height = dp(1); }

    private ImageView portrait(boolean store, int size) {
        ImageView image = new ImageView(host); android.graphics.drawable.Drawable photo = host.profilePhoto(store);
        if (photo == null) { image.setImageResource(R.drawable.ic_person); image.setColorFilter(host.isDarkTheme() ? accent : primary); image.setPadding(dp(9), dp(9), dp(9), dp(9)); }
        else image.setImageDrawable(photo);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP); image.setBackground(shape(soft, store ? 16 : size / 2));
        image.setOutlineProvider(new ViewOutlineProvider() { @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(store ? 16 : size / 2)); } }); image.setClipToOutline(true); return image;
    }

    private void showAccountMenu() {
        String[] options = BuildConfig.STANDALONE_MODE ? new String[]{"تغيير صورة الحساب", "المظهر", "إعدادات الإشعارات"} : new String[]{"تغيير صورة الحساب", "المظهر", "إعدادات الإشعارات", "حسابات المتاجر", "مزامنة السجلات", "سجل نشاط المستخدمين"};
        new SadadDialog.Builder(host).setTitle(host.storeName().isEmpty() ? "متجري" : host.storeName()).setItems(options, (d, which) -> {
            if (which == 0) host.editProfilePhoto();
            else if (which == 1) showAppearancePicker();
            else if (which == 2) host.openNotificationSettings();
            else if (which == 3) showSavedAccounts();
            else if (which == 4) host.retryLocalSync();
            else host.showStoreActivityLog();
        }).show();
    }

    private AlertDialog showAppearancePicker() {
        LinearLayout box = new LinearLayout(host); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(20), dp(5), dp(20), dp(16)); box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        AlertDialog dialog = new SadadDialog.Builder(host).setTitle("اختر المظهر").setView(box).setNegativeButton("رجوع", null).create();
        for (boolean night : new boolean[]{false, true}) {
            LinearLayout option = card(surface); option.setOrientation(LinearLayout.HORIZONTAL); option.setGravity(Gravity.CENTER_VERTICAL); option.setPadding(dp(14), dp(12), dp(10), dp(12));
            LinearLayout copy = new LinearLayout(host); copy.setOrientation(LinearLayout.VERTICAL); copy.addView(label((host.isDarkTheme() == night ? "✓  " : "") + (night ? "الوضع الداكن" : "الوضع الفاتح"), 17, text, true)); option.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
            option.addView(iconView(night ? R.drawable.ic_theme_dark : R.drawable.ic_theme_light, accent), new LinearLayout.LayoutParams(dp(28), dp(28))); option.setContentDescription(night ? "اختيار الوضع الداكن" : "اختيار الوضع الفاتح"); option.setOnClickListener(v -> { dialog.dismiss(); host.setTheme(night); }); box.addView(option, bottomMargin(10));
        }
        dialog.show(); return dialog;
    }

    private void showSavedAccounts() {
        JSONArray accounts = host.sessions.savedAccounts(); String[] names = new String[accounts.length() + 1];
        for (int i = 0; i < accounts.length(); i++) names[i] = accounts.optJSONObject(i).optString("name", "متجر"); names[accounts.length()] = "＋ إضافة حساب متجر";
        new SadadDialog.Builder(host).setTitle("حسابات المتاجر").setItems(names, (d, index) -> { if (index == accounts.length()) host.addStoreAccount(); else host.switchStoreAccount(accounts.optJSONObject(index).optString("id")); }).show();
    }

    private void buildTrash() {
        pageHeading("سلة المحذوفات", "استعد سجلات الأشخاص والإشعارات أو احذفها نهائياً.");
        JSONArray items;
        try { items = host.database.getTrashItems(); }
        catch (Exception e) { items = new JSONArray(); }
        if (items.length() == 0) {
            emptyCard("سلة المحذوفات فارغة", "ستظهر هنا السجلات والإشعارات التي تحذفها.");
            return;
        }
        for (int i = 0; i < items.length(); i++) {
            JSONObject entry = items.optJSONObject(i); if (entry == null) continue;
            long id = entry.optLong("id"); String kind = entry.optString("kind");
            JSONObject payload = entry.optJSONObject("payload");
            LinearLayout box = card(surface); box.setPadding(dp(14), dp(12), dp(14), dp(12));
            box.addView(label(entry.optString("title", "عنصر محذوف"), 15, text, true));
            String kindLabel = "contact".equals(kind) ? "زبون وسجله" : "ledger".equals(kind) ? "سجل حركات" : "إشعار";
            box.addView(label(kindLabel + " · " + dayAndTime(entry.optLong("deletedAt")), 12, muted, false), topMargin(4));
            LinearLayout actions = new LinearLayout(host); actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.addView(button("استعادة", soft, softForeground(), () -> {
                try {
                    if ("contact".equals(kind)) {
                        if (!host.database.restoreTrashContact(id)) { host.showBrandedMessage("تعذر العثور على السجل المحذوف."); return; }
                        host.markLedgerChanged();
                    } else if ("ledger".equals(kind)) {
                        if (!host.database.restoreTrashLedger(id)) {
                            host.showBrandedMessage("استعد الزبون من سلة المحذوفات أولاً، ثم أعد هذا السجل."); return;
                        }
                        host.markLedgerChanged();
                    } else if (payload != null) {
                        DueReminderManager.restore(host, payload);
                        host.database.deleteTrashItem(id);
                    }
                    host.showBrandedMessage("تمت الاستعادة."); host.refreshCurrentScreen();
                } catch (Exception error) {
                    host.showBrandedMessage(error.getMessage() == null ? "تعذرت الاستعادة." : error.getMessage());
                }
            }), new LinearLayout.LayoutParams(0, -2, 1));
            actions.addView(spaceWidth(8));
            actions.addView(button("حذف نهائياً", Color.rgb(255, 238, 235), Color.rgb(157, 47, 43), () ->
                    new SadadDialog.Builder(host).setTitle("حذف نهائي")
                            .setMessage("لن يمكن استعادة هذا العنصر بعد حذفه نهائياً.")
                            .setNegativeButton("إلغاء", null)
                            .setPositiveButton("حذف", (dialog, which) -> {
                                host.database.deleteTrashItem(id); host.refreshCurrentScreen();
                            }).show()), new LinearLayout.LayoutParams(0, -2, 1));
            box.addView(actions, topMargin(10)); content.addView(box, bottomMargin(9));
        }
    }

    private void addSubscriptionSummary() {
        if (BuildConfig.STANDALONE_MODE) {
            LinearLayout summary = card(soft); summary.setPadding(dp(15), dp(11), dp(15), dp(11));
            summary.addView(label("نسخة مستقلة", 12, muted, false));
        summary.addView(label("لا تتصل باشتراك أو خادم", 16, text, true), topMargin(3));
            content.addView(summary, bottomMargin(17));
            return;
        }
        JSONObject account = host.sessions.account();
        String mode = account.optString("subscriptionMode", "permanent");
        String detail;
        if ("permanent".equals(mode)) detail = "اشتراكك فعال دون تاريخ انتهاء";
        else if ("paused".equals(mode)) detail = "الاشتراك موقوف مؤقتًا";
        else {
            long expiry = account.optLong("subscriptionExpiresAt", 0L);
            long remaining = Math.max(0L, expiry - System.currentTimeMillis());
            if (remaining == 0L) detail = "انتهت مدة الاشتراك · تواصل مع الأدمن للتجديد";
            else if (remaining < 86_400_000L) {
                long hours = Math.max(1L, (remaining + 3_599_999L) / 3_600_000L);
                detail = "متبقي أقل من يوم · " + NumberFormat.getIntegerInstance(Locale.US).format(hours) + " ساعة";
            } else {
                long days = remaining / 86_400_000L;
                detail = days == 1L ? "متبقي يوم واحد" : "متبقي " + NumberFormat.getIntegerInstance(Locale.US).format(days) + " أيام";
            }
        }
        LinearLayout summary = card(soft); summary.setPadding(dp(15), dp(11), dp(15), dp(11));
        summary.addView(label("الاشتراك الحالي", 12, muted, false));
        summary.addView(label(detail, 16, text, true), topMargin(3));
        content.addView(summary, bottomMargin(17));
    }

    private AlertDialog promptStoreProfile() {
        LinearLayout box = new LinearLayout(host); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(18), dp(6), dp(18), 0);
        EditText store = input("اسم المتجر", false); store.setText(host.storeName()); box.addView(store, bottomMargin(10));
        EditText owner = input("اسم صاحب المتجر", false); owner.setText(host.ownerName()); box.addView(owner);
        AlertDialog dialog = new SadadDialog.Builder(host).setTitle("بيانات المتجر").setView(box).setNegativeButton("إلغاء", null).setPositiveButton("حفظ", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (store.getText().toString().trim().isEmpty()) { store.setError("أدخل اسم المتجر"); return; }
            if (owner.getText().toString().trim().isEmpty()) { owner.setError("أدخل اسم صاحب المتجر"); return; }
            host.saveStoreProfile(store.getText().toString(), owner.getText().toString()); dialog.dismiss();
        })); dialog.show(); return dialog;
    }

    private void promptChangeServerPassword() {
        LinearLayout box = new LinearLayout(host); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(18), dp(6), dp(18), 0);
        EditText previous = input("كلمة المرور السابقة", true); box.addView(previous, bottomMargin(9));
        EditText next = input("كلمة المرور الجديدة (12 محرفًا على الأقل)", true); box.addView(next, bottomMargin(9));
        EditText confirm = input("تأكيد كلمة المرور الجديدة", true); box.addView(confirm);
        AlertDialog dialog = new SadadDialog.Builder(host).setTitle("تغيير كلمة المرور").setView(box).setNegativeButton("إلغاء", null).setPositiveButton("تغيير", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!next.getText().toString().equals(confirm.getText().toString())) { confirm.setError("كلمتا المرور غير متطابقتين"); return; }
            if (next.getText().toString().length() < 12) { next.setError("استخدم 12 محرفًا على الأقل."); return; }
            host.changeServerPassword(previous.getText().toString(), next.getText().toString(), (success, message) -> {
                if (success) dialog.dismiss(); else previous.setError(message);
            });
        })); dialog.show();
    }

    private void promptSetPin() {
        LinearLayout box = new LinearLayout(host); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(20), dp(8), dp(20), 0); box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        EditText first = input("رمز جديد (4 إلى 12 رقماً)", true); first.setInputType(2 | 0x10); box.addView(first, bottomMargin(8));
        EditText second = input("تأكيد الرمز", true); second.setInputType(2 | 0x10); box.addView(second);
        AlertDialog dialog = new SadadDialog.Builder(host).setTitle("إعداد قفل التطبيق").setMessage("سيُطلب الرمز عند فتح التطبيق بعد مغادرته.")
                .setView(box).setNegativeButton("إلغاء", null).setPositiveButton("حفظ", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String a = first.getText().toString(), b = second.getText().toString();
            if (!a.equals(b)) { second.setError("الرمزان غير متطابقين"); return; }
            if (host.setAppPin(a)) dialog.dismiss();
        })); dialog.show();
    }

    private LinearLayout bottomNav(String page) {
        LinearLayout nav = new LinearLayout(host); nav.setGravity(Gravity.CENTER); nav.setBackgroundColor(surface); nav.setElevation(dp(8));
        nav.setPadding(dp(8), dp(7), dp(8), dp(7));
        String active = activeTab(page);
        addNavItem(nav, R.drawable.ic_home, "الرئيسية", "home", active);
        addNavItem(nav, R.drawable.ic_people, "الزبائن", "contacts", active);
        LinearLayout add = new LinearLayout(host); add.setOrientation(LinearLayout.VERTICAL); add.setGravity(Gravity.CENTER);
        TextView plus = button("＋", primary, Color.WHITE, () -> { editingContactId = 0; host.show("contact_form"); }); plus.setTextSize(26); plus.setPadding(0, 0, 0, 0); plus.setMinHeight(dp(48)); plus.setBackground(shape(primary, 24)); plus.setElevation(dp(6)); plus.setContentDescription("إضافة شخص جديد"); add.addView(plus, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView addLabel = label("إضافة", 10, muted, false); addLabel.setGravity(Gravity.CENTER); add.addView(addLabel, topMargin(4)); nav.addView(add, new LinearLayout.LayoutParams(0, dp(68), 1));
        addNavItem(nav, R.drawable.ic_history, "السجل", "history", active);
        addNavItem(nav, R.drawable.ic_settings, "الإعدادات", "settings", active);
        return nav;
    }

    private void addNavItem(LinearLayout nav, int icon, String label, String target, String active) {
        LinearLayout item = new LinearLayout(host); item.setOrientation(LinearLayout.VERTICAL); item.setGravity(Gravity.CENTER); item.setPadding(dp(4), dp(2), dp(4), dp(2));
        boolean selected = active.equals(target);
        item.setBackgroundColor(Color.TRANSPARENT); item.setSelected(selected);
        item.setClipChildren(false); item.setClipToPadding(false);
        int color = selected ? softForeground() : muted;
        FrameLayout iconArea = new FrameLayout(host);
        iconArea.setClipChildren(false); iconArea.setClipToPadding(false);
        View halo = new View(host);
        halo.setBackground(navHalo()); halo.setAlpha(0f);
        FrameLayout.LayoutParams haloParams = new FrameLayout.LayoutParams(dp(48), dp(46), Gravity.CENTER);
        iconArea.addView(halo, haloParams);
        ImageView glyph = iconView(icon, color);
        FrameLayout.LayoutParams glyphParams = new FrameLayout.LayoutParams(dp(23), dp(23), Gravity.CENTER);
        iconArea.addView(glyph, glyphParams);
        item.addView(iconArea, new LinearLayout.LayoutParams(-1, dp(40)));
        TextView title = label(label, 10, color, selected); title.setGravity(Gravity.CENTER); title.setPadding(0, 0, 0, 0); item.addView(title, topMargin(3));
        nav.addView(item, new LinearLayout.LayoutParams(0, dp(68), 1));
        if (selected) {
            glyph.setScaleX(.97f); glyph.setScaleY(.97f); title.setAlpha(.84f); item.setTranslationY(dp(.5f));
            ValueAnimator highlight = ValueAnimator.ofFloat(0f, 1f);
            highlight.setDuration(580L); highlight.setInterpolator(new DecelerateInterpolator(1.5f));
            highlight.addUpdateListener(value -> {
                float progress = (float) value.getAnimatedValue();
                halo.setAlpha(.94f * progress);
                glyph.setScaleX(.97f + .03f * progress); glyph.setScaleY(.97f + .03f * progress);
                title.setAlpha(.84f + .16f * progress);
                item.setTranslationY(dp(.5f * (1f - progress)));
            });
            item.post(() -> {
                highlight.start();
            });
        }
        item.setOnClickListener(v -> {
            if (target.equals(active)) { if (route.equals(target)) return; else host.show(target); }
            else host.show(target);
        });
    }

    private GradientDrawable navHalo() {
        int tint = accent;
        GradientDrawable glow = new GradientDrawable();
        glow.setShape(GradientDrawable.OVAL);
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setGradientRadius(dp(24));
        glow.setColors(new int[]{Color.argb(112, Color.red(tint), Color.green(tint), Color.blue(tint)),
                Color.argb(42, Color.red(tint), Color.green(tint), Color.blue(tint)), Color.TRANSPARENT});
        return glow;
    }

    private String activeTab(String page) {
        if (page.startsWith("contact") || page.endsWith("_form")) return "contacts";
        if (page.equals("history") || page.equals("history_results") || page.equals("reports")) return "history";
        if (page.equals("settings") || page.equals("trash")) return "settings";
        return "home";
    }

    private boolean showBottomNav(String page) { return !page.endsWith("_form"); }
    private int softForeground() { return host.isDarkTheme() ? text : primary; }
    private String titleFor(String page) {
        switch (page) {
            case "home": return "الرئيسية"; case "contacts": return "الزبائن"; case "contact_detail": return "تفاصيل الشخص";
            case "contact_form": return editingContactId > 0 ? "تعديل الشخص" : "إضافة شخص"; case "debt_form": return "تسجيل دين";
            case "payment_form": return "تسجيل دفعة"; case "history": case "history_results": return "سجل الحركات"; case "reports": return "التقارير";
            case "settings": return "الإعدادات"; case "trash": return "سلة المحذوفات"; case "notifications": return "الإشعارات"; default: return "سدد";
        }
    }

    private GradientDrawable pageBackground() { return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, host.isDarkTheme() ? new int[]{bg, Color.rgb(20, 46, 37)} : new int[]{bg, Color.rgb(237, 246, 239)}); }

    private TextView backArrow(Runnable action) {
        TextView back = button("", Color.TRANSPARENT, host.isDarkTheme() ? Color.WHITE : Color.BLACK, action); android.graphics.drawable.Drawable arrow = host.getDrawable(R.drawable.ic_back_whatsapp).mutate(); arrow.setTint(host.isDarkTheme() ? Color.WHITE : Color.BLACK); arrow.setBounds(0, 0, dp(24), dp(24)); back.setCompoundDrawables(null, null, arrow, null); back.setGravity(Gravity.CENTER); back.setPadding(0, 0, 0, 0); back.setMinHeight(0); back.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Color.argb(28, 100, 130, 115)), null, null)); back.setContentDescription("رجوع إلى الشاشة السابقة"); return back;
    }

    private void pageHeading(String heading, String sub) {
        LinearLayout row = new LinearLayout(host); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        String target = backTarget(route);
        if (target != null) { TextView back = backArrow(() -> host.show(target)); row.addView(back, new LinearLayout.LayoutParams(dp(38), dp(38))); row.addView(spaceWidth(10)); }
        TextView h = label(heading, 23, text, true); h.setGravity(Gravity.RIGHT); row.addView(h, new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(row, topBottom(8, 5)); if (sub != null && !sub.isEmpty()) content.addView(label(sub, 13, muted, false), bottomMargin(15));
    }

    private String backTarget(String page) {
        switch (page) {
            case "contacts": case "history": case "settings": return "home";
            case "contact_detail": return "contacts";
            case "contact_form": return host.contactFormBackTarget();
            case "debt_form": case "payment_form": return "contact_detail";
            case "history_results": return "history";
            case "reports": return "history";
            case "notifications": return "home";
            case "trash": return "settings";
            default: return null;
        }
    }

    private void sectionTitle(String heading, String sub) {
        TextView h = label(heading, 17, text, true); content.addView(h, topBottom(5, 3));
        if (sub != null && !sub.isEmpty()) content.addView(label(sub, 12, muted, false), bottomMargin(9));
    }

    private LinearLayout metric(String title, String amount, String caption, int stripe) {
        LinearLayout box = card(surface); box.setPadding(dp(14), dp(14), dp(14), dp(14));
        box.setElevation(dp(2));
        TextView titleView = label(title, 12, muted, true); box.addView(titleView);
        box.addView(label(amount, 19, text, true), topMargin(7));
        box.addView(label(caption, 11, stripe, false), topMargin(3));
        return box;
    }

    private View actionCard(int glyph, String title, Runnable action) {
        LinearLayout box = card(surface); box.setGravity(Gravity.CENTER); box.setPadding(dp(6), dp(10), dp(6), dp(8));
        ImageView icon = iconView(glyph, accent); box.addView(icon, new LinearLayout.LayoutParams(dp(27), dp(27)));
        TextView caption = label(title, 11, text, true); caption.setGravity(Gravity.CENTER); box.addView(caption, topMargin(8)); box.setOnClickListener(v -> action.run()); return box;
    }

    private ImageView iconView(int resource, int color) {
        ImageView icon = new ImageView(host); icon.setImageResource(resource); icon.setColorFilter(host.isDarkTheme() && color == primary ? accent : color);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER); icon.setContentDescription(null); return icon;
    }

    private void emptyCard(String title, String detail) { emptyCardInto(content, title, detail); }
    private void emptyCardInto(LinearLayout parent, String title, String detail) {
        LinearLayout box = card(surface); box.setGravity(Gravity.CENTER); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(18), dp(23), dp(18), dp(23));
        box.addView(label("◌", 28, accent, true));
        TextView h = label(title, 16, text, true); h.setGravity(Gravity.CENTER); box.addView(h, topMargin(7));
        TextView d = label(detail, 12, muted, false); d.setGravity(Gravity.CENTER); box.addView(d, topMargin(5)); parent.addView(box, bottomMargin(10));
    }

    private void addField(String title, View input) {
        TextView label = label(title, 13, text, true); content.addView(label, topBottom(3, 6));
        content.addView(input, bottomMargin(13));
    }

    private EditText input(String hint, boolean password) {
        EditText field = new EditText(host); field.setSingleLine(!hint.contains("اختيارية") && !hint.contains("تفاصيل")); field.setTextSize(15); field.setTextColor(text); field.setHintTextColor(muted);
        field.setHint(hint); field.setPadding(dp(14), dp(11), dp(14), dp(11)); field.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        field.setLayoutDirection(View.LAYOUT_DIRECTION_RTL); field.setBackground(shape(surface, 13)); field.setSelectAllOnFocus(false);
        if (password) field.setInputType(129); else field.setInputType(1);
        return field;
    }

    private Spinner spinner(String[] options) {
        Spinner spinner = new Spinner(SadadDialog.context(host), Spinner.MODE_DROPDOWN); spinner.setPadding(dp(9), dp(2), dp(9), dp(2)); spinner.setBackground(shape(surface, 13)); spinner.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(host, android.R.layout.simple_spinner_dropdown_item, options) {
            @Override public View getDropDownView(int position, View convertView, ViewGroup parent) { View row = getView(position, convertView, parent); row.setBackgroundColor(surface); return row; }
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView t = new TextView(host); t.setText(getItem(position)); t.setTextColor(text); t.setTextSize(15); t.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT); t.setPadding(dp(8), dp(10), dp(8), dp(10)); return t;
            }
        };
        spinner.setPopupBackgroundDrawable(shape(surface, 16)); spinner.setAdapter(adapter); return spinner;
    }

    private Switch switchRow(String title, boolean checked) {
        Switch control = new Switch(host); control.setText(title); control.setTextColor(text); control.setTextSize(14); control.setChecked(checked);
        control.setPadding(dp(14), dp(9), dp(14), dp(9)); control.setBackground(shape(surface, 14)); control.setLayoutDirection(View.LAYOUT_DIRECTION_RTL); return control;
    }

    private TextView button(String title, int background, int foreground, Runnable action) {
        TextView view = label(title, 14, foreground, true); view.setGravity(Gravity.CENTER); view.setPadding(dp(13), dp(12), dp(13), dp(12));
        view.setMinHeight(dp(48)); view.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Color.argb(80, 21, 157, 137)), shape(background, 10), null));
        view.setOnTouchListener((v, event) -> { if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) v.animate().scaleX(.97f).scaleY(.97f).setDuration(90).start(); else if (event.getAction() == android.view.MotionEvent.ACTION_UP || event.getAction() == android.view.MotionEvent.ACTION_CANCEL) v.animate().scaleX(1f).scaleY(1f).setDuration(100).start(); return false; }); view.setClickable(true); view.setFocusable(true); view.setOnClickListener(v -> action.run()); return view;
    }

    private TextView label(String value, float size, int color, boolean bold) {
        TextView view = new TextView(host); view.setText(value == null ? "" : value); view.setTextSize(size); view.setTextColor(host.isDarkTheme() && color == primary ? accent : color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD); view.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT); view.setIncludeFontPadding(true); view.setLayoutDirection(View.LAYOUT_DIRECTION_RTL); return view;
    }

    private ImageView logo(int size) {
        ImageView image = new ImageView(host); image.setImageResource(host.brandLogoResource()); image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setContentDescription("شعار سدد"); image.setBackgroundColor(bg); image.setClipToOutline(true);
        image.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                int radius = Math.round(Math.min(view.getWidth(), view.getHeight()) * .095f);
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        return image;
    }

    private ScrollView scroll() {
        ScrollView scroll = new ScrollView(host); scroll.setFillViewport(true); scroll.setSmoothScrollingEnabled(true); scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS); scroll.setClipToPadding(false); scroll.setLayoutDirection(View.LAYOUT_DIRECTION_RTL); return scroll;
    }

    private LinearLayout card(int color) {
        LinearLayout box = new LinearLayout(host); box.setOrientation(LinearLayout.VERTICAL); box.setBackground(shape(color, 10)); box.setElevation(dp(1)); return box;
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius));
        if (color == surface) drawable.setStroke(dp(1), line); return drawable;
    }

    private int shapeButtonColor() { return Color.rgb(159, 46, 51); }
    private SadadDatabase cachedDatabase;
    private long cachedSnapshotToken = Long.MIN_VALUE;
    private boolean cachedOverview;
    private long cachedPerson;
    private JSONObject cachedSnapshot;
    private JSONObject snapshot() {
        try { synchronized (host.database) {
            long token = host.database.changeToken();
            boolean overview = "home".equals(host.currentRoute()) || "contacts".equals(host.currentRoute()) || "settings".equals(host.currentRoute()) || "history".equals(host.currentRoute()) || "history_results".equals(host.currentRoute());
            long person=("contact_detail".equals(host.currentRoute())||"debt_form".equals(host.currentRoute())||"payment_form".equals(host.currentRoute()))?contactId:0;
            if (cachedSnapshot == null || cachedDatabase != host.database || token != cachedSnapshotToken || overview != cachedOverview || person!=cachedPerson) {
                cachedSnapshot = overview ? host.database.getOverview() : person>0?host.database.getPersonSnapshot(person):host.database.getSnapshot(); cachedPerson=person; cachedOverview = overview; cachedDatabase = host.database; cachedSnapshotToken = token;
            }
            return cachedSnapshot;
        } } catch (JSONException e) { return new JSONObject(); }
    }
    private JSONArray array(JSONObject object, String key) { JSONArray a = object.optJSONArray(key); return a == null ? new JSONArray() : a; }
    private JSONObject findContact(long id) {
        JSONArray contacts = array(snapshot(), "contacts"); for (int i = 0; i < contacts.length(); i++) { JSONObject c = contacts.optJSONObject(i); if (c != null && c.optLong("id") == id) return c; } return null;
    }
    private boolean isReceivableTransaction(JSONObject tx) { return tx != null && "receivable".equals(tx.optString("direction")); }
    private int countReceivableTransactions(JSONArray transactions) {
        int count = 0; for (int i = 0; i < transactions.length(); i++) if (isReceivableTransaction(transactions.optJSONObject(i))) count++; return count;
    }
    private int countReceivablePayments(JSONArray payments) {
        Set<String> actions = new HashSet<>();
        for (int i = 0; i < payments.length(); i++) {
            JSONObject payment = payments.optJSONObject(i);
            if (payment != null && isReceivableTransaction(payment)) actions.add(payment.optLong("contactId") + ":" + payment.optLong("createdAt") + ":" + payment.optString("method") + ":" + payment.optString("note"));
        }
        return actions.size();
    }
    private int countDebts(JSONArray debts) {
        int count = 0; for (int i = 0; i < debts.length(); i++) { JSONObject debt = debts.optJSONObject(i); if (debt != null && "receivable".equals(debt.optString("direction"))) count++; } return count;
    }
    private int contactsWithBalance(JSONArray contacts, String field) {
        int n = 0; for (int i = 0; i < contacts.length(); i++) { JSONObject c = contacts.optJSONObject(i); if (c != null && c.optDouble(field) > 0) n++; } return n;
    }
    private JSONObject contactOf(JSONObject[] people, long id) { for (JSONObject p : people) if (p != null && p.optLong("id") == id) return p; return null; }
    private JSONObject debtOf(JSONArray debts, long id) { for (int i = 0; i < debts.length(); i++) { JSONObject d = debts.optJSONObject(i); if (d != null && d.optLong("id") == id) return d; } return null; }
    private String money(double n) {
        try { return NumberFormat.getNumberInstance(Locale.US).format(n) + " ₪"; }
        catch (Exception e) { return String.format(Locale.US, "%.2f ₪", n); }
    }
    private String date(long timestamp) {
        if (timestamp <= 0) return "بدون تاريخ";
        return new java.text.SimpleDateFormat("dd/MM/yyyy", Locale.US).format(new Date(timestamp));
    }
    private String formatDateOnly(long timestamp) { return new SimpleDateFormat("dd/MM/yyyy", Locale.US).format(new Date(timestamp)); }
    private String dayAndTime(long timestamp) {
        if (timestamp <= 0) return "وقت غير محدد";
        Date value = new Date(timestamp);
        String weekday = new SimpleDateFormat("EEEE", new Locale("ar")).format(value);
        String time = new SimpleDateFormat("HH:mm", Locale.US).format(value);
        return westernDigits(weekday + " · الساعة " + time + " · " + date(timestamp));
    }
    private String formatDueDate(String value) {
        if (value == null || value.trim().isEmpty()) return "غير محدد";
        try {
            Date parsed = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(value.trim());
            return parsed == null ? value : new SimpleDateFormat("dd/MM/yyyy", Locale.US).format(parsed);
        } catch (Exception e) { return value; }
    }
    private String westernDigits(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '\u0660' && c <= '\u0669') c = (char) ('0' + c - '\u0660');
            else if (c >= '\u06F0' && c <= '\u06F9') c = (char) ('0' + c - '\u06F0');
            result.append(c);
        }
        return result.toString();
    }
    private String initials(String name) {
        String[] words = name.trim().split("\\s+"); StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(2, words.length); i++) if (!words[i].isEmpty()) b.append(words[i].charAt(0));
        return b.length() == 0 ? "س" : b.toString();
    }
    private int dp(float n) { return host.dp(n); }
    private LinearLayout.LayoutParams match() { return new LinearLayout.LayoutParams(-1, -2); }
    private LinearLayout.LayoutParams matchWithBottom(int margin) { return new LinearLayout.LayoutParams(-1, -2) {{ bottomMargin = dp(margin); }}; }
    private LinearLayout.LayoutParams bottomMargin(int margin) { return new LinearLayout.LayoutParams(-1, -2) {{ bottomMargin = dp(margin); }}; }
    private LinearLayout.LayoutParams topMargin(int margin) { return new LinearLayout.LayoutParams(-1, -2) {{ topMargin = dp(margin); }}; }
    private LinearLayout.LayoutParams topBottom(int top, int bottom) { return new LinearLayout.LayoutParams(-1, -2) {{ topMargin = dp(top); bottomMargin = dp(bottom); }}; }
    private View spaceWidth(int width) { View v = new View(host); v.setLayoutParams(new LinearLayout.LayoutParams(dp(width), 1)); return v; }
    private static void addGap(LinearLayout parent, int height) { View v = new View(parent.getContext()); parent.addView(v, new LinearLayout.LayoutParams(1, Math.round(height * parent.getResources().getDisplayMetrics().density))); }
}
