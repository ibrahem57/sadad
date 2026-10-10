import 'dart:convert';
import 'dart:math';

import 'package:cryptography/cryptography.dart';
import 'package:local_auth/local_auth.dart';

import 'models.dart';
import 'storage.dart';

class LocalLock {
  String _key(String scope) => 'sadad.lock.$scope';
  Future<Json?> settings(String scope) async {
    final value = await SessionVault.secure.read(key: _key(scope));
    return value == null ? null : jsonDecode(value) as Json;
  }

  Future<List<int>> _hash(String pin, List<int> salt) async =>
      (await Pbkdf2(
            macAlgorithm: Hmac.sha256(),
            iterations: 150000,
            bits: 256,
          ).deriveKey(secretKey: SecretKey(utf8.encode(pin)), nonce: salt))
          .extractBytes();
  Future<bool> verify(String scope, String pin) async {
    final s = await settings(scope);
    if (s == null) {
      return true;
    }
    final waitUntil = integer(s['waitUntil']);
    if (DateTime.now().millisecondsSinceEpoch < waitUntil) {
      throw const FormatException('انتظر قليلًا قبل المحاولة التالية.');
    }
    final actual = await _hash(western(pin), base64Decode(s['salt']));
    final expected = base64Decode(s['hash']);
    var diff = actual.length ^ expected.length;
    for (var i = 0; i < actual.length && i < expected.length; i++) {
      diff |= actual[i] ^ expected[i];
    }
    if (diff == 0) {
      s['failures'] = 0;
      s['waitUntil'] = 0;
    } else {
      final failures = integer(s['failures']) + 1;
      s['failures'] = failures;
      if (failures >= 5) {
        s['waitUntil'] =
            DateTime.now().millisecondsSinceEpoch +
            min(300, 30 * (failures - 4)) * 1000;
      }
    }
    await SessionVault.secure.write(key: _key(scope), value: jsonEncode(s));
    return diff == 0;
  }

  Future<void> configure(
    String scope,
    String pin, {
    bool biometrics = false,
    String? currentPin,
  }) async {
    if (await settings(scope) != null &&
        (currentPin == null || !await verify(scope, currentPin))) {
      throw const FormatException('رمز القفل الحالي غير صحيح.');
    }
    if (pin.isEmpty) {
      await SessionVault.secure.delete(key: _key(scope));
      return;
    }
    pin = western(pin);
    if (!RegExp(r'^\d{4,8}$').hasMatch(pin)) {
      throw const FormatException('رمز القفل من 4 إلى 8 أرقام.');
    }
    if (biometrics && !await authenticate()) {
      throw const FormatException('لم يتم تأكيد البصمة.');
    }
    final random = Random.secure(),
        salt = List<int>.generate(32, (_) => random.nextInt(256));
    await SessionVault.secure.write(
      key: _key(scope),
      value: jsonEncode({
        'salt': base64Encode(salt),
        'hash': base64Encode(await _hash(pin, salt)),
        'biometrics': biometrics,
        'failures': 0,
        'waitUntil': 0,
      }),
    );
  }

  Future<bool> authenticate() async {
    try {
      final auth = LocalAuthentication();
      if (!await auth.canCheckBiometrics) {
        return false;
      }
      return await auth.authenticate(
        localizedReason: 'افتح سجل سدد المحفوظ على هذا الجهاز',
        biometricOnly: true,
        persistAcrossBackgrounding: true,
      );
    } catch (_) {
      return false;
    }
  }
}
