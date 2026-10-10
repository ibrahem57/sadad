import 'package:flutter/material.dart';

import '../core/controller.dart';
import '../core/models.dart';
import '../services/reports.dart';
import 'forms.dart';
import 'shared.dart';

class ContactsView extends StatefulWidget {
  final SadadController app;
  const ContactsView(this.app, {super.key});
  @override
  State<ContactsView> createState() => _ContactsViewState();
}

class _ContactsViewState extends State<ContactsView> {
  final search = TextEditingController();
  bool archived = false;
  @override
  void dispose() {
    search.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final ledger = widget.app.visible,
        query = western(search.text.trim()).toLowerCase();
    final contacts =
        ledger.contacts
            .where(
              (c) =>
                  (archived || c['archivedAt'] == null) &&
                  ('${c['name']}'.toLowerCase().contains(query) ||
                      western('${c['phone'] ?? ''}').contains(query)),
            )
            .toList()
          ..sort((a, b) {
            final x = ledger.balance(idOf(a)), y = ledger.balance(idOf(b));
            if (x == 0 && y != 0) {
              return 1;
            }
            if (y == 0 && x != 0) {
              return -1;
            }
            return '${a['name']}'.compareTo('${b['name']}');
          });
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(20, 16, 20, 8),
          child: TextField(
            controller: search,
            onChanged: (_) => setState(() {}),
            decoration: InputDecoration(
              hintText: 'ابحث بالاسم أو رقم الهاتف',
              prefixIcon: const Icon(Icons.search),
              suffixIcon: search.text.isNotEmpty
                  ? IconButton(
                      onPressed: () => setState(search.clear),
                      icon: const Icon(Icons.clear),
                    )
                  : null,
            ),
          ),
        ),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 20),
          child: Wrap(
            spacing: 12,
            runSpacing: 8,
            alignment: WrapAlignment.spaceBetween,
            children: [
              FilterChip(
                label: const Text('إظهار المؤرشفين'),
                selected: archived,
                onSelected: (v) => setState(() => archived = v),
              ),
              FilledButton.icon(
                onPressed: widget.app.canWrite
                    ? () => addContact(context, widget.app)
                    : null,
                icon: const Icon(Icons.person_add_alt),
                label: const Text('شخص'),
              ),
            ],
          ),
        ),
        Expanded(
          child: contacts.isEmpty
              ? const SingleChildScrollView(
                  child: EmptyState(
                    'لا يوجد أشخاص',
                    'أضف شخصًا أو غيّر عبارة البحث. السجلات ذات الرصيد صفر تبقى محفوظة.',
                  ),
                )
              : ListView.builder(
                  padding: const EdgeInsets.all(16),
                  itemCount: contacts.length,
                  itemBuilder: (context, index) =>
                      ContactTile(widget.app, contacts[index]),
                ),
        ),
      ],
    );
  }
}

Future<void> addContact(BuildContext context, SadadController app) async {
  final id = await Navigator.push<String>(
    context,
    MaterialPageRoute(builder: (_) => ContactForm(app)),
  );
  if (id != null && context.mounted) {
    await openPage(context, ContactPage(app, id));
  }
}

class ContactTile extends StatelessWidget {
  final SadadController app;
  final Json contact;
  const ContactTile(this.app, this.contact, {super.key});
  @override
  Widget build(BuildContext context) {
    final hasPending = app.pending.any(
      (c) =>
          c.entityId == idOf(contact) ||
          '${c.payload['contactId']}' == idOf(contact) ||
          '${app.visible.debt('${c.payload['debtId']}')?['contactId']}' ==
              idOf(contact),
    );
    return Card(
      child: ListTile(
        contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
        leading: CircleAvatar(
          child: Icon(
            contact['archivedAt'] == null
                ? Icons.person_outline
                : Icons.archive_outlined,
          ),
        ),
        title: Text(
          '${contact['name']}',
          style: const TextStyle(fontWeight: FontWeight.bold),
        ),
        subtitle: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('${contact['phone'] ?? ''}', textDirection: TextDirection.ltr),
            if (hasPending) const PendingBadge(),
            if (contact['archivedAt'] != null) const Text('مؤرشف'),
          ],
        ),
        trailing: Amount(app.visible.balance(idOf(contact))),
        onTap: () => openPage(context, ContactPage(app, idOf(contact))),
      ),
    );
  }
}

class ContactPage extends StatefulWidget {
  final SadadController app;
  final String id;
  const ContactPage(this.app, this.id, {super.key});
  @override
  State<ContactPage> createState() => _ContactPageState();
}

class _ContactPageState extends State<ContactPage> {
  bool busy = false;
  Future<void> archive(Json contact) async {
    if (busy) {
      return;
    }
    setState(() => busy = true);
    try {
      final archived = contact['archivedAt'] != null;
      final reason = await reasonDialog(
        context,
        archived ? 'استعادة الشخص' : 'أرشفة الشخص',
      );
      if (reason == null ||
          !mounted ||
          !await review(
            context,
            archived ? 'تأكيد الاستعادة' : 'تأكيد الأرشفة',
            '${contact['name']}\n$reason\nتبقى الحركات المالية في السجل.',
            action: 'تأكيد',
          )) {
        return;
      }
      await widget.app.submit(
        archived ? 'contact.restore' : 'contact.archive',
        widget.id,
        {'reason': reason},
        version: '${contact['version']}',
      );
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
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: widget.app,
    builder: (context, _) {
      final app = widget.app,
          ledger = app.visible,
          contact = ledger.contact(widget.id);
      if (contact == null) {
        return const Scaffold(
          body: EmptyState('السجل غير موجود', 'حدّث بيانات الحساب.'),
        );
      }
      final debts =
          ledger.debts.where((d) => '${d['contactId']}' == widget.id).toList()
            ..sort(
              (a, b) =>
                  integer(b['createdAt']).compareTo(integer(a['createdAt'])),
            );
      return Scaffold(
        appBar: AppBar(
          title: Text('${contact['name']}'),
          actions: [
            IconButton(
              tooltip: 'كشف حساب',
              onPressed: () =>
                  openPage(context, ReportPage(app, contactId: widget.id)),
              icon: const Icon(Icons.picture_as_pdf_outlined),
            ),
            PopupMenuButton<String>(
              onSelected: (value) async {
                if (value == 'edit') {
                  await openPage(context, ContactForm(app, contact: contact));
                } else {
                  await archive(contact);
                }
              },
              itemBuilder: (_) => [
                PopupMenuItem(
                  value: 'edit',
                  enabled: app.canWrite,
                  child: const Text('تعديل البيانات'),
                ),
                PopupMenuItem(
                  value: 'archive',
                  enabled: app.canWrite && !busy,
                  child: Text(
                    contact['archivedAt'] == null
                        ? 'أرشفة الشخص'
                        : 'استعادة الشخص',
                  ),
                ),
              ],
            ),
          ],
        ),
        body: constrained(
          ListView(
            padding: const EdgeInsets.all(20),
            children: [
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(20),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        '${contact['phone'] ?? ''}',
                        textDirection: TextDirection.ltr,
                      ),
                      if ('${contact['note'] ?? ''}'.isNotEmpty)
                        Text('${contact['note']}'),
                      const SizedBox(height: 12),
                      const Text('الدين الحالي'),
                      Amount(
                        ledger.balance(widget.id),
                        style: Theme.of(context).textTheme.headlineMedium,
                      ),
                      if (ledger.balance(widget.id) !=
                          app.confirmed.balance(widget.id)) ...[
                        const PendingBadge(),
                        Text(
                          'الرصيد المعتمد: ${money(app.confirmed.balance(widget.id))}',
                        ),
                      ],
                      if (cents(contact, 'creditLimit') > 0)
                        Text(
                          'حد الدين: ${money(cents(contact, 'creditLimit'))}',
                        ),
                      if (contact['archivedAt'] != null)
                        const Text('هذا الشخص مؤرشف. استعده لإضافة حركات.'),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 16),
              Wrap(
                spacing: 12,
                runSpacing: 12,
                children: [
                  FilledButton.icon(
                    onPressed: app.canWrite && contact['archivedAt'] == null
                        ? () => openPage(context, EntryForm(app, widget.id))
                        : null,
                    icon: const Icon(Icons.add),
                    label: const Text('دين جديد'),
                  ),
                  OutlinedButton.icon(
                    onPressed:
                        app.canWrite &&
                            contact['archivedAt'] == null &&
                            ledger.balance(widget.id) > 0
                        ? () => openPage(
                            context,
                            EntryForm(app, widget.id, payment: true),
                          )
                        : null,
                    icon: const Icon(Icons.payments_outlined),
                    label: const Text('تسجيل دفعة'),
                  ),
                  TextButton.icon(
                    onPressed: () => openPage(
                      context,
                      HistoryPage(app, contactId: widget.id),
                    ),
                    icon: const Icon(Icons.history),
                    label: const Text('كامل السجل'),
                  ),
                ],
              ),
              const SectionTitle('الديون والدفعات'),
              if (debts.isEmpty)
                const EmptyState('لا توجد ديون', 'سيظهر الدين هنا بعد تسجيله.'),
              ...debts.map(
                (d) => Card(
                  child: ListTile(
                    title: Text(
                      '${d['note'] ?? 'دين'}'.isEmpty ? 'دين' : '${d['note']}',
                    ),
                    subtitle: Text(
                      '${dateLabel(d['createdAt'])}\nأصل الدين: ${money(cents(d, 'amount'))} · المدفوع: ${money(cents(d, 'paid'))}',
                    ),
                    isThreeLine: true,
                    trailing: Amount(cents(d, 'remaining')),
                    onTap: () => openPage(context, DebtPage(app, idOf(d))),
                  ),
                ),
              ),
            ],
          ),
        ),
      );
    },
  );
}

class DebtPage extends StatefulWidget {
  final SadadController app;
  final String id;
  const DebtPage(this.app, this.id, {super.key});
  @override
  State<DebtPage> createState() => _DebtPageState();
}

class _DebtPageState extends State<DebtPage> {
  bool busy = false;
  Future<void> reverse(Json payment) async {
    if (busy) {
      return;
    }
    setState(() => busy = true);
    try {
      final reason = await reasonDialog(context, 'سبب عكس الدفعة');
      if (reason == null ||
          !mounted ||
          !await review(
            context,
            'عكس دفعة',
            '${money(cents(payment, 'amount'))}\n$reason\nسيُسجل العكس مع الاحتفاظ بالدفعة الأصلية.',
            action: 'تأكيد العكس',
          )) {
        return;
      }
      await widget.app.submit('payment.reverse', ids.v4(), {
        'paymentId': idOf(payment),
        'reason': reason,
      });
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
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: widget.app,
    builder: (context, _) {
      final app = widget.app, debt = app.visible.debt(widget.id);
      if (debt == null) {
        return const Scaffold(
          body: EmptyState('الدين غير موجود', 'حدّث السجل.'),
        );
      }
      final payments =
          app.visible.payments
              .where((p) => '${p['debtId']}' == widget.id)
              .toList()
            ..sort(
              (a, b) =>
                  integer(b['createdAt']).compareTo(integer(a['createdAt'])),
            );
      return Scaffold(
        appBar: AppBar(
          title: const Text('تفاصيل الدين'),
          actions: [
            IconButton(
              tooltip: 'تصحيح الدين',
              onPressed: app.canWrite
                  ? () => openPage(
                      context,
                      EntryForm(app, '${debt['contactId']}', correction: debt),
                    )
                  : null,
              icon: const Icon(Icons.edit_outlined),
            ),
          ],
        ),
        body: constrained(
          ListView(
            padding: const EdgeInsets.all(20),
            children: [
              Text(
                '${app.visible.contact('${debt['contactId']}')?['name']}',
                style: Theme.of(context).textTheme.headlineSmall,
              ),
              const SizedBox(height: 16),
              Text('${debt['note'] ?? ''}'),
              Text('سُجل: ${dateLabel(debt['createdAt'])}'),
              if (integer(debt['dueDate']) > 0)
                Text('الاستحقاق: ${dateLabel(debt['dueDate'])}'),
              const SizedBox(height: 16),
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(20),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text('أصل الدين: ${money(cents(debt, 'amount'))}'),
                      Text('المدفوع: ${money(cents(debt, 'paid'))}'),
                      const Text('المتبقي'),
                      Amount(cents(debt, 'remaining')),
                      if (cents(debt, 'credit') > 0)
                        Text('رصيد زائد: ${money(cents(debt, 'credit'))}'),
                    ],
                  ),
                ),
              ),
              const SectionTitle('الدفعات'),
              if (payments.isEmpty)
                const EmptyState('لا توجد دفعات', 'سجّل دفعة من صفحة الشخص.'),
              ...payments.map(
                (p) => Card(
                  child: Padding(
                    padding: const EdgeInsets.all(12),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            Expanded(child: Text(dateLabel(p['createdAt']))),
                            Amount(cents(p, 'amount')),
                          ],
                        ),
                        Text(
                          '${p['method'] == 'cash'
                              ? 'نقدي'
                              : p['method'] == 'bank'
                              ? 'بنك'
                              : 'محفظة'} · ${p['note'] ?? ''}',
                        ),
                        if (p['reversedAt'] != null)
                          Text('عُكست: ${dateLabel(p['reversedAt'])}'),
                        Wrap(
                          children: [
                            TextButton.icon(
                              onPressed: () async {
                                final canonical = app.confirmed.payments
                                    .where((r) => idOf(r) == idOf(p))
                                    .firstOrNull;
                                if (canonical == null) {
                                  notice(
                                    context,
                                    'انتظر اعتماد الدفعة لإصدار إيصال.',
                                  );
                                  return;
                                }
                                try {
                                  await Reports.deliver(
                                    app,
                                    contactId: '${debt['contactId']}',
                                    receipt: canonical,
                                  );
                                } catch (e) {
                                  if (context.mounted) {
                                    notice(context, e);
                                  }
                                }
                              },
                              icon: const Icon(Icons.receipt_long_outlined),
                              label: const Text('إيصال'),
                            ),
                            if (p['reversedAt'] == null)
                              TextButton(
                                onPressed: app.canWrite && !busy
                                    ? () => reverse(p)
                                    : null,
                                child: const Text('عكس الدفعة'),
                              ),
                          ],
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      );
    },
  );
}

class HistoryPage extends StatelessWidget {
  final SadadController app;
  final String? contactId;
  const HistoryPage(this.app, {super.key, this.contactId});
  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('سجل الحركات')),
    body: AnimatedBuilder(
      animation: app,
      builder: (context, _) =>
          constrained(HistoryView(app, contactId: contactId)),
    ),
  );
}

class HistoryView extends StatefulWidget {
  final SadadController app;
  final String? contactId;
  const HistoryView(this.app, {super.key, this.contactId});
  @override
  State<HistoryView> createState() => _HistoryViewState();
}

class _HistoryViewState extends State<HistoryView> {
  String period = 'all';
  DateTimeRange? custom;
  bool grouped = true;
  @override
  Widget build(BuildContext context) {
    final now = DateTime.now();
    final from = switch (period) {
      'day' => DateTime(now.year, now.month, now.day),
      'month' => DateTime(now.year, now.month),
      'year' => DateTime(now.year),
      'custom' => custom?.start,
      _ => null,
    };
    final until = period == 'custom'
        ? custom?.end.add(const Duration(days: 1))
        : null;
    final events =
        widget.app.visible.transactions
            .where(
              (t) =>
                  (widget.contactId == null ||
                      '${t['contactId']}' == widget.contactId) &&
                  (from == null ||
                      integer(t['createdAt']) >= from.millisecondsSinceEpoch) &&
                  (until == null ||
                      integer(t['createdAt']) < until.millisecondsSinceEpoch),
            )
            .toList()
          ..sort(
            (a, b) =>
                integer(b['createdAt']).compareTo(integer(a['createdAt'])),
          );
    final shown = <Json>[];
    final seen = <String>{};
    for (final event in events) {
      if (widget.contactId != null ||
          !grouped ||
          seen.add('${event['contactId']}')) {
        shown.add(event);
      }
    }
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            children: [
              DropdownButtonFormField<String>(
                initialValue: period,
                decoration: const InputDecoration(labelText: 'الفترة'),
                items: const [
                  DropdownMenuItem(value: 'all', child: Text('كل السجل')),
                  DropdownMenuItem(value: 'day', child: Text('اليوم')),
                  DropdownMenuItem(value: 'month', child: Text('هذا الشهر')),
                  DropdownMenuItem(value: 'year', child: Text('هذه السنة')),
                  DropdownMenuItem(value: 'custom', child: Text('فترة مخصصة')),
                ],
                onChanged: (value) async {
                  if (value == 'custom') {
                    final result = await showDateRangePicker(
                      context: context,
                      firstDate: DateTime(2000),
                      lastDate: DateTime(now.year + 1),
                      initialDateRange: custom,
                    );
                    if (result == null || !mounted) {
                      return;
                    }
                    custom = result;
                  }
                  setState(() => period = value!);
                },
              ),
              if (widget.contactId == null)
                SwitchListTile.adaptive(
                  contentPadding: EdgeInsets.zero,
                  title: const Text('آخر حركة لكل شخص'),
                  value: grouped,
                  onChanged: (v) => setState(() => grouped = v),
                ),
            ],
          ),
        ),
        Expanded(
          child: shown.isEmpty
              ? const SingleChildScrollView(
                  child: EmptyState(
                    'لا توجد حركات',
                    'ستظهر العمليات المسجلة في هذه الفترة.',
                  ),
                )
              : ListView.builder(
                  padding: const EdgeInsets.symmetric(horizontal: 16),
                  itemCount: shown.length,
                  itemBuilder: (context, index) {
                    final t = shown[index], cid = '${t['contactId']}';
                    return Card(
                      child: ListTile(
                        title: Text(
                          '${t['contactName'] ?? widget.app.visible.contact(cid)?['name'] ?? ''} · ${operationLabel('${t['kind']}')}',
                        ),
                        subtitle: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(dateLabel(t['createdAt'])),
                            if ('${t['note'] ?? t['reason'] ?? ''}'.isNotEmpty)
                              Text('${t['note'] ?? t['reason']}'),
                            if ('${t['createdBy'] ?? t['actor'] ?? ''}'
                                .isNotEmpty)
                              Text('بواسطة ${t['createdBy'] ?? t['actor']}'),
                            if (t['pending'] == true) const PendingBadge(),
                          ],
                        ),
                        trailing: Amount(cents(t, 'amount')),
                        onTap:
                            widget.contactId == null &&
                                widget.app.visible.contact(cid) != null
                            ? () => openPage(
                                context,
                                HistoryPage(widget.app, contactId: cid),
                              )
                            : null,
                      ),
                    );
                  },
                ),
        ),
      ],
    );
  }
}

class ReportPage extends StatefulWidget {
  final SadadController app;
  final String? contactId;
  const ReportPage(this.app, {super.key, this.contactId});
  @override
  State<ReportPage> createState() => _ReportPageState();
}

class _ReportPageState extends State<ReportPage> {
  DateTimeRange? range;
  String period = 'all';
  bool busy = false;
  Future<void> report(bool share) async {
    if (busy) {
      return;
    }
    setState(() => busy = true);
    final now = DateTime.now();
    final from = switch (period) {
      'day' => DateTime(now.year, now.month, now.day),
      'month' => DateTime(now.year, now.month),
      'year' => DateTime(now.year),
      'custom' => range?.start,
      _ => null,
    };
    try {
      await Reports.deliver(
        widget.app,
        contactId: widget.contactId,
        from: from,
        until: period == 'custom' ? range?.end : null,
        share: share,
      );
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
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('التقارير')),
    body: constrained(
      ListView(
        padding: const EdgeInsets.all(20),
        children: [
          const Icon(Icons.picture_as_pdf_outlined, size: 64),
          const SizedBox(height: 20),
          Text(
            widget.contactId == null
                ? 'كشف المتجر'
                : 'كشف ${widget.app.visible.contact(widget.contactId!)?['name']}',
            style: Theme.of(context).textTheme.headlineSmall,
          ),
          const SizedBox(height: 8),
          const Text(
            'الكشف يعرض الحركات المعتمدة ورصيد الشخص الحالي. اختر فترة لتصفية الحركات.',
          ),
          const SizedBox(height: 24),
          DropdownButtonFormField<String>(
            initialValue: period,
            decoration: const InputDecoration(labelText: 'الفترة'),
            items: const [
              DropdownMenuItem(value: 'all', child: Text('كل السجل')),
              DropdownMenuItem(value: 'day', child: Text('اليوم')),
              DropdownMenuItem(value: 'month', child: Text('هذا الشهر')),
              DropdownMenuItem(value: 'year', child: Text('هذه السنة')),
              DropdownMenuItem(value: 'custom', child: Text('فترة مخصصة')),
            ],
            onChanged: (v) async {
              if (v == 'custom') {
                final now = DateTime.now();
                final result = await showDateRangePicker(
                  context: context,
                  firstDate: DateTime(2000),
                  lastDate: DateTime(now.year + 1),
                );
                if (result == null || !mounted) {
                  return;
                }
                range = result;
              }
              setState(() => period = v!);
            },
          ),
          const SizedBox(height: 24),
          FilledButton.icon(
            onPressed: busy ? null : () => report(false),
            icon: const Icon(Icons.print_outlined),
            label: const Text('عرض وطباعة PDF'),
          ),
          const SizedBox(height: 12),
          OutlinedButton.icon(
            onPressed: busy ? null : () => report(true),
            icon: const Icon(Icons.share_outlined),
            label: const Text('مشاركة PDF'),
          ),
          if (widget.app.pending.isNotEmpty)
            const Padding(
              padding: EdgeInsets.only(top: 20),
              child: Text(
                'توجد عمليات بانتظار الاعتماد؛ ستظهر في الكشف بعد المزامنة.',
              ),
            ),
        ],
      ),
    ),
  );
}
