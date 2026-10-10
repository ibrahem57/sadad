import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:sadad/core/models.dart';

void main() {
  group('المبالغ بوحدة الأغورة', () {
    test('يحفظ الكسور والأرقام العربية والفارسية بدقة', () {
      expect(parseMoney('0.29'), 29);
      expect(parseMoney('١٢٫٣٤'), 1234);
      expect(parseMoney('۱۲.۳۴'), 1234);
      expect(parseMoney('001.2'), 120);
      expect(parseMoney('0', allowZero: true), 0);
    });
    test('يرفض القيم الملتبسة أو السالبة أو الكسور الزائدة', () {
      for (final value in [
        '0',
        '-1',
        '1e2',
        '1.001',
        '1,000',
        'NaN',
        '1000000001',
      ]) {
        expect(() => parseMoney(value), throwsFormatException, reason: value);
      }
    });
  });

  test('الطلب المحفوظ يعيد نفس هوية العملية ونصها عند القراءة', () {
    final command = Command.create(
      'debt.create',
      ids.v4(),
      {'amountCents': 1234, 'note': 'دين'},
      dependencies: ['parent'],
    );
    final restored = Command.fromRow({
      'operation_id': command.operationId,
      'entity_id': command.entityId,
      'type': command.type,
      'wire': command.wire,
      'state': 'queued',
      'created_at': command.createdAt,
      'local_order': 7,
    });
    expect(restored.wire, command.wire);
    expect(restored.operationId, command.operationId);
    expect(restored.dependencies, ['parent']);
    expect(jsonDecode(restored.wire).containsKey('expectedVersion'), false);
    expect(
      Command.create(
        'contact.update',
        ids.v4(),
        {},
        version: '7',
      ).envelope['expectedVersion'],
      '7',
    );
  });

  Ledger seed() => Ledger({
    'contacts': [
      {'id': 'person', 'name': 'شخص', 'version': '1'},
    ],
    'debts': [
      {
        'id': 'debt',
        'contactId': 'person',
        'direction': 'receivable',
        'amountCents': 10000,
        'paidCents': 0,
        'remainingCents': 10000,
        'version': '1',
      },
    ],
    'payments': [],
    'transactions': [],
    'cursor': '1',
    'epoch': 'epoch',
  });

  test('المعاينة لا تعدّل السجل المعتمد ولا تكرر الدفعة', () {
    final confirmed = seed();
    final payment = Command.create('payment.create', 'payment', {
      'debtId': 'debt',
      'amountCents': 2500,
      'method': 'cash',
    });
    final preview = confirmed.projected([payment]);
    expect(confirmed.balance('person'), 10000);
    expect(confirmed.payments, isEmpty);
    expect(preview.balance('person'), 7500);
    preview.apply(payment);
    expect(preview.payments.length, 1);
    expect(preview.transactions.length, 1);
    expect(preview.balance('person'), 7500);
  });

  test('عكس الدفعة يحتفظ بالأصل ويعيد الرصيد', () {
    final ledger = seed();
    ledger.apply(
      Command.create('payment.create', 'payment', {
        'debtId': 'debt',
        'amountCents': 2500,
        'method': 'cash',
      }),
    );
    ledger.apply(
      Command.create('payment.reverse', 'reversal', {
        'paymentId': 'payment',
        'reason': 'خطأ في التسجيل',
      }),
    );
    expect(ledger.balance('person'), 10000);
    expect(ledger.payments.single['reversedAt'], isNotNull);
    expect(ledger.transactions.length, 2);
  });

  test('الأرشفة تحفظ الدين والحركات', () {
    final ledger = seed();
    ledger.apply(
      Command.create('contact.archive', 'person', {
        'reason': 'أرشفة',
      }, version: '1'),
    );
    expect(ledger.contact('person')!['archivedAt'], isNotNull);
    expect(ledger.total, 10000);
    ledger.apply(
      Command.create('contact.restore', 'person', {
        'reason': 'عودة',
      }, version: '2'),
    );
    expect(ledger.contact('person')!['archivedAt'], isNull);
    expect(ledger.total, 10000);
  });

  test('يستبعد المعاينة التابعة لعملية تحتاج مراجعة دون تعطيل غيرها', () {
    final parent = Command.create('contact.create', 'other', {
      'name': 'شخص آخر',
    });
    final rejected = Command(
      operationId: parent.operationId,
      entityId: parent.entityId,
      type: parent.type,
      wire: parent.wire,
      state: 'attention',
      createdAt: parent.createdAt,
    );
    final child = Command.create(
      'debt.create',
      'otherDebt',
      {'contactId': 'other', 'amountCents': 5000, 'direction': 'receivable'},
      dependencies: [parent.operationId],
    );
    final independent = Command.create('payment.create', 'payment', {
      'debtId': 'debt',
      'amountCents': 1000,
    });
    final preview = seed().projected([rejected, child, independent]);
    expect(preview.contact('other'), isNull);
    expect(preview.debt('otherDebt'), isNull);
    expect(preview.total, 9000);
  });
}
