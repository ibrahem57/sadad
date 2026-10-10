import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';
import 'package:sadad/core/api.dart';
import 'package:sadad/core/controller.dart';
import 'package:sadad/core/models.dart';
import 'package:sadad/core/storage.dart';

const epochA = 'aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa';
const epochB = 'bbbbbbbb-bbbb-4bbb-bbbb-bbbbbbbbbbbb';
Ledger snapshot(
  int cursor, {
  String epoch = epochA,
  List<Json> contacts = const [],
}) => Ledger({
  'contacts': contacts,
  'debts': [],
  'payments': [],
  'transactions': [],
  'cursor': '$cursor',
  'epoch': epoch,
});

class FakeApi extends SadadApi {
  final Future<Json> Function(String method, String route, String? wire) handle;
  final List<String> sent = [];
  FakeApi(this.handle);
  @override
  Future<Json> request(
    String method,
    String route, {
    String? token,
    Json? body,
    String? wire,
  }) {
    if (wire != null) {
      sent.add(wire);
    }
    return handle(method, route, wire);
  }
}

void main() {
  late Directory temporary;
  late LedgerStore store;
  late String databasePath;
  final session = StoreSession(
    scope: 'store-a',
    token: 'test-only',
    deviceId: 'test-device',
    account: {'id': 'a', 'ledgerVersion': 3},
  );

  setUpAll(() {
    sqfliteFfiInit();
    databaseFactory = databaseFactoryFfi;
  });
  setUp(() async {
    temporary = await Directory.systemTemp.createTemp('sadad-flutter-test-');
    databasePath = '${temporary.path}/journal.db';
    store = LedgerStore();
    await store.open(databasePath: databasePath);
  });
  tearDown(() async {
    await store.db.close();
    final target = temporary.absolute.path;
    if (!target.startsWith(Directory.systemTemp.absolute.path) ||
        !temporary.path.contains('sadad-flutter-test-')) {
      throw StateError('Unsafe test cleanup');
    }
    await temporary.delete(recursive: true);
  });

  test(
    'يحفظ نص العملية بعد إعادة الفتح ويمنع تعديلها أو خلط المتاجر',
    () async {
      final c = Command.create('contact.create', ids.v4(), {'name': 'شخص'});
      await store.enqueue('store-a', c);
      await store.accept('store-a', snapshot(4));
      await store.accept(
        'store-b',
        snapshot(
          20,
          contacts: [
            {'id': 'other', 'name': 'متجر آخر'},
          ],
        ),
      );
      expect(await store.pending('store-b'), isEmpty);
      expect((await store.cache('store-a')).ledger!.contacts, isEmpty);
      await expectLater(
        store.db.update(
          'journal',
          {'wire': '{}'},
          where: 'operation_id=?',
          whereArgs: [c.operationId],
        ),
        throwsA(isA<DatabaseException>()),
      );
      await store.db.close();
      store = LedgerStore();
      await store.open(databasePath: databasePath);
      expect((await store.pending('store-a')).single.wire, c.wire);
    },
  );

  test('إيصال الاعتماد لا يحرك مؤشر القراءة حتى تصل النسخة المعتمدة', () async {
    final c = Command.create('contact.create', ids.v4(), {'name': 'شخص'});
    await store.accept('store-a', snapshot(4));
    await store.enqueue('store-a', c);
    await store.acknowledge('store-a', c, {'epoch': epochA, 'sequence': '5'});
    expect((await store.cache('store-a')).ledger!.cursor, '4');
    expect((await store.pending('store-a')).single.state, 'acknowledged');
    await store.accept('store-a', snapshot(5));
    expect(await store.pending('store-a'), isEmpty);
    await store.accept('store-a', snapshot(3));
    expect((await store.cache('store-a')).ledger!.cursor, '5');
  });

  test('تغير نسخة الخادم يوقف الإرسال ويحفظ الطلب للمراجعة', () async {
    final c = Command.create('contact.create', ids.v4(), {'name': 'شخص'});
    await store.accept('store-a', snapshot(5));
    await store.enqueue('store-a', c);
    final api = FakeApi(
      (method, route, wire) async => {
        'snapshot': snapshot(2, epoch: epochB).data,
      },
    );
    await expectLater(
      SyncEngine(store, api).run(session),
      throwsA(isA<ApiFailure>()),
    );
    expect(api.sent, isEmpty);
    expect((await store.cache('store-a')).recovery, true);
    final kept = (await store.pending('store-a')).single;
    expect(kept.state, 'attention');
    expect(kept.wire, c.wire);
    await expectLater(store.finishRecovery('store-a'), throwsFormatException);
    await store.review(kept);
    await store.finishRecovery('store-a');
    expect((await store.reviewed('store-a')).single.wire, c.wire);
  });

  test('تعثر الشبكة يعيد نفس الطلب ويؤكده بعد جلب سجل الخادم', () async {
    final c = Command.create('contact.create', ids.v4(), {'name': 'شخص'});
    await store.accept('store-a', snapshot(0));
    await store.enqueue('store-a', c);
    var attempts = 0, committed = false;
    final api = FakeApi((method, route, wire) async {
      if (method == 'GET') {
        return {
          'snapshot': snapshot(
            committed ? 1 : 0,
            contacts: committed
                ? [
                    {'id': c.entityId, 'name': 'شخص'},
                  ]
                : [],
          ).data,
        };
      }
      if (++attempts == 1) {
        throw const ApiFailure(0, 'انقطع الاتصال');
      }
      committed = true;
      return {
        'operationId': c.operationId,
        'status': 'committed',
        'epoch': epochA,
        'sequence': '1',
      };
    });
    final sync = SyncEngine(store, api);
    await sync.run(session);
    expect((await store.pending('store-a')).single.state, 'queued');
    await store.retry('store-a');
    await sync.run(session);
    expect(api.sent, [c.wire, c.wire]);
    expect(await store.pending('store-a'), isEmpty);
    expect(
      (await store.cache('store-a')).ledger!.contacts.single['id'],
      c.entityId,
    );
  });

  test('تعارض سلسلة لا يمنع اعتماد سلسلة أخرى', () async {
    final parent = Command.create('contact.create', ids.v4(), {'name': 'مكرر'});
    final child = Command.create(
      'debt.create',
      ids.v4(),
      {'contactId': parent.entityId, 'amountCents': 500},
      dependencies: [parent.operationId],
    );
    final independent = Command.create('contact.create', ids.v4(), {
      'name': 'مستقل',
    });
    await store.accept('store-a', snapshot(0));
    for (final c in [parent, child, independent]) {
      await store.enqueue('store-a', c);
    }
    var committed = false;
    final api = FakeApi((method, route, wire) async {
      if (method == 'GET') {
        return {'snapshot': snapshot(committed ? 1 : 0).data};
      }
      if (wire == parent.wire) {
        throw const ApiFailure(409, 'مكرر', code: 'duplicate_contact');
      }
      expect(wire, independent.wire);
      committed = true;
      return {
        'operationId': independent.operationId,
        'status': 'committed',
        'epoch': epochA,
        'sequence': '1',
      };
    });
    await SyncEngine(store, api).run(session);
    final remaining = await store.pending('store-a');
    expect(remaining.map((c) => c.operationId), [
      parent.operationId,
      child.operationId,
    ]);
    expect(remaining.every((c) => c.state == 'attention'), true);
    expect(api.sent, [parent.wire, independent.wire]);
  });

  test('مراجعة الرفض تحتفظ بالطلبات وتوقف العمليات التابعة', () async {
    final parent = Command.create('contact.create', 'person', {'name': 'شخص'});
    final child = Command.create(
      'debt.create',
      'debt',
      {'contactId': 'person', 'amountCents': 100},
      dependencies: [parent.operationId],
    );
    await store.enqueue('store-a', parent);
    await store.enqueue('store-a', child);
    await store.fail(parent, 'تعارض', 'conflict');
    await store.review(parent);
    expect((await store.pending('store-a')).single.state, 'attention');
    expect((await store.reviewed('store-a')).single.wire, parent.wire);
  });

  test('عامل مزامنة واحد لكل متجر واتصالات مستقلة للمتاجر', () async {
    final second = LedgerStore();
    await second.open(databasePath: databasePath);
    try {
      expect(await store.lease('store-a', 'foreground'), true);
      expect(await second.lease('store-a', 'background'), false);
      expect(await second.lease('store-b', 'background'), true);
      await store.release('store-a', 'wrong-owner');
      expect(await second.lease('store-a', 'background'), false);
      await store.release('store-a', 'foreground');
      expect(await second.lease('store-a', 'background'), true);
    } finally {
      await second.db.close();
    }
    expect((await store.cache('store-a')).ledger, isNull);
  });

  test('إعادة تطبيق عملية التجربة بعد توقف مفاجئ لا تكرر القيد', () async {
    final c = Command.create('contact.create', 'person', {'name': 'شخص'});
    await store.enqueue('demo', c);
    await store.demoCommit('demo', c, {});
    await store.demoCommit('demo', c, {});
    final ledger = (await store.cache('demo')).ledger!;
    expect(ledger.contacts.length, 1);
    expect(ledger.transactions.length, 1);
    expect(ledger.cursor, '1');
    expect(await store.pending('demo'), isEmpty);
  });
}
