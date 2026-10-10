# سدد — دفتر مالي تجريبي

تطبيق Android بواجهة عربية، مع دفتر مالي على Supabase، وطابور محلي مشفر، ومزامنة تحافظ على هوية العمليات، ولوحة إدارة للأجهزة والاسترداد.

- مصدر Android: `Supabase-Setup/android-official/Sadad-AndroidStudio`.
- وظائف الخادم والترحيلات: `Supabase-Setup/supabase`.
- عقد العمليات: `Supabase-Setup/docs/V3_CONTRACT_AR.md`.
- دليل التشغيل: [دليل التشغيل](delivery/SADID_RUNBOOK_AR.md).
- نتائج التنفيذ والاختبارات: [تقرير التنفيذ](delivery/SADID_IMPLEMENTATION_REPORT_AR.md).

البيانات المرفقة اصطناعية. هذه نسخة للتجربة؛ توقيع التطبيق تطويري، واختبار هاتف فعلي واستعادة قاعدة الخادم الكاملة ما زالا مطلوبين قبل الإنتاج.

## البناء والاختبارات

افتح مشروع Android في Android Studio، أو شغّل داخل مجلده:

```powershell
.\gradlew.bat assembleDebug assembleDebugAndroidTest -Pandroid.overridePathCheck=true
```

لتثبيت تبعية اختبارات قاعدة البيانات من جذر المستودع:

```powershell
npm install --prefix .local-tools --no-save @electric-sql/pglite@0.5.8
npx --yes --package=node@22.16.0 node --max-old-space-size=256 Supabase-Setup/scripts/test-v3-ledger.mjs
```

توجد تفاصيل اختبارات Edge والمحاكي في دليل التشغيل. الترحيلات الجديدة منشورة على مشروع التجربة؛ طابق سجل الترحيلات قبل إعادة النشر.

بيانات الدخول محفوظة في ملف محلي خاص خارج هذا المستودع.

---

## توثيق النسخة السابقة

# سدد 2.5.10 — مشروع Android Studio

For the Supabase backend work, admin dashboard connection, verification status and remaining tasks, start with [the continuation handoff](docs/CONTINUE_HERE.md).

هذا المشروع يبني التطبيق المستقل والمتصل من نفس واجهات Android الأصلية. التحديث مؤرخ في 10 أكتوبر 2026.

## البناء

افتح مجلد المشروع في Android Studio، واستخدم SDK 36 وJDK المرفق مع Android Studio. لا يحتوي المشروع مسار SDK خاصًا بجهاز المطور؛ ينشئ Android Studio ملف `local.properties` على جهازك.

```powershell
.\gradlew.bat assembleStandaloneDebug assembleServerDebug
```

- `standaloneDebug`: `com.sadad.app.test.offline`، دخول أولي `123 / 123`، بيانات محلية فقط.
- `serverDebug`: `com.sadad.app`، تحقق ومزامنة مع لوحة الأدمن، ويستخدم افتراضيًا `http://10.0.2.2:8081/api` للمحاكي.

يمكن تعديل رابط الخادم في شاشة الدخول للنسخة المتصلة، أو عند البناء:

```powershell
.\gradlew.bat assembleServerDebug "-PSADAD_API_BASE_URL=https://YOUR-DOMAIN/api"
```

استبدل النطاق بعنوان موقعك الحقيقي. APKs المرفقة بإصدار التسليم موقعة Debug. إصدار الإنتاج يتطلب عنوان HTTPS ومفتاح توقيعك عبر `SADAD_KEYSTORE_FILE` و`SADAD_KEYSTORE_PASSWORD` و`SADAD_KEY_ALIAS` و`SADAD_KEY_PASSWORD` ثم `assembleServerRelease`.

## ملفات التنفيذ

- `NativeScreens.java`: الواجهات الرئيسية والأشخاص والسجل والإشعارات والإعدادات والتقارير.
- `LedgerTablePdf.java`: جدول مستقل لكل شخص، ترتيب زمني، رصيد افتتاحي وختامي، متابعة صفحات وبيانات طويلة.
- `LedgerTableExcel.java`: ملف XLSX حقيقي بورقة مستقلة لكل شخص وقيم مالية رقمية.
- `SyncScheduler.java` و`LedgerSyncService.java`: رفع عند الاتصال ومزامنة دورية بالخلفية.
- `PdfShareProvider.java`: مشاركة PDF وExcel بصلاحيات قراءة مؤقتة، دون صلاحيات تخزين عامة.
- `MainActivity.java`: الحسابات والربط والمزامنة واختيار الصور والحفظ والصوت والقفل.
- `SadadDatabase.java`: قاعدة SQLite المحلية.
- `server/`: نسخة مطابقة لموقع الأدمن وواجهة API في ملف الإدارة المنفصل.

## اختبار معزول على المحاكي

اختبارات الواجهات والكشوف تعمل فقط بمعرّف منفصل حتى لا تلمس بيانات التطبيق الحقيقي. يستخدم المشروع Instrumentation مدمجة في Android دون مكتبات اختبار إضافية.

```powershell
.\gradlew.bat assembleStandaloneDebug assembleStandaloneDebugAndroidTest "-PSADAD_TEST_APPLICATION_ID=com.sadad.qa"
adb -s emulator-5554 install -r app/build/outputs/apk/standalone/debug/app-standalone-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/standalone/debug/app-standalone-debug-androidTest.apk
adb -s emulator-5554 shell pm grant com.sadad.qa.offline android.permission.POST_NOTIFICATIONS
adb -s emulator-5554 shell am instrument -w com.sadad.qa.offline.test/com.sadad.app.QaInstrumentation
```

يبني أمر الاختبار APK بمعرّف QA؛ أعد تنفيذ أمر البناء العادي قبل توزيع APK. لا تُضاف بيانات اختبار إلى النسختين النهائيتين. يخرج الاختبار صورًا وPDF في مجلد `files/qa` الخاص بنسخة QA.

## تشغيل موقع الإدارة

من `server/` شغّل `Start-Sadad.cmd` أو اضبط `.env` وشغّل `node server.js` باستخدام Node.js 24 أو أحدث. يُنشأ حساب الأدمن بمفتاح التأسيس، ثم تُنشأ حسابات المتاجر من الموقع. تفاصيل الربط والاستخدام في الملفات العربية المرفقة بمجلد `docs/`.

## الخدمات المتبقية

لم يُنشر نطاق عام. دخول الزبائن المتصل يحتاج تفعيل SMS وبوابة موثقة. إرسال WhatsApp الآلي يحتاج إعداد Meta/WhatsApp Business. رابط الدعم الحالي يفتح الرقم `+970599562401` فقط عند طلب المستخدم.

## تصميم 2.5.10

الزبائن: بحث وتصنيفات وترتيب وبطاقات مختصرة. السجل: متبقٍ وآخر دين مع فتح الشخص من الاسم. النوافذ تستخدم SadadDialog بثيم فاتح وداكن، وSadadDatePickerDialog للتقويم، وThemeSceneView لرسومات المظهر. أسهم الرجوع شفافة دون دائرة.

اختبار التحديث المحدد: ابنِ نسخة QA مع الخيار -PSADAD_TEST_RUNNER=com.sadad.app.Ui251Instrumentation ثم شغّل نفس صنف Instrumentation في أمر adb. النسخة النهائية تبنى بدون خيارات QA.


## تحديث 2.5.10
- محرر الصورة في ProfilePhotoEditor.java، صورة مشتركة محفوظة لكل حساب.
- قاعدة التطبيق بالإصدار 6 تنقل الدفعات القديمة دون مسحها وتضيف محفظتي جوال وبال بي. خادم الأدمن يطبق النقل نفسه تلقائيًا.
- دخول الزبون مخفي حاليًا.


## تحديث 2.5.10

قلم الإعدادات يعدّل اسم المتجر وصاحبه، والصورة تفتح محرر الصورة. الترحيب واسم المتجر في سطر واحد. خطوط حركة الشهر متحركة وتعرض نسبة الديون والدفعات من مجموع حركة الشهر. اختيار المظهر يستخدم أيقونات بسيطة، والتصدير يسأل PDF أو Excel قبل الحفظ أو المشاركة. تُنشأ التقارير على عامل خلفي وتُخزّن نسخة السجلات مؤقتًا حتى تتغير قاعدة البيانات.

النسخة المتصلة تسجل مهمة Android كل ساعة عند توفر الشبكة، ومهمة رفع محفوظة للتعديلات غير المرفوعة. توقيت المهام يخضع لقيود Android؛ التطبيق المستقل يعمل محليًا. إذا تغيّر السجل أثناء رفع نسخة سابقة يحتفظ بالتعديل ويعيد رفعه بدل استبداله بالاستجابة القديمة.

الكود العام: https://github.com/ibrahem57/sadad

الملفات الجاهزة: https://github.com/ibrahem57/sadad/releases/tag/v2.5.10

GitHub لا يشغّل موقع الأدمن تلقائيًا؛ يوجد خادم Node/SQLite في `server/` وتعليمات التشغيل والنشر فيه. لا توجد كلمات مرور أو قواعد بيانات المستخدم ضمن هذا المشروع.

اختبار الواجهات الأحدث: أضف `-PSADAD_TEST_RUNNER=com.sadad.app.Ui254Instrumentation` و`-PSADAD_TEST_APPLICATION_ID=com.sadad.qa` عند بناء standaloneDebug وstandaloneDebugAndroidTest. اختبار Sync254Instrumentation يحتاج خادم اختبار مؤقت وملف جلسة اختبار؛ لا تشغله على التطبيق الحقيقي.

لتجربة المزامنة: شغّل node tools/qa-sync-server.cjs على Node 24؛ ينشئ خادمًا مؤقتًا على 18082 وملف tools/qa-sync-session.json. انسخ الملف عبر adb push إلى /data/local/tmp/sadad-qa-sync.json، وابنِ serverDebug وserverDebugAndroidTest بمعرّف SADAD_APPLICATION_ID=com.sadad.qa.connected وSADAD_TEST_RUNNER=com.sadad.app.Sync254Instrumentation، ثم ثبّتهما وشغّل الاختبار. ملف جلسة الاختبار مستبعد من Git. أوقف الخادم بعد الاختبار.

مرجع جدولة Android: https://developer.android.com/reference/android/app/job/JobInfo.Builder#setExpedited(boolean). المزامنة السريعة تستخدم مهمة ذات أولوية عند توفر حصتها، وتعود لمهمة عادية محفوظة إذا انتهت الحصة.


## التوسع والسجل الكامل

مزامنة التغييرات على دفعات وتنزيل كامل السجل بصفحات مع حماية التعديلات المحلية. راجع docs/Sadad-Scale-And-Operations-2.5.10-AR.txt لمعرفة النسخ الاحتياطية وحدود الاستضافة. لا يثبت اختبار محلي سعة إنتاج غير محدودة.


## تحديث 2.5.10

مقدمات واتساب فلسطين وإسرائيل، زر تواصل في ملف الزبون، فلتر مدة واحد يبدأ بالكل، حفظ PDF/Excel في نهاية السجل، بيانات الزبون الكاملة في التصدير، وقائمة سجل الحركات الكامل عند الفتح مع إغلاق وإعادة استخدام الصفوف. حذف السجلات الكبيرة يعطي الأولوية لحذف الأصل ويؤكد أحداث الطابور المحددة فقط.


تحديث 2.5.10: سجل الرئيسية الكامل بقائمة تعيد استخدام الصفوف، رجوع وفق مسار التنقل، فلتر صغير، نسبة دفعات ظاهرة، عنوان إجمالي الديون، أيقونات البحث والقلم، المطور orca، وكلمة مرور المتجر من أربع خانات.


تحديث 2.5.10: توسيط أيقونة قلم الإعدادات وإصلاح قص اسم الشاشة بترويسة ذات ارتفاع يتكيف مع النص.

تحديث 2.5.10: الدفعات في سجل الرئيسية خضراء بإشارة + والديون حمراء بإشارة −، وألوان الأسهم متوافقة مع الحركة.

تحديث 2.5.10: حذف الصورة المشتركة، تباعد صفوف سجل الرئيسية وتمريره كاملًا مع الصفحة، سهم عريض وزر إغلاق علوي واحد.
