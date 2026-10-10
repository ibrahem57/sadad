import 'dart:async';
import 'dart:io';

import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'api.dart';
import 'config.dart';
import 'models.dart';
import 'security.dart';
import 'storage.dart';

class SyncEngine {
  final LedgerStore store;
  final SadadApi api;
  SyncEngine(this.store, this.api);
  Future<void> run(
    StoreSession session, {
    Future<void> Function(Json)? accountChanged,
  }) async {
    if (session.demo) {
      return;
    }
    final owner = ids.v4();
    if (!await store.lease(session.scope, owner)) {
      return;
    }
    try {
      // Pull first: a restored server epoch must be detected before any queued write.
      var state = await store.cache(session.scope);
      final suffix = state.ledger?.epoch.isNotEmpty == true
          ? '?after=${state.ledger!.cursor}&epoch=${state.ledger!.epoch}'
          : '';
      Json response;
      try {
        response = await api.request(
          'GET',
          'mobile/v3/snapshot$suffix',
          token: session.token,
        );
      } on ApiFailure catch (e) {
        if (e.status == 404 || e.code == 'migration_required') {
          // Compatibility is read-only. A cached legacy snapshot is never uploaded.
          final old = await api.request(
            'GET',
            'mobile/snapshot',
            token: session.token,
          );
          if (old['snapshot'] != null) {
            await store.accept(
              session.scope,
              Ledger(Map<String, dynamic>.from(old['snapshot'])),
            );
          }
          throw const ApiFailure(
            409,
            'بيانات الحساب للعرض. يلزم استكمال ترحيل الخادم قبل اعتماد عمليات Flutter.',
            code: 'migration_required',
          );
        }
        rethrow;
      }
      if (response['account'] != null && accountChanged != null) {
        await accountChanged(Map<String, dynamic>.from(response['account']));
      }
      if (response['snapshot'] != null) {
        await store.accept(
          session.scope,
          Ledger(Map<String, dynamic>.from(response['snapshot'])),
        );
      }
      state = await store.cache(session.scope);
      if (state.recovery) {
        throw const ApiFailure(
          409,
          'تغير سجل الخادم بعد الاستعادة. راجع العمليات المحفوظة.',
          code: 'epoch_changed',
        );
      }
      final pending = await store.pending(session.scope);
      var acknowledged = pending.any((c) => c.state == 'acknowledged');
      for (final c in pending.where((c) => c.state == 'queued')) {
        if (!await store.renewLease(session.scope, owner)) {
          return;
        }
        if (!await store.ready(c)) {
          continue;
        }
        try {
          final receipt = await api.request(
            'POST',
            'mobile/v3/operations',
            token: session.token,
            wire: c.wire,
          );
          if (receipt['operationId'] != c.operationId ||
              receipt['status'] != 'committed' ||
              receipt['epoch'] == null ||
              receipt['sequence'] == null) {
            throw const ApiFailure(
              502,
              'رد الخادم غير مكتمل. سنراجع اعتماد العملية عند المزامنة.',
            );
          }
          await store.acknowledge(session.scope, c, receipt);
          acknowledged = true;
        } on ApiFailure catch (e) {
          // Auth failures preserve input and wait for the user to sign in again.
          if (e.status == 401) {
            rethrow;
          }
          await store.fail(
            c,
            e.message,
            e.code,
            retry: e.retryable,
            retryAfter: e.retryAfter,
          );
          if (e.retryable && e.code != 'dependency_pending') {
            break;
          }
        }
      }
      if (acknowledged) {
        final fresh = await api.request(
          'GET',
          'mobile/v3/snapshot',
          token: session.token,
        );
        if (fresh['snapshot'] != null) {
          await store.accept(
            session.scope,
            Ledger(Map<String, dynamic>.from(fresh['snapshot'])),
          );
        }
      }
    } finally {
      await store.release(session.scope, owner);
    }
  }
}

class SadadController extends ChangeNotifier {
  final LedgerStore store;
  final vault = SessionVault(), api = SadadApi(), lock = LocalLock();
  StoreSession? session;
  CacheState cache = CacheState(null, false, 0, '');
  List<Command> pending = [];
  List<StoreSession> accounts = [];
  bool loading = true,
      syncing = false,
      locked = false,
      needsLogin = false,
      backendCompatible = true,
      privacySuspended = false;
  String error = '';
  String startupError = '';
  ThemeMode themeMode = ThemeMode.system;
  StreamSubscription<List<ConnectivityResult>>? connectivity;
  Timer? timer;
  late final SyncEngine engine = SyncEngine(store, api);
  SadadController(this.store);
  Ledger get confirmed => cache.ledger ?? Ledger.empty(session?.account ?? {});
  Ledger get visible => confirmed.projected(pending);
  bool get canWrite =>
      session != null &&
      !needsLogin &&
      !cache.recovery &&
      backendCompatible &&
      cache.ledger != null &&
      !session!.forcePasswordChange &&
      (session!.demo ||
          integer(session!.account['ledgerVersion']) == 3 &&
              RegExp(r'^[0-9a-fA-F-]{36}$').hasMatch(cache.ledger!.epoch));
  Future<void> init() async {
    startupError = '';
    loading = true;
    notifyListeners();
    await connectivity?.cancel();
    timer?.cancel();
    try {
      final theme = await SharedPreferencesAsync().getString('sadad.theme');
      themeMode =
          ThemeMode.values.where((m) => m.name == theme).firstOrNull ??
          ThemeMode.system;
      accounts = await vault.all();
      session = await vault.active();
      if (session != null) {
        await reload();
        locked = await lock.settings(session!.scope) != null;
      }
    } catch (_) {
      startupError = 'تعذّر فتح الحسابات أو البيانات المحلية. أعد المحاولة دون حذف بيانات التطبيق.';
      loading = false;
      notifyListeners();
      return;
    }
    loading = false;
    notifyListeners();
    connectivity = Connectivity().onConnectivityChanged.listen((results) {
      if (results.any((r) => r != ConnectivityResult.none)) {
        unawaited(sync());
      }
    });
    timer = Timer.periodic(const Duration(seconds: 30), (_) {
      unawaited(sync());
    });
    unawaited(sync());
  }

  Future<void> reload() async {
    final current = session;
    if (current == null) {
      return;
    }
    if (current.demo) {
      for (final command in (await store.pending(
        current.scope,
      )).where((c) => c.state == 'queued')) {
        await store.demoCommit(current.scope, command, current.account);
      }
    }
    final newCache = await store.cache(current.scope),
        journal = await store.pending(current.scope);
    if (session?.scope != current.scope) {
      return;
    }
    cache = newCache;
    pending = journal;
    notifyListeners();
  }

  Future<void> login(String username, String password, String staffName) async {
    if (syncing) {
      throw const FormatException(
        'انتظر انتهاء المزامنة الحالية ثم حاول الدخول.',
      );
    }
    if (username.trim().isEmpty || password.isEmpty) {
      throw const FormatException('أدخل اسم المستخدم وكلمة المرور.');
    }
    final deviceId = await vault.deviceId();
    StoreSession next;
    Ledger? initial;
    if (AppConfig.demo) {
      if (!((username == '123' && password == '123') ||
          (username == '12345' && password == '12345'))) {
        throw const FormatException('بيانات دخول التجربة: 123 / 123.');
      }
      next = StoreSession(
        scope: 'demo:merchant',
        token: 'offline-demo',
        deviceId: deviceId,
        staffName: 'صاحب المتجر',
        demo: true,
        account: {
          'id': 'demo',
          'name': 'متجر التجربة',
          'username': username,
          'ledgerVersion': 3,
          'subscriptionMode': 'permanent',
          'permissions': {},
          'maxDevices': 1,
        },
      );
      final old = await store.cache(next.scope);
      if (old.ledger == null) {
        initial = Ledger.empty(next.account);
        initial.data['epoch'] = 'demo-local';
      }
    } else {
      final response = await api.request(
        'POST',
        'mobile/login',
        body: {
          'username': username.trim(),
          'password': password,
          'deviceId': deviceId,
          'deviceName': Platform.isIOS
              ? 'iPhone • Flutter'
              : 'Android • Flutter',
          'staffName': staffName.trim(),
        },
      );
      final account = Map<String, dynamic>.from(
        response['account'] ?? response['snapshot']?['account'] ?? {},
      );
      final token = '${response['token'] ?? ''}';
      if (token.isEmpty || account['id'] == null) {
        throw const ApiFailure(
          502,
          'بيانات الدخول التي أعادها الخادم غير مكتملة.',
        );
      }
      next = StoreSession(
        scope: '${AppConfig.apiUrl}:${account['id']}',
        token: token,
        deviceId: deviceId,
        account: account,
        staffName: response['deviceStaffName'] ?? staffName,
        forcePasswordChange: response['forcePasswordChange'] == true,
        devicePermissions: Map<String, dynamic>.from(
          response['device']?['permissions'] ?? {},
        ),
      );
      if (response['snapshot'] != null) {
        initial = Ledger(Map<String, dynamic>.from(response['snapshot']));
      }
    }
    await vault.save(next);
    if (initial != null) {
      await store.accept(next.scope, initial);
    }
    session = next;
    backendCompatible = true;
    needsLogin = false;
    error = '';
    locked = await lock.settings(next.scope) != null;
    accounts = await vault.all();
    await reload();
    unawaited(sync());
  }

  Future<void> switchAccount(StoreSession next) async {
    if (syncing) {
      throw const FormatException('انتظر انتهاء المزامنة الحالية.');
    }
    await vault.save(next);
    session = next;
    needsLogin = false;
    backendCompatible = true;
    error = '';
    locked = await lock.settings(next.scope) != null;
    await reload();
    unawaited(sync());
  }

  Future<void> logout() async {
    if (syncing) {
      throw const FormatException('انتظر انتهاء المزامنة الحالية.');
    }
    await vault.signOut();
    session = null;
    locked = false;
    needsLogin = false;
    cache = CacheState(null, false, 0, '');
    pending = [];
    notifyListeners();
  }

  Future<void> sync({bool retryNow = false}) async {
    final current = session;
    if (current == null || current.demo || syncing || needsLogin) {
      return;
    }
    syncing = true;
    notifyListeners();
    try {
      if (retryNow) {
        await store.retry(current.scope);
      }
      await engine.run(
        current,
        accountChanged: (account) async {
          if (session?.scope != current.scope) {
            return;
          }
          session = session!.updated(account: account);
          await vault.save(session!);
        },
      );
      error = '';
      backendCompatible = true;
    } on ApiFailure catch (e) {
      error = e.message;
      needsLogin = e.status == 401;
      if (e.code == 'migration_required') {
        backendCompatible = false;
      }
    } catch (_) {
      error = 'تعذّرت المزامنة. البيانات والعمليات محفوظة على الجهاز.';
    } finally {
      syncing = false;
      await reload();
      notifyListeners();
    }
  }

  Future<void> forget(StoreSession target) async {
    if (syncing) {
      throw const FormatException('انتظر انتهاء المزامنة الحالية.');
    }
    await vault.forget(target.scope);
    if (session?.scope == target.scope) {
      await logout();
    }
    accounts = await vault.all();
    notifyListeners();
  }

  Future<Command> submit(
    String type,
    String entityId,
    Json payload, {
    String? version,
  }) async {
    if (!canWrite) {
      throw FormatException(
        error.isEmpty
            ? 'يلزم تسجيل الدخول واستكمال إعداد الحساب قبل حفظ عملية جديدة.'
            : error,
      );
    }
    final current = session!;
    final feature = type.startsWith('contact.')
        ? 'contacts'
        : type.startsWith('debt.')
        ? 'debts'
        : 'payments';
    if (!current.allowed(feature)) {
      throw const FormatException('هذه الميزة غير مفعّلة لحسابك.');
    }
    if (type == 'payment.create' &&
        !current.deviceAllowed('registerPayments')) {
      throw const FormatException('هذا الجهاز لا يملك صلاحية تسجيل دفعات.');
    }
    if (type == 'contact.archive' && !current.deviceAllowed('deleteContacts') ||
        (type == 'payment.reverse' || type == 'debt.correct') &&
            !current.deviceAllowed('deleteRecords')) {
      throw const FormatException('لا يملك هذا الجهاز صلاحية هذا التعديل.');
    }
    if (type == 'contact.create') {
      final limit = integer(current.account['debtorLimit']);
      if (limit > 0 &&
          visible.contacts.where((c) => c['archivedAt'] == null).length >=
              limit) {
        throw const FormatException(
          'وصل المتجر إلى الحد المسموح لعدد الأشخاص.',
        );
      }
      final phone = normalizedPhone('${payload['phone'] ?? ''}');
      if (phone.isNotEmpty &&
          visible.contacts.any(
            (c) => normalizedPhone('${c['phone'] ?? ''}') == phone,
          )) {
        throw const FormatException(
          'رقم الهاتف مسجل لشخص آخر. افتح سجله الحالي.',
        );
      }
    }
    if (type == 'payment.create') {
      final now = DateTime.now().millisecondsSinceEpoch;
      final contactId = visible.debt('${payload['debtId']}')?['contactId'];
      if (visible.payments.any(
        (p) =>
            visible.debt('${p['debtId']}')?['contactId'] == contactId &&
            cents(p, 'amount') == payload['amountCents'] &&
            p['method'] == payload['method'] &&
            now - integer(p['createdAt']) < 60000 &&
            p['reversedAt'] == null,
      )) {
        throw const FormatException(
          'سُجلت دفعة بهذا المبلغ والطريقة للشخص خلال دقيقة. راجع السجل أولًا.',
        );
      }
    }
    final relatedIds = {
      entityId,
      '${payload['contactId'] ?? ''}',
      '${payload['debtId'] ?? ''}',
      '${payload['paymentId'] ?? ''}',
    };
    final dependencies = pending
        .where((c) => relatedIds.contains(c.entityId) && c.state != 'attention')
        .map((c) => c.operationId)
        .toList();
    if (pending.any(
      (c) => c.state == 'attention' && relatedIds.contains(c.entityId),
    )) {
      throw const FormatException(
        'توجد عملية لهذا السجل تحتاج مراجعة. افتح مركز المزامنة أولًا.',
      );
    }
    final command = Command.create(
      type,
      entityId,
      payload,
      version: version,
      dependencies: dependencies,
    );
    await store.enqueue(current.scope, command);
    if (current.demo) {
      await store.demoCommit(current.scope, command, current.account);
    }
    await reload();
    unawaited(sync());
    return command;
  }

  Future<void> changePassword(String oldPassword, String newPassword) async {
    if (session!.demo) {
      throw const FormatException('تغيير كلمة المرور متاح للحساب الرسمي.');
    }
    await api.request(
      'POST',
      'mobile/change-password',
      token: session!.token,
      body: {'currentPassword': oldPassword, 'password': newPassword},
    );
    session = session!.updated(forcePasswordChange: false);
    await vault.save(session!);
    notifyListeners();
    unawaited(sync());
  }

  Future<void> setTheme(ThemeMode mode) async {
    themeMode = mode;
    await SharedPreferencesAsync().setString('sadad.theme', mode.name);
    notifyListeners();
  }

  Future<void> lockNow() async {
    if (session != null &&
        await lock.settings(session!.scope) != null &&
        !privacySuspended) {
      locked = true;
      notifyListeners();
    }
  }

  Future<bool> unlock(String pin) async {
    final result = await lock.verify(session!.scope, pin);
    if (result) {
      locked = false;
      notifyListeners();
    }
    return result;
  }

  Future<bool> biometricUnlock() async {
    if ((await lock.settings(session!.scope))?['biometrics'] != true) {
      return false;
    }
    privacySuspended = true;
    try {
      final result = await lock.authenticate();
      if (result) {
        locked = false;
        notifyListeners();
      }
      return result;
    } finally {
      privacySuspended = false;
    }
  }

  @override
  void dispose() {
    connectivity?.cancel();
    timer?.cancel();
    super.dispose();
  }
}
