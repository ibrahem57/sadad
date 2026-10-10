import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;

import 'config.dart';
import 'models.dart';

class ApiFailure implements Exception {
  final int status;
  final String message, code;
  final int retryAfter;
  const ApiFailure(
    this.status,
    this.message, {
    this.code = '',
    this.retryAfter = 0,
  });
  bool get retryable =>
      status == 0 ||
      status == 429 ||
      status >= 500 ||
      code == 'dependency_pending';
  @override
  String toString() => message;
}

class SadadApi {
  Future<Json> request(
    String method,
    String route, {
    String? token,
    Json? body,
    String? wire,
  }) async {
    final base = Uri.parse(AppConfig.apiUrl);
    if (base.scheme != 'https' || base.host.isEmpty) {
      throw const ApiFailure(0, 'عنوان الخادم يجب أن يستخدم HTTPS.');
    }
    final client = http.Client();
    try {
      final req =
          http.Request(
              method,
              Uri.parse(
                '${AppConfig.apiUrl.replaceAll(RegExp(r'/+$'), '')}/$route',
              ),
            )
            ..followRedirects = false
            ..headers.addAll({
              'Content-Type': 'application/json; charset=utf-8',
              'apikey': AppConfig.apiKey,
              if (token != null) 'Authorization': 'Bearer $token',
            });
      if (wire != null || body != null) {
        req.body = wire ?? jsonEncode(body);
      }
      final response = await http.Response.fromStream(
        await client.send(req).timeout(const Duration(seconds: 20)),
      ).timeout(const Duration(seconds: 20));
      Json result = {};
      try {
        result = jsonDecode(utf8.decode(response.bodyBytes)) as Json;
      } catch (_) {
        /* Untrusted non-JSON error body. */
      }
      if (response.statusCode < 200 ||
          response.statusCode >= 300 ||
          result['ok'] == false) {
        throw ApiFailure(
          response.statusCode,
          result['error']?.toString() ??
              result['message']?.toString() ??
              'تعذّر الاتصال بالخادم (${response.statusCode}).',
          code: result['code']?.toString() ?? '',
          retryAfter: int.tryParse(response.headers['retry-after'] ?? '') ?? 0,
        );
      }
      return result;
    } on ApiFailure {
      rethrow;
    } catch (_) {
      throw const ApiFailure(
        0,
        'لا يوجد اتصال بالخادم. العمليات محفوظة على الجهاز.',
      );
    } finally {
      client.close();
    }
  }
}
