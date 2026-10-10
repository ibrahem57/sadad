import 'package:flutter/material.dart';
import 'package:intl/intl.dart' show DateFormat;
import 'package:speech_to_text/speech_to_text.dart';

import '../core/controller.dart';
import '../core/models.dart';

String dateLabel(dynamic timestamp) {
  final time = integer(timestamp);
  return time == 0
      ? '—'
      : DateFormat(
          'yyyy/MM/dd · HH:mm',
          'en',
        ).format(DateTime.fromMillisecondsSinceEpoch(time));
}

String operationLabel(String type) => switch (type) {
  'contact.create' => 'إضافة شخص',
  'contact.update' => 'تعديل شخص',
  'contact.archive' => 'أرشفة شخص',
  'contact.restore' => 'استعادة شخص',
  'debt.create' || 'debt' => 'تسجيل دين',
  'debt.correct' => 'تصحيح دين',
  'payment.create' || 'payment' => 'تسجيل دفعة',
  'payment.reverse' => 'عكس دفعة',
  _ => type,
};
void notice(BuildContext context, Object error) {
  final text = error is FormatException ? error.message : '$error';
  ScaffoldMessenger.of(context).showSnackBar(
    SnackBar(content: Text(text), behavior: SnackBarBehavior.floating),
  );
}

Future<bool> review(
  BuildContext context,
  String title,
  String body, {
  String action = 'حفظ العملية',
}) async =>
    await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(title),
        content: Text(body),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('رجوع'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: Text(action),
          ),
        ],
      ),
    ) ??
    false;
Future<void> openPage(BuildContext context, Widget page) =>
    Navigator.of(context).push<void>(MaterialPageRoute(builder: (_) => page));
Widget constrained(Widget child) => Align(
  alignment: Alignment.topCenter,
  child: ConstrainedBox(
    constraints: const BoxConstraints(maxWidth: 760),
    child: child,
  ),
);

class Amount extends StatelessWidget {
  final int value;
  final TextStyle? style;
  const Amount(this.value, {super.key, this.style});
  @override
  Widget build(BuildContext context) => Directionality(
    textDirection: TextDirection.ltr,
    child: Text(
      money(value),
      style:
          style ??
          Theme.of(context).textTheme.titleMedium
              ?.copyWith(fontWeight: FontWeight.bold),
    ),
  );
}

class Brand extends StatelessWidget {
  final double size;
  const Brand({super.key, this.size = 100});
  @override
  Widget build(BuildContext context) => ClipRRect(
    borderRadius: BorderRadius.circular(16),
    child: Image.asset(
      Theme.of(context).brightness == Brightness.dark
          ? 'assets/images/logo-dark.png'
          : 'assets/images/logo-light.jpeg',
      width: size,
      height: size,
      fit: BoxFit.contain,
    ),
  );
}

class EmptyState extends StatelessWidget {
  final IconData icon;
  final String title, body;
  const EmptyState(
    this.title,
    this.body, {
    super.key,
    this.icon = Icons.inbox_outlined,
  });
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(horizontal: 28, vertical: 48),
    child: Column(
      children: [
        Icon(icon, size: 48, color: Theme.of(context).colorScheme.primary),
        const SizedBox(height: 16),
        Text(
          title,
          style: Theme.of(context).textTheme.titleLarge,
          textAlign: TextAlign.center,
        ),
        const SizedBox(height: 8),
        Text(body, textAlign: TextAlign.center),
      ],
    ),
  );
}

class SectionTitle extends StatelessWidget {
  final String text;
  final Widget? action;
  const SectionTitle(this.text, {super.key, this.action});
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 12),
    child: Row(
      children: [
        Expanded(
          child: Text(
            text,
            style: Theme.of(context).textTheme.titleLarge
                ?.copyWith(fontWeight: FontWeight.bold),
          ),
        ),
        ?action,
      ],
    ),
  );
}

class VoiceField extends StatefulWidget {
  final TextEditingController controller;
  final String label;
  final bool moneyInput, requiredField, secret, voice;
  final SadadController? app;
  final int maxLines;
  const VoiceField({
    super.key,
    required this.controller,
    required this.label,
    this.moneyInput = false,
    this.requiredField = false,
    this.secret = false,
    this.voice = false,
    this.app,
    this.maxLines = 1,
  });
  @override
  State<VoiceField> createState() => _VoiceFieldState();
}

class _VoiceFieldState extends State<VoiceField> {
  final speech = SpeechToText();
  bool listening = false, obscure = true;
  Future<void> record() async {
    if (listening) {
      await speech.stop();
      if (mounted) {
        setState(() => listening = false);
      }
      return;
    }
    widget.app?.privacySuspended = true;
    try {
      final available = await speech.initialize(
        onStatus: (status) {
          if (status == 'done' || status == 'notListening') {
            widget.app?.privacySuspended = false;
            if (mounted) {
              setState(() => listening = false);
            }
          }
        },
        onError: (_) {
          widget.app?.privacySuspended = false;
          if (mounted) {
            setState(() => listening = false);
          }
        },
      );
      if (!available) {
        if (mounted) {
          notice(context, 'الإملاء غير متاح. يمكنك الكتابة مباشرة.');
        }
        return;
      }
      final locales = await speech.locales();
      final arabic = locales
          .where((l) => l.localeId.startsWith('ar'))
          .firstOrNull;
      if (mounted) {
        setState(() => listening = true);
      }
      await speech.listen(
        onResult: (result) {
          widget.controller.text = western(result.recognizedWords);
          widget.controller.selection = TextSelection.collapsed(
            offset: widget.controller.text.length,
          );
        },
        listenOptions: SpeechListenOptions(
          localeId: arabic?.localeId,
          partialResults: true,
          cancelOnError: true,
        ),
      );
    } catch (_) {
      if (mounted) {
        notice(context, 'تعذّر تشغيل الإملاء.');
      }
    } finally {
      if (!listening) {
        widget.app?.privacySuspended = false;
      }
    }
  }

  @override
  void dispose() {
    speech.cancel();
    widget.app?.privacySuspended = false;
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 16),
    child: TextFormField(
      controller: widget.controller,
      obscureText: widget.secret && obscure,
      maxLines: widget.secret ? 1 : widget.maxLines,
      textDirection: widget.moneyInput || widget.secret
          ? TextDirection.ltr
          : null,
      keyboardType: widget.moneyInput
          ? const TextInputType.numberWithOptions(decimal: true)
          : widget.maxLines > 1
          ? TextInputType.multiline
          : TextInputType.text,
      textInputAction: widget.maxLines == 1
          ? TextInputAction.next
          : TextInputAction.newline,
      autocorrect: !widget.secret && !widget.moneyInput,
      enableSuggestions: !widget.secret,
      decoration: InputDecoration(
        labelText: widget.label,
        suffixIcon: widget.secret
            ? IconButton(
                onPressed: () => setState(() => obscure = !obscure),
                icon: Icon(
                  obscure
                      ? Icons.visibility_outlined
                      : Icons.visibility_off_outlined,
                ),
              )
            : widget.voice
            ? IconButton(
                onPressed: record,
                tooltip: 'إملاء صوتي',
                icon: Icon(listening ? Icons.stop_circle : Icons.mic_none),
              )
            : null,
      ),
      validator: (value) {
        if (widget.requiredField && (value?.trim().isEmpty ?? true)) {
          return 'هذا الحقل مطلوب';
        }
        if (widget.moneyInput && value?.trim().isNotEmpty == true) {
          try {
            parseMoney(value!, allowZero: !widget.requiredField);
          } on FormatException catch (e) {
            return e.message;
          }
        }
        return null;
      },
    ),
  );
}

class PendingBadge extends StatelessWidget {
  final String text;
  const PendingBadge({super.key, this.text = 'بانتظار الاعتماد'});
  @override
  Widget build(BuildContext context) => Chip(
    label: Text(text, style: const TextStyle(fontSize: 12)),
    avatar: const Icon(Icons.schedule, size: 16),
    padding: EdgeInsets.zero,
    visualDensity: VisualDensity.compact,
  );
}
