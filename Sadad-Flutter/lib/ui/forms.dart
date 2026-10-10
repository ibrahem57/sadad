import 'package:flutter/material.dart';

import '../core/controller.dart';
import '../core/models.dart';
import 'shared.dart';

class ContactForm extends StatefulWidget {
  final SadadController app;
  final Json? contact;
  const ContactForm(this.app, {super.key, this.contact});
  @override
  State<ContactForm> createState() => _ContactFormState();
}

class _ContactFormState extends State<ContactForm> {
  final form = GlobalKey<FormState>();
  final name = TextEditingController(),
      phone = TextEditingController(),
      note = TextEditingController(),
      limit = TextEditingController();
  String category = 'عميل';
  bool whatsapp = false, busy = false;
  @override
  void initState() {
    super.initState();
    final c = widget.contact;
    if (c != null) {
      name.text = '${c['name']}';
      phone.text = '${c['phone'] ?? ''}';
      note.text = '${c['note'] ?? ''}';
      limit.text = cents(c, 'creditLimit') == 0
          ? ''
          : (cents(c, 'creditLimit') / 100).toStringAsFixed(2);
      category = '${c['category'] ?? 'عميل'}';
      whatsapp = c['whatsappOptIn'] == true;
    }
  }

  Future<void> save() async {
    if (busy || !form.currentState!.validate()) {
      return;
    }
    setState(() => busy = true);
    try {
      final phoneValue = normalizedPhone(phone.text);
      final duplicate = widget.app.visible.contacts
          .where(
            (c) =>
                idOf(c) !=
                    (widget.contact == null ? '' : idOf(widget.contact!)) &&
                (phoneValue.isNotEmpty &&
                        normalizedPhone('${c['phone'] ?? ''}') == phoneValue ||
                    '${c['name']}'.trim() == name.text.trim() &&
                        normalizedPhone('${c['phone'] ?? ''}') == phoneValue),
          )
          .firstOrNull;
      if (duplicate != null) {
        if (!mounted) {
          return;
        }
        await showDialog<void>(
          context: context,
          builder: (context) => AlertDialog(
            title: const Text('هذا الشخص مسجل'),
            content: Text(
              '${duplicate['name']}\n${duplicate['phone'] ?? ''}\nافتح سجله الحالي من قائمة الأشخاص.',
            ),
            actions: [
              FilledButton(
                onPressed: () => Navigator.pop(context),
                child: const Text('حسنًا'),
              ),
            ],
          ),
        );
        if (mounted) {
          Navigator.pop(context, idOf(duplicate));
        }
        return;
      }
      final payload = <String, dynamic>{
        'name': name.text.trim(),
        'phone': phoneValue,
        'category': category,
        'note': note.text.trim(),
        'whatsappOptIn': whatsapp,
        'creditLimitCents': limit.text.trim().isEmpty
            ? 0
            : parseMoney(limit.text, allowZero: true),
      };
      if (!mounted ||
          !await review(
            context,
            widget.contact == null ? 'إضافة شخص' : 'تعديل بيانات الشخص',
            '${payload['name']}\n${payload['phone']}\nحد الدين: ${money(payload['creditLimitCents'])}\n${payload['note']}',
          )) {
        return;
      }
      final id = widget.contact == null ? ids.v4() : idOf(widget.contact!);
      await widget.app.submit(
        widget.contact == null ? 'contact.create' : 'contact.update',
        id,
        payload,
        version: widget.contact == null
            ? null
            : '${widget.app.visible.contact(id)?['version']}',
      );
      if (mounted) {
        Navigator.pop(context, id);
      }
    } catch (e) {
      if (mounted) {
        notice(context, e);
      }
    } finally {
      if (mounted) {
        setState(() => busy = false);
      }
    }
  }

  @override
  void dispose() {
    for (final c in [name, phone, note, limit]) {
      c.dispose();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: Text(widget.contact == null ? 'إضافة شخص' : 'تعديل الشخص'),
    ),
    body: constrained(
      Form(
        key: form,
        child: ListView(
          padding: const EdgeInsets.all(20),
          children: [
            VoiceField(
              controller: name,
              label: 'الاسم',
              requiredField: true,
              voice: true,
              app: widget.app,
            ),
            TextFormField(
              controller: phone,
              keyboardType: TextInputType.phone,
              textDirection: TextDirection.ltr,
              decoration: const InputDecoration(
                labelText: 'رقم الهاتف',
                hintText: '97059xxxxxxx',
              ),
              validator: (s) =>
                  s!.isNotEmpty &&
                      (normalizedPhone(s).length < 8 ||
                          normalizedPhone(s).length > 15)
                  ? 'أدخل رقمًا من 8 إلى 15 رقمًا'
                  : null,
            ),
            const SizedBox(height: 16),
            DropdownButtonFormField<String>(
              initialValue: category,
              decoration: const InputDecoration(labelText: 'الفئة'),
              items: {
                category,
                'عميل',
                'مورد',
                'أخرى',
              }.map((v) => DropdownMenuItem(value: v, child: Text(v))).toList(),
              onChanged: (v) => setState(() => category = v!),
            ),
            const SizedBox(height: 16),
            VoiceField(
              controller: limit,
              label: 'حد الدين (اختياري)',
              moneyInput: true,
            ),
            VoiceField(
              controller: note,
              label: 'ملاحظة',
              maxLines: 3,
              voice: true,
              app: widget.app,
            ),
            SwitchListTile.adaptive(
              title: const Text('وافق الشخص على رسائل واتساب'),
              value: whatsapp,
              onChanged: (v) => setState(() => whatsapp = v),
            ),
            const SizedBox(height: 20),
            FilledButton.icon(
              onPressed: busy ? null : save,
              icon: const Icon(Icons.check),
              label: Text(busy ? 'جارٍ الحفظ…' : 'مراجعة وحفظ'),
            ),
          ],
        ),
      ),
    ),
  );
}

class EntryForm extends StatefulWidget {
  final SadadController app;
  final String contactId;
  final bool payment;
  final Json? correction;
  const EntryForm(
    this.app,
    this.contactId, {
    super.key,
    this.payment = false,
    this.correction,
  });
  @override
  State<EntryForm> createState() => _EntryFormState();
}

class _EntryFormState extends State<EntryForm> {
  final form = GlobalKey<FormState>();
  final amount = TextEditingController(),
      note = TextEditingController(),
      reason = TextEditingController();
  String method = 'cash';
  String? debtId;
  DateTime? due;
  bool busy = false;
  List<Json> get debts => widget.app.visible.debts
      .where(
        (d) =>
            '${d['contactId']}' == widget.contactId &&
            d['direction'] != 'payable' &&
            cents(d, 'remaining') > 0,
      )
      .toList();
  @override
  void initState() {
    super.initState();
    if (widget.payment) {
      debtId = debts.firstOrNull?['id']?.toString();
    }
    if (widget.correction != null) {
      final d = widget.correction!;
      amount.text = (cents(d, 'amount') / 100).toStringAsFixed(2);
      note.text = '${d['note'] ?? ''}';
      final timestamp = integer(d['dueDate']);
      if (timestamp > 0) {
        due = DateTime.fromMillisecondsSinceEpoch(timestamp);
      }
    }
  }

  String get title => widget.correction != null
      ? 'تصحيح دين'
      : widget.payment
      ? 'تسجيل دفعة'
      : 'تسجيل دين';
  Future<void> save() async {
    if (busy || !form.currentState!.validate()) {
      return;
    }
    setState(() => busy = true);
    try {
      final value = parseMoney(amount.text);
      final contact = widget.app.visible.contact(widget.contactId)!;
      Json payload;
      String type, entity;
      String? version;
      if (widget.payment) {
        if (debtId == null) {
          throw const FormatException('اختر دينًا لتسجيل الدفعة.');
        }
        final debt = widget.app.visible.debt(debtId!);
        if (debt == null || value > cents(debt, 'remaining')) {
          throw const FormatException('الدفعة أكبر من المتبقي على الدين.');
        }
        payload = {
          'debtId': debtId,
          'amountCents': value,
          'method': method,
          'note': note.text.trim(),
        };
        type = 'payment.create';
        entity = ids.v4();
      } else if (widget.correction != null) {
        if (reason.text.trim().isEmpty) {
          throw const FormatException('اكتب سبب تصحيح الدين.');
        }
        entity = idOf(widget.correction!);
        version = '${widget.app.visible.debt(entity)?['version']}';
        type = 'debt.correct';
        payload = {
          'amountCents': value,
          'note': note.text.trim(),
          'dueDate': due?.millisecondsSinceEpoch,
          'reason': reason.text.trim(),
        };
      } else {
        final limit = cents(contact, 'creditLimit');
        if (limit > 0 &&
            widget.app.visible.balance(widget.contactId) + value > limit) {
          throw const FormatException(
            'سيتم تجاوز حد الدين لهذا الشخص. عدّل الحد بعد مراجعة السجل.',
          );
        }
        payload = {
          'contactId': widget.contactId,
          'direction': 'receivable',
          'amountCents': value,
          'note': note.text.trim(),
          'dueDate': due?.millisecondsSinceEpoch,
        };
        type = 'debt.create';
        entity = ids.v4();
      }
      if (!mounted ||
          !await review(
            context,
            title,
            '${contact['name']}\n${money(value)}\n${note.text}\n${reason.text}',
          )) {
        return;
      }
      await widget.app.submit(type, entity, payload, version: version);
      if (mounted) {
        Navigator.pop(context);
        notice(
          context,
          widget.app.session!.demo
              ? 'حُفظت العملية محليًا في نسخة التجربة.'
              : 'حُفظت العملية. سيعتمدها الخادم عند المزامنة.',
        );
      }
    } catch (e) {
      if (mounted) {
        notice(context, e);
      }
    } finally {
      if (mounted) {
        setState(() => busy = false);
      }
    }
  }

  @override
  void dispose() {
    amount.dispose();
    note.dispose();
    reason.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: Text(title)),
    body: constrained(
      Form(
        key: form,
        child: ListView(
          padding: const EdgeInsets.all(20),
          children: [
            Text(
              '${widget.app.visible.contact(widget.contactId)?['name'] ?? ''}',
              style: Theme.of(context).textTheme.headlineSmall,
            ),
            const SizedBox(height: 24),
            if (widget.payment) ...[
              DropdownButtonFormField<String>(
                initialValue: debtId,
                isExpanded: true,
                decoration: const InputDecoration(labelText: 'الدين'),
                items: debts
                    .map(
                      (d) => DropdownMenuItem(
                        value: idOf(d),
                        child: Text(
                          '${money(cents(d, 'remaining'))} · ${d['note'] ?? dateLabel(d['createdAt'])}',
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                    )
                    .toList(),
                onChanged: (v) => setState(() => debtId = v),
                validator: (v) => v == null ? 'لا يوجد دين قابل للدفع' : null,
              ),
              const SizedBox(height: 16),
            ],
            VoiceField(
              controller: amount,
              label: 'المبلغ بالشيكل',
              requiredField: true,
              moneyInput: true,
              voice: true,
              app: widget.app,
            ),
            if (widget.payment) ...[
              DropdownButtonFormField<String>(
                initialValue: method,
                decoration: const InputDecoration(labelText: 'طريقة الدفع'),
                items: const [
                  DropdownMenuItem(value: 'cash', child: Text('نقدي')),
                  DropdownMenuItem(value: 'bank', child: Text('بنك')),
                  DropdownMenuItem(value: 'wallet', child: Text('محفظة')),
                ],
                onChanged: (v) => setState(() => method = v!),
              ),
              const SizedBox(height: 16),
            ] else ...[
              ListTile(
                contentPadding: EdgeInsets.zero,
                title: const Text('تاريخ الاستحقاق (اختياري)'),
                subtitle: Text(
                  due == null
                      ? 'بدون تاريخ'
                      : dateLabel(due!.millisecondsSinceEpoch)
                            .split(' · ')
                            .first,
                ),
                trailing: Wrap(
                  children: [
                    if (due != null)
                      IconButton(
                        onPressed: () => setState(() => due = null),
                        icon: const Icon(Icons.clear),
                      ),
                    IconButton(
                      onPressed: () async {
                        final now = DateTime.now();
                        final value = await showDatePicker(
                          context: context,
                          initialDate: due ?? now,
                          firstDate: DateTime(2000),
                          lastDate: DateTime(now.year + 20),
                        );
                        if (value != null && mounted) {
                          setState(() => due = value);
                        }
                      },
                      icon: const Icon(Icons.calendar_today_outlined),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 16),
            ],
            VoiceField(
              controller: note,
              label: 'ملاحظة العملية',
              maxLines: 3,
              voice: true,
              app: widget.app,
            ),
            if (widget.correction != null)
              VoiceField(
                controller: reason,
                label: 'سبب التصحيح',
                requiredField: true,
                maxLines: 2,
              ),
            const SizedBox(height: 20),
            FilledButton.icon(
              onPressed: busy ? null : save,
              icon: const Icon(Icons.check),
              label: Text(busy ? 'جارٍ الحفظ…' : 'مراجعة وحفظ'),
            ),
          ],
        ),
      ),
    ),
  );
}

Future<String?> reasonDialog(BuildContext context, String title) async {
  final input = TextEditingController();
  final result = await showDialog<String>(
    context: context,
    builder: (context) => AlertDialog(
      title: Text(title),
      content: TextField(
        controller: input,
        maxLines: 3,
        decoration: const InputDecoration(labelText: 'السبب'),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('رجوع'),
        ),
        FilledButton(
          onPressed: () {
            if (input.text.trim().isNotEmpty) {
              Navigator.pop(context, input.text.trim());
            }
          },
          child: const Text('مراجعة'),
        ),
      ],
    ),
  );
  input.dispose();
  return result;
}
