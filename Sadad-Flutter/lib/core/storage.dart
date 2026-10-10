import 'dart:convert';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:path/path.dart' as path;
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sqflite/sqflite.dart';

import 'models.dart';

class SessionVault {
  static const secure = FlutterSecureStorage(
    iOptions: IOSOptions(
      accessibility: KeychainAccessibility.first_unlock_this_device,
    ),
  );
  Future<List<StoreSession>> all() async {
    final value = await secure.read(key: 'sadad.accounts.v4');
    return value == null
        ? []
        : (jsonDecode(value) as List)
              .map((v) => StoreSession.fromJson(Map<String, dynamic>.from(v)))
              .toList();
  }

  Future<void> save(StoreSession session) async {
    final sessions = await all();
    sessions.removeWhere((s) => s.scope == session.scope);
    if (sessions.length >= 3) {
      throw const FormatException('يمكن حفظ ثلاثة حسابات على هذا الجهاز.');
    }
    sessions.add(session);
    await secure.write(
      key: 'sadad.accounts.v4',
      value: jsonEncode(sessions.map((s) => s.toJson()).toList()),
    );
    await SharedPreferencesAsync().setString('sadad.active', session.scope);
  }

  Future<StoreSession?> active() async {
    final activeScope = await SharedPreferencesAsync().getString(
      'sadad.active',
    );
    return (await all()).where((s) => s.scope == activeScope).firstOrNull;
  }

  Future<String> deviceId() async {
    var value = await secure.read(key: 'sadad.device');
    if (value == null) {
      value = ids.v4();
      await secure.write(key: 'sadad.device', value: value);
    }
    return value;
  }

  Future<void> signOut() => SharedPreferencesAsync().remove('sadad.active');
  Future<void> forget(String scope) async {
    final sessions = await all();
    sessions.removeWhere((s) => s.scope == scope);
    await secure.write(
      key: 'sadad.accounts.v4',
      value: jsonEncode(sessions.map((s) => s.toJson()).toList()),
    );
  }
}

class CacheState {
  final Ledger? ledger;
  final bool recovery;
  final int refreshedAt;
  final String error;
  CacheState(this.ledger, this.recovery, this.refreshedAt, this.error);
}

class LedgerStore {
  late final Database db;
  Future<void> open({String? databasePath}) async {
    db = await openDatabase(
      databasePath ??
          path.join(await getDatabasesPath(), 'sadad-flutter-v4.db'),
      version: 1,
      singleInstance: false,
      onConfigure: (db) async {
        await db.execute('PRAGMA foreign_keys = ON');
        await db.execute('PRAGMA busy_timeout = 5000');
      },
      onCreate: (db, _) async {
        await db.execute(
          'CREATE TABLE cache(scope TEXT PRIMARY KEY,json TEXT NOT NULL,epoch TEXT NOT NULL,cursor INTEGER NOT NULL,refreshed_at INTEGER NOT NULL,recovery INTEGER NOT NULL DEFAULT 0,error TEXT)',
        );
        await db.execute(
          'CREATE TABLE journal(local_order INTEGER PRIMARY KEY AUTOINCREMENT,operation_id TEXT NOT NULL UNIQUE,scope TEXT NOT NULL,entity_id TEXT NOT NULL,type TEXT NOT NULL,wire TEXT NOT NULL,state TEXT NOT NULL DEFAULT \'queued\',error TEXT,error_code TEXT,ack_epoch TEXT,ack_sequence INTEGER,created_at INTEGER NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,next_attempt INTEGER NOT NULL DEFAULT 0)',
        );
        await db.execute(
          'CREATE INDEX journal_scope ON journal(scope,local_order)',
        );
        await db.execute(
          'CREATE TRIGGER immutable_wire BEFORE UPDATE OF operation_id,scope,entity_id,type,wire,created_at ON journal BEGIN SELECT RAISE(ABORT,\'immutable_command\'); END',
        );
        await db.execute(
          'CREATE TABLE sync_lease(scope TEXT PRIMARY KEY,owner TEXT NOT NULL,expires INTEGER NOT NULL)',
        );
      },
    );
  }

  Future<CacheState> cache(String scope) async {
    final rows = await db.query('cache', where: 'scope=?', whereArgs: [scope]);
    if (rows.isEmpty) {
      return CacheState(null, false, 0, '');
    }
    final r = rows.first;
    return CacheState(
      Ledger(jsonDecode(r['json'] as String)),
      r['recovery'] == 1,
      integer(r['refreshed_at']),
      '${r['error'] ?? ''}',
    );
  }

  Future<List<Command>> pending(String scope) async => (await db.query(
    'journal',
    where: "scope=? AND state NOT IN ('confirmed','reviewed')",
    whereArgs: [scope],
    orderBy: 'local_order',
  )).map(Command.fromRow).toList();
  Future<void> enqueue(String scope, Command command) async {
    await db.insert('journal', {
      'scope': scope,
      'operation_id': command.operationId,
      'entity_id': command.entityId,
      'type': command.type,
      'wire': command.wire,
      'created_at': command.createdAt,
    });
  }

  Future<bool> accept(String scope, Ledger ledger, {bool initial = false}) =>
      db.transaction((tx) async {
        final old = await tx.query(
          'cache',
          where: 'scope=?',
          whereArgs: [scope],
        );
        final changedEpoch =
            !initial &&
            old.isNotEmpty &&
            '${old.first['epoch']}'.isNotEmpty &&
            old.first['epoch'] != ledger.epoch;
        if (old.isNotEmpty &&
            !changedEpoch &&
            integer(old.first['cursor']) > integer(ledger.cursor)) {
          return false;
        }
        if (changedEpoch) {
          // A restore may have removed previously committed receipts. Keep their exact requests for review.
          await tx.update(
            'journal',
            {
              'state': 'attention',
              'error': 'تغيرت نسخة سجل الخادم. راجع العملية قبل إعادة إدخالها.',
              'error_code': 'epoch_changed',
            },
            where: "scope=? AND state NOT IN ('confirmed','reviewed')",
            whereArgs: [scope],
          );
        }
        final recovery =
            changedEpoch || (old.isNotEmpty && old.first['recovery'] == 1);
        await tx.insert('cache', {
          'scope': scope,
          'json': jsonEncode(ledger.data),
          'epoch': ledger.epoch,
          'cursor': integer(ledger.cursor),
          'refreshed_at': DateTime.now().millisecondsSinceEpoch,
          'recovery': recovery ? 1 : 0,
          'error': '',
        }, conflictAlgorithm: ConflictAlgorithm.replace);
        if (!recovery) {
          await tx.rawUpdate(
            "UPDATE journal SET state='confirmed',error=NULL WHERE scope=? AND state='acknowledged' AND ack_epoch=? AND ack_sequence<=?",
            [scope, ledger.epoch, integer(ledger.cursor)],
          );
        }
        return changedEpoch;
      });
  Future<void> acknowledge(String scope, Command c, Json receipt) async {
    await db.transaction((tx) async {
      await tx.update(
        'journal',
        {
          'state': 'acknowledged',
          'ack_epoch': receipt['epoch'],
          'ack_sequence': integer(receipt['sequence']),
          'error': null,
        },
        where: 'scope=? AND operation_id=?',
        whereArgs: [scope, c.operationId],
      );
      final cache = await tx.query(
        'cache',
        where: 'scope=?',
        whereArgs: [scope],
      );
      if (cache.isNotEmpty &&
          cache.first['recovery'] != 1 &&
          cache.first['epoch'] == receipt['epoch'] &&
          integer(cache.first['cursor']) >= integer(receipt['sequence'])) {
        await tx.update(
          'journal',
          {'state': 'confirmed'},
          where: 'operation_id=?',
          whereArgs: [c.operationId],
        );
      }
    });
  }

  Future<void> fail(
    Command c,
    String message,
    String code, {
    bool retry = false,
    int retryAfter = 0,
  }) async {
    final row = (await db.query(
      'journal',
      columns: ['attempts'],
      where: 'operation_id=?',
      whereArgs: [c.operationId],
    )).first;
    final attempts = integer(row['attempts']) + 1;
    final delay = retryAfter > 0 ? retryAfter : (2 << attempts.clamp(0, 8));
    await db.update(
      'journal',
      {
        'state': retry ? 'queued' : 'attention',
        'error': message,
        'error_code': code,
        'attempts': attempts,
        'next_attempt': DateTime.now().millisecondsSinceEpoch + delay * 1000,
      },
      where: 'operation_id=?',
      whereArgs: [c.operationId],
    );
  }

  Future<bool> ready(Command c) async {
    final row = (await db.query(
      'journal',
      where: 'operation_id=?',
      whereArgs: [c.operationId],
    )).first;
    if (integer(row['next_attempt']) > DateTime.now().millisecondsSinceEpoch) {
      return false;
    }
    for (final dependency in c.dependencies) {
      final deps = await db.query(
        'journal',
        columns: ['state'],
        where: 'operation_id=?',
        whereArgs: [dependency],
      );
      if (deps.isEmpty ||
          ['attention', 'reviewed'].contains(deps.first['state'])) {
        await fail(
          c,
          'عملية سابقة مرتبطة بهذا السجل تحتاج مراجعة. راجع السلسلة ثم أعد إدخال المطلوب.',
          'dependency_rejected',
        );
        return false;
      }
      if (!['confirmed', 'acknowledged'].contains(deps.first['state'])) {
        return false;
      }
    }
    return true;
  }

  Future<void> retry(String scope) => db.update(
    'journal',
    {'next_attempt': 0},
    where: "scope=? AND state='queued'",
    whereArgs: [scope],
  );
  Future<List<Command>> reviewed(String scope) async => (await db.query(
    'journal',
    where: "scope=? AND state='reviewed'",
    whereArgs: [scope],
    orderBy: 'local_order DESC',
  )).map(Command.fromRow).toList();
  Future<void> review(Command c) => db.transaction((tx) async {
    final original = await tx.query(
      'journal',
      where: 'operation_id=?',
      whereArgs: [c.operationId],
    );
    if (original.isEmpty || original.first['state'] != 'attention') {
      return;
    }
    await tx.update(
      'journal',
      {'state': 'reviewed'},
      where: 'operation_id=?',
      whereArgs: [c.operationId],
    );
    final rows = await tx.query(
      'journal',
      where: "scope=? AND state NOT IN ('confirmed','reviewed')",
      whereArgs: [original.first['scope']],
      orderBy: 'local_order',
    );
    final blocked = <String>{c.operationId};
    for (final row in rows) {
      final command = Command.fromRow(row);
      if (command.dependencies.any(blocked.contains)) {
        blocked.add(command.operationId);
        await tx.update(
          'journal',
          {
            'state': 'attention',
            'error_code': 'dependency_rejected',
            'error': 'راجع هذه العملية لأنها مرتبطة بإدخال لم يُعتمد.',
          },
          where: 'operation_id=?',
          whereArgs: [command.operationId],
        );
      }
    }
  });
  Future<void> finishRecovery(String scope) async {
    final rows = await pending(scope);
    if (rows.isNotEmpty) {
      throw const FormatException('راجع جميع العمليات المتأثرة أولًا.');
    }
    await db.update(
      'cache',
      {'recovery': 0},
      where: 'scope=?',
      whereArgs: [scope],
    );
  }

  Future<bool> lease(String scope, String owner) => db.transaction((tx) async {
    final now = DateTime.now().millisecondsSinceEpoch;
    await tx.delete(
      'sync_lease',
      where: 'scope=? AND expires<?',
      whereArgs: [scope, now],
    );
    await tx.rawInsert(
      'INSERT OR IGNORE INTO sync_lease(scope,owner,expires) VALUES(?,?,?)',
      [scope, owner, now + 120000],
    );
    final changed = await tx.rawQuery('SELECT changes() AS count');
    return integer(changed.first['count']) == 1;
  });
  Future<bool> renewLease(String scope, String owner) async =>
      await db.update(
        'sync_lease',
        {'expires': DateTime.now().millisecondsSinceEpoch + 120000},
        where: 'scope=? AND owner=?',
        whereArgs: [scope, owner],
      ) ==
      1;
  Future<void> release(String scope, String owner) => db.delete(
    'sync_lease',
    where: 'scope=? AND owner=?',
    whereArgs: [scope, owner],
  );
  Future<void> demoCommit(String scope, Command command, Json account) async {
    await db.transaction((tx) async {
      final journal = await tx.query(
        'journal',
        where: 'scope=? AND operation_id=?',
        whereArgs: [scope, command.operationId],
      );
      if (journal.isEmpty || journal.first['state'] != 'queued') {
        return;
      }
      final saved = await tx.query(
        'cache',
        where: 'scope=?',
        whereArgs: [scope],
      );
      final current = saved.isEmpty
          ? Ledger.empty(account)
          : Ledger(jsonDecode(saved.first['json'] as String));
      current.apply(command);
      for (final event in current.transactions) {
        event.remove('pending');
      }
      current.data['cursor'] = '${integer(current.cursor) + 1}';
      current.data['epoch'] = 'demo-local';
      await tx.insert('cache', {
        'scope': scope,
        'json': jsonEncode(current.data),
        'epoch': current.epoch,
        'cursor': integer(current.cursor),
        'refreshed_at': DateTime.now().millisecondsSinceEpoch,
        'recovery': 0,
        'error': '',
      }, conflictAlgorithm: ConflictAlgorithm.replace);
      await tx.update(
        'journal',
        {
          'state': 'confirmed',
          'ack_epoch': current.epoch,
          'ack_sequence': integer(current.cursor),
        },
        where: 'operation_id=?',
        whereArgs: [command.operationId],
      );
    });
  }
}
