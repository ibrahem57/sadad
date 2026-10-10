import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sadad/core/controller.dart';
import 'package:sadad/core/models.dart';
import 'package:sadad/core/storage.dart';
import 'package:sadad/main.dart';

SadadController previewApp() {
  final app = SadadController(LedgerStore());
  app.loading = false;
  app.session = StoreSession(
    scope: 'demo',
    token: 'local',
    deviceId: 'preview',
    demo: true,
    staffName: 'أحمد',
    account: {'name': 'متجر سدد', 'ledgerVersion': 3},
  );
  app.accounts = [app.session!];
  app.cache = CacheState(
    Ledger({
      'contacts': [
        {
          'id': 'person',
          'name': 'شخص باسم طويل للتأكد من عرض الشاشة العربية',
          'phone': '970599999999',
          'version': '1',
        },
      ],
      'debts': [
        {
          'id': 'debt',
          'contactId': 'person',
          'direction': 'receivable',
          'amountCents': 12550,
          'remainingCents': 12550,
          'paidCents': 0,
          'version': '1',
        },
      ],
      'payments': [],
      'transactions': [],
      'epoch': 'demo-local',
      'cursor': '1',
    }),
    false,
    1780000000000,
    '',
  );
  return app;
}

void main() {
  testWidgets(
    'شاشات الهاتف الضيق باللغة العربية تعمل في المظهرين دون تجاوزات',
    (tester) async {
      tester.view.physicalSize = const Size(360, 800);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final app = previewApp();
      await tester.pumpWidget(SadadApp(app));
      await tester.pumpAndSettle();
      expect(find.text('الدين الحالي'), findsOneWidget);
      expect(find.text('125.50 ₪'), findsWidgets);
      expect(
        Directionality.of(tester.element(find.byType(NavigationBar))),
        TextDirection.rtl,
      );
      for (final label in ['الأشخاص', 'السجل', 'الإعدادات', 'الرئيسية']) {
        await tester.tap(find.text(label).last);
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull, reason: label);
      }
      app.themeMode = ThemeMode.dark;
      await tester.pumpWidget(SadadApp(app));
      await tester.pumpAndSettle();
      expect(
        Theme.of(tester.element(find.byType(NavigationBar))).brightness,
        Brightness.dark,
      );
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets('المراجعة تعرض البيانات قبل الحفظ ويمكن الرجوع دون كتابة', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(360, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final app = previewApp();
    await tester.pumpWidget(SadadApp(app));
    await tester.pumpAndSettle();
    await tester.tap(find.text('إضافة شخص'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextFormField).first, 'شخص جديد');
    await tester.scrollUntilVisible(
      find.text('مراجعة وحفظ'),
      250,
      scrollable: find.byType(Scrollable).last,
    );
    await tester.tap(find.text('مراجعة وحفظ'));
    await tester.pumpAndSettle();
    expect(find.byType(AlertDialog), findsOneWidget);
    await tester.tap(find.text('رجوع'));
    await tester.pumpAndSettle();
    expect(find.byType(AlertDialog), findsNothing);
    expect(app.confirmed.contacts.length, 1);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox());
  });
}
