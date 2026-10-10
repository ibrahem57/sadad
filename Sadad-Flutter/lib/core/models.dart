import 'dart:convert';

import 'package:uuid/uuid.dart';

typedef Json = Map<String, dynamic>;
const ids = Uuid();
int integer(dynamic value) =>
    value is num ? value.toInt() : int.tryParse('$value') ?? 0;
String idOf(Json row) => '${row['id']}';
int cents(Json row, String key) => row.containsKey('${key}Cents')
    ? integer(row['${key}Cents'])
    : ((num.tryParse('${row[key]}') ?? 0) * 100).round();
String money(int value) => '${(value / 100).toStringAsFixed(2)} ₪';
String western(String input) {
  const arabic = '٠١٢٣٤٥٦٧٨٩';
  const persian = '۰۱۲۳۴۵۶۷۸۹';
  for (var i = 0; i < 10; i++) {
    input = input.replaceAll(arabic[i], '$i').replaceAll(persian[i], '$i');
  }
  return input;
}

int parseMoney(String text, {bool allowZero = false}) {
  final value = western(text.trim()).replaceAll('٫', '.');
  if (!RegExp(r'^\d{1,10}(\.\d{1,2})?$').hasMatch(value)) {
    throw const FormatException(
      'أدخل مبلغًا صحيحًا، وبحد أقصى منزلتين بعد الفاصلة.',
    );
  }
  final parts = value.split('.');
  final result =
      int.parse(parts[0]) * 100 +
      int.parse(parts.length == 1 ? '0' : parts[1].padRight(2, '0'));
  if ((!allowZero && result <= 0) || result > 100000000000) {
    throw const FormatException('المبلغ خارج النطاق المسموح.');
  }
  return result;
}

String normalizedPhone(String phone) =>
    western(phone)
        .replaceAll(RegExp(r'[^0-9]'), '')
        .replaceFirst(RegExp(r'^00'), '');

class StoreSession {
  final String scope, token, deviceId, staffName;
  final Json account, devicePermissions;
  final bool forcePasswordChange, demo;
  StoreSession({
    required this.scope,
    required this.token,
    required this.deviceId,
    required this.account,
    this.staffName = '',
    this.forcePasswordChange = false,
    this.devicePermissions = const {},
    this.demo = false,
  });
  factory StoreSession.fromJson(Json j) => StoreSession(
    scope: j['scope'],
    token: j['token'],
    deviceId: j['deviceId'],
    account: Map<String, dynamic>.from(j['account']),
    staffName: j['staffName'] ?? '',
    forcePasswordChange: j['forcePasswordChange'] == true,
    devicePermissions: Map<String, dynamic>.from(j['devicePermissions'] ?? {}),
    demo: j['demo'] == true,
  );
  Json toJson() => {
    'scope': scope,
    'token': token,
    'deviceId': deviceId,
    'account': account,
    'staffName': staffName,
    'forcePasswordChange': forcePasswordChange,
    'devicePermissions': devicePermissions,
    'demo': demo,
  };
  bool allowed(String feature) =>
      (account['permissions'] as Map? ?? {})[feature] != false;
  bool deviceAllowed(String feature) => devicePermissions[feature] != false;
  StoreSession updated({Json? account, bool? forcePasswordChange}) =>
      StoreSession(
        scope: scope,
        token: token,
        deviceId: deviceId,
        account: account ?? this.account,
        staffName: staffName,
        devicePermissions: devicePermissions,
        demo: demo,
        forcePasswordChange: forcePasswordChange ?? this.forcePasswordChange,
      );
}

class Command {
  final String operationId, entityId, type, wire, state, error;
  final int order, createdAt;
  Command({
    required this.operationId,
    required this.entityId,
    required this.type,
    required this.wire,
    this.state = 'queued',
    this.error = '',
    this.order = 0,
    required this.createdAt,
  });
  factory Command.create(
    String type,
    String entityId,
    Json payload, {
    String? version,
    List<String> dependencies = const [],
  }) {
    final operation = ids.v4(), time = DateTime.now().millisecondsSinceEpoch;
    final envelope = <String, dynamic>{
      'schemaVersion': 1,
      'operationId': operation,
      'type': type,
      'entityId': entityId,
      'createdAt': time,
      'expectedVersion': ?version,
      'dependsOn': dependencies,
      'payload': payload,
    };
    return Command(
      operationId: operation,
      entityId: entityId,
      type: type,
      wire: jsonEncode(envelope),
      createdAt: time,
    );
  }
  factory Command.fromRow(Json row) => Command(
    operationId: row['operation_id'],
    entityId: row['entity_id'],
    type: row['type'],
    wire: row['wire'],
    state: row['state'],
    error: row['error'] ?? '',
    order: integer(row['local_order']),
    createdAt: integer(row['created_at']),
  );
  Json get envelope => jsonDecode(wire) as Json;
  Json get payload => Map<String, dynamic>.from(envelope['payload']);
  List<String> get dependencies =>
      List<String>.from(envelope['dependsOn'] ?? []);
}

class Ledger {
  final Json data;
  Ledger(Json value) : data = jsonDecode(jsonEncode(value)) as Json;
  factory Ledger.empty(Json account) => Ledger({
    'contacts': [],
    'debts': [],
    'payments': [],
    'transactions': [],
    'account': account,
    'totals': {'receivableCents': 0},
    'cursor': '0',
    'epoch': '',
    'currency': 'ILS',
  });
  List<Json> get contacts => (data['contacts'] as List? ?? []).cast<Json>();
  List<Json> get debts => (data['debts'] as List? ?? []).cast<Json>();
  List<Json> get payments => (data['payments'] as List? ?? []).cast<Json>();
  List<Json> get transactions =>
      (data['transactions'] as List? ?? []).cast<Json>();
  String get cursor => '${data['cursor'] ?? data['revision'] ?? 0}';
  String get epoch => '${data['epoch'] ?? ''}';
  Json? contact(String id) => contacts.where((c) => idOf(c) == id).firstOrNull;
  Json? debt(String id) => debts.where((c) => idOf(c) == id).firstOrNull;
  int balance(String id) => debts
      .where((d) => '${d['contactId']}' == id && d['direction'] != 'payable')
      .fold(0, (sum, d) => sum + cents(d, 'remaining'));
  int get total => debts
      .where((d) => d['direction'] != 'payable')
      .fold(0, (sum, d) => sum + cents(d, 'remaining'));
  Ledger projected(List<Command> commands) {
    final next = Ledger(data);
    final blocked = commands
        .where((c) => c.state == 'attention')
        .map((c) => c.operationId)
        .toSet();
    for (final c in commands) {
      if (blocked.contains(c.operationId) ||
          c.dependencies.any(blocked.contains)) {
        blocked.add(c.operationId);
        continue;
      }
      next.apply(c);
    }
    return next;
  }

  // Projection is only a preview. Official reports always use the canonical cache.
  void apply(Command c) {
    if (transactions.any((event) => '${event['id']}' == c.operationId)) {
      return;
    }
    final p = c.payload, id = c.entityId;
    var contactId = '${p['contactId'] ?? ''}';
    switch (c.type) {
      case 'contact.create':
        if (contact(id) == null) {
          contacts.add({
            'id': id,
            ...p,
            'createdAt': c.createdAt,
            'version': '1',
          });
        }
        contactId = id;
      case 'contact.update':
        final row = contact(id);
        if (row != null) {
          row.addAll(p);
          row['version'] = '${integer(row['version']) + 1}';
        }
        contactId = id;
      case 'contact.archive':
        final row = contact(id);
        if (row != null) {
          row['archivedAt'] = c.createdAt;
          row['archiveReason'] = p['reason'];
          row['version'] = '${integer(row['version']) + 1}';
        }
        contactId = id;
      case 'contact.restore':
        final row = contact(id);
        if (row != null) {
          row['archivedAt'] = null;
          row['version'] = '${integer(row['version']) + 1}';
        }
        contactId = id;
      case 'debt.create':
        if (debt(id) == null) {
          debts.add({'id': id, ...p, 'createdAt': c.createdAt, 'version': '1'});
        }
      case 'debt.correct':
        final row = debt(id);
        if (row != null) {
          row.addAll(p);
          row['version'] = '${integer(row['version']) + 1}';
          contactId = '${row['contactId']}';
        }
      case 'payment.create':
        if (!payments.any((r) => idOf(r) == id)) {
          payments.add({'id': id, ...p, 'createdAt': c.createdAt});
        }
        contactId = '${debt('${p['debtId']}')?['contactId'] ?? ''}';
      case 'payment.reverse':
        final row = payments
            .where((r) => idOf(r) == '${p['paymentId']}')
            .firstOrNull;
        if (row != null) {
          row['reversedAt'] = c.createdAt;
          contactId = '${debt('${row['debtId']}')?['contactId'] ?? ''}';
        }
    }
    recalculate();
    transactions.add({
      'id': c.operationId,
      'kind': c.type,
      'contactId': contactId,
      'contactName': contact(contactId)?['name'] ?? '',
      'createdAt': c.createdAt,
      'amountCents': p['amountCents'] ?? 0,
      'note': p['note'] ?? p['reason'] ?? '',
      'pending': true,
    });
  }

  void recalculate() {
    for (final d in debts) {
      final paid = payments
          .where((p) => '${p['debtId']}' == idOf(d) && p['reversedAt'] == null)
          .fold(0, (sum, p) => sum + cents(p, 'amount'));
      d['amountCents'] = cents(d, 'amount');
      d['paidCents'] = paid;
      d['remainingCents'] = (cents(d, 'amount') - paid).clamp(0, 100000000000);
      d['creditCents'] = (paid - cents(d, 'amount')).clamp(0, 100000000000);
    }
    for (final c in contacts) {
      c['receivableCents'] = balance(idOf(c));
    }
    data['totals'] = {'receivableCents': total};
  }
}
