import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../core/config.dart';
import '../core/controller.dart';
import '../core/models.dart';
import '../services/reports.dart';
import 'auth.dart';
import 'shared.dart';
import 'reviewed_operations.dart';

class SettingsView extends StatelessWidget {
  final SadadController app;
  const SettingsView(this.app, {super.key});
  @override
  Widget build(BuildContext context) {
    final account = app.session!.account,
        remaining = integer(account['subscriptionRemainingMs']);
    return ListView(
      padding: const EdgeInsets.all(20),
      children: [
        const Center(child: Brand()),
        const SizedBox(height: 16),
        Text(
          '${account['name']}',
          textAlign: TextAlign.center,
          style: Theme.of(context).textTheme.headlineSmall,
        ),
        Text(
          'المستخدم: ${app.session!.staffName.isEmpty ? 'صاحب المتجر' : app.session!.staffName}',
          textAlign: TextAlign.center,
        ),
        const SectionTitle('الحساب'),
        Card(
          child: Column(
            children: [
              ListTile(
                leading: const Icon(Icons.verified_outlined),
                title: const Text('الاشتراك'),
                subtitle: Text(
                  account['subscriptionMode'] == 'permanent'
                      ? 'دائم'
                      : account['subscriptionMode'] == 'paused'
                      ? 'موقوف'
                      : 'المتبقي: ${(remaining / 86400000).ceil()} يوم',
                ),
              ),
              ListTile(
                leading: const Icon(Icons.devices_outlined),
                title: const Text('الأجهزة'),
                subtitle: Text(
                  '${account['boundDevices'] ?? 1} من ${account['maxDevices'] ?? 1} جهاز',
                ),
              ),
              ListTile(
                leading: const Icon(Icons.password),
                title: const Text('تغيير كلمة المرور'),
                onTap: app.session!.demo
                    ? null
                    : () => openPage(context, PasswordPage(app)),
              ),
              ListTile(
                leading: const Icon(Icons.sync),
                title: const Text('مركز المزامنة'),
                subtitle: Text('${app.pending.length} عملية محفوظة'),
                onTap: () => openPage(context, JournalPage(app)),
              ),
            ],
          ),
        ),
        const SectionTitle('التطبيق'),
        Card(
          child: Column(
            children: [
              ListTile(
                leading: const Icon(Icons.lock_outline),
                title: const Text('قفل التطبيق والبصمة'),
                onTap: () => openPage(context, LockSettingsPage(app)),
              ),
              ListTile(
                leading: const Icon(Icons.palette_outlined),
                title: const Text('المظهر'),
                trailing: DropdownButton<ThemeMode>(
                  value: app.themeMode,
                  underline: const SizedBox(),
                  items: const [
                    DropdownMenuItem(
                      value: ThemeMode.system,
                      child: Text('حسب الجهاز'),
                    ),
                    DropdownMenuItem(
                      value: ThemeMode.light,
                      child: Text('فاتح'),
                    ),
                    DropdownMenuItem(
                      value: ThemeMode.dark,
                      child: Text('داكن'),
                    ),
                  ],
                  onChanged: (mode) => app.setTheme(mode!),
                ),
              ),
              ListTile(
                leading: const Icon(Icons.chat_outlined),
                title: const Text('واتساب للأعمال'),
                onTap: () => openPage(context, WhatsAppPage(app)),
              ),
              ListTile(
                leading: const Icon(Icons.description_outlined),
                title: const Text('سند صرف'),
                onTap: () => openPage(context, VoucherPage(app)),
              ),
              ListTile(
                leading: const Icon(Icons.help_outline),
                title: const Text('تواصل معنا'),
                onTap: () async {
                  if (!await launchUrl(
                        Uri.parse(AppConfig.supportUrl),
                        mode: LaunchMode.externalApplication,
                      ) &&
                      context.mounted) {
                    notice(context, 'تعذّر فتح واتساب.');
                  }
                },
              ),
              ListTile(
                leading: const Icon(Icons.info_outline),
                title: const Text('عن سدد'),
                subtitle: const Text('Flutter · 4.0.0'),
                onTap: () => showAboutDialog(
                  context: context,
                  applicationName: 'سدد',
                  applicationVersion: '4.0.0 • Flutter',
                  applicationIcon: const Brand(size: 60),
                  children: [
                    Text(
                      AppConfig.demo
                          ? 'نسخة تجربة محلية.'
                          : 'متصل بخادم سدد على Supabase.',
                    ),
                    const Text(
                      'تُحفظ العمليات على الجهاز إلى أن يؤكدها الخادم.',
                    ),
                    SelectableText(
                      'الخادم: ${Uri.parse(AppConfig.apiUrl).host}',
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
        const SectionTitle('حسابات هذا الجهاز'),
        ...app.accounts.map(
          (s) => Card(
            child: ListTile(
              leading: Icon(
                s.scope == app.session!.scope
                    ? Icons.check_circle
                    : Icons.storefront,
              ),
              title: Text('${s.account['name']}'),
              onTap: s.scope == app.session!.scope
                  ? null
                  : () async {
                      try {
                        await app.switchAccount(s);
                        if (context.mounted) {
                          Navigator.popUntil(context, (route) => route.isFirst);
                        }
                      } catch (e) {
                        if (context.mounted) {
                          notice(context, e);
                        }
                      }
                    },
              trailing: IconButton(
                tooltip: 'إزالة الدخول المحفوظ',
                icon: const Icon(Icons.remove_circle_outline),
                onPressed: () async {
                  if (!await review(
                    context,
                    'إزالة الحساب المحفوظ',
                    'سيُزال الدخول المحفوظ لـ ${s.account['name']}. تبقى العمليات والبيانات المحلية لإعادة فتحها عند الدخول.',
                    action: 'إزالة',
                  )) {
                    return;
                  }
                  await app.forget(s);
                  if (context.mounted) {
                    Navigator.popUntil(context, (route) => route.isFirst);
                  }
                },
              ),
            ),
          ),
        ),
        if (app.accounts.length < 3)
          OutlinedButton.icon(
            onPressed: () => openPage(context, LoginPage(app, asRoute: true)),
            icon: const Icon(Icons.add),
            label: const Text('إضافة حساب'),
          ),
        const SizedBox(height: 16),
        TextButton.icon(
          onPressed: () async {
            try {
              await app.logout();
              if (context.mounted) {
                Navigator.popUntil(context, (route) => route.isFirst);
              }
            } catch (e) {
              if (context.mounted) {
                notice(context, e);
              }
            }
          },
          icon: const Icon(Icons.logout),
          label: const Text('تسجيل الخروج'),
        ),
        const SizedBox(height: 20),
      ],
    );
  }
}

class JournalPage extends StatelessWidget {
  final SadadController app;
  const JournalPage(this.app, {super.key});
  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: app,
    builder: (context, _) => Scaffold(
      appBar: AppBar(
        title: const Text('مركز المزامنة'),
        actions: [
          IconButton(
            tooltip: 'إدخالات المراجعة المحفوظة',
            onPressed: () => openPage(context, ReviewedOperationsPage(app)),
            icon: const Icon(Icons.inventory_2_outlined),
          ),
          IconButton(
            tooltip: 'مزامنة الآن',
            onPressed: app.syncing ? null : () => app.sync(retryNow: true),
            icon: const Icon(Icons.sync),
          ),
        ],
      ),
      body: constrained(
        ListView(
          padding: const EdgeInsets.all(20),
          children: [
            Text('آخر تحديث: ${dateLabel(app.cache.refreshedAt)}'),
            const SizedBox(height: 12),
            if (app.error.isNotEmpty)
              Text(
                app.error,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            if (app.needsLogin)
              FilledButton(
                onPressed: () =>
                    openPage(context, LoginPage(app, asRoute: true)),
                child: const Text('تسجيل الدخول مجددًا'),
              ),
            if (app.cache.recovery)
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    children: [
                      const Text(
                        'تغيرت نسخة الخادم. راجع كل عملية مقابل السجل الحالي قبل إعادة إدخالها.',
                      ),
                      FilledButton(
                        onPressed: () async {
                          try {
                            await app.store.finishRecovery(app.session!.scope);
                            await app.reload();
                            await app.sync();
                          } catch (e) {
                            if (context.mounted) {
                              notice(context, e);
                            }
                          }
                        },
                        child: const Text('إنهاء مراجعة الاستعادة'),
                      ),
                    ],
                  ),
                ),
              ),
            if (app.pending.isEmpty)
              const EmptyState(
                'لا توجد عمليات معلقة',
                'كل العمليات المحلية ظاهرة في آخر سجل تم اعتماده.',
                icon: Icons.cloud_done_outlined,
              ),
            ...app.pending.map(
              (c) => Card(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        operationLabel(c.type),
                        style: Theme.of(context).textTheme.titleMedium,
                      ),
                      Text(dateLabel(c.createdAt)),
                      Text(
                        c.state == 'attention'
                            ? 'تحتاج مراجعة'
                            : c.state == 'acknowledged'
                            ? 'اعتمدها الخادم؛ ننتظر تحديث السجل'
                            : 'بانتظار الإرسال',
                      ),
                      if (c.payload['amountCents'] != null)
                        Amount(integer(c.payload['amountCents'])),
                      if (c.payload['name'] != null)
                        Text('${c.payload['name']}'),
                      if ('${c.payload['note'] ?? c.payload['reason'] ?? ''}'
                          .isNotEmpty)
                        Text('${c.payload['note'] ?? c.payload['reason']}'),
                      if (c.error.isNotEmpty)
                        Text(
                          c.error,
                          style: TextStyle(
                            color: Theme.of(context).colorScheme.error,
                          ),
                        ),
                      if (c.state == 'attention')
                        TextButton(
                          onPressed: () async {
                            if (!await review(
                              context,
                              'تأكيد مراجعة العملية',
                              'قارن إدخالك بالسجل الحالي. ستبقى العملية محفوظة للمراجعة ولن تُرسل. يمكنك إدخال عملية جديدة بعد التأكد.',
                              action: 'راجعت العملية',
                            )) {
                              return;
                            }
                            await app.store.review(c);
                            await app.reload();
                          },
                          child: const Text('راجعت العملية وأحتفظ بإدخالها'),
                        ),
                    ],
                  ),
                ),
              ),
            ),
            const SizedBox(height: 20),
          ],
        ),
      ),
    ),
  );
}

class LockSettingsPage extends StatefulWidget {
  final SadadController app;
  const LockSettingsPage(this.app, {super.key});
  @override
  State<LockSettingsPage> createState() => _LockSettingsPageState();
}

class _LockSettingsPageState extends State<LockSettingsPage> {
  final current = TextEditingController(),
      next = TextEditingController(),
      confirmation = TextEditingController();
  bool biometric = false, busy = false;
  Future<void> save({bool disable = false}) async {
    if (busy) {
      return;
    }
    setState(() => busy = true);
    widget.app.privacySuspended = true;
    try {
      if (!disable && next.text != confirmation.text) {
        throw const FormatException('تأكيد رمز القفل غير مطابق.');
      }
      if (!disable && next.text.isEmpty) {
        throw const FormatException('أدخل رمز القفل الجديد.');
      }
      await widget.app.lock.configure(
        widget.app.session!.scope,
        disable ? '' : next.text,
        biometrics: biometric,
        currentPin: current.text,
      );
      if (mounted) {
        Navigator.pop(context);
        notice(context, disable ? 'أُلغي قفل التطبيق.' : 'حُفظ قفل التطبيق.');
      }
    } catch (e) {
      if (mounted) {
        notice(context, e);
      }
    } finally {
      widget.app.privacySuspended = false;
      if (mounted) {
        setState(() => busy = false);
      }
    }
  }

  @override
  void dispose() {
    current.dispose();
    next.dispose();
    confirmation.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('قفل التطبيق')),
    body: constrained(
      ListView(
        padding: const EdgeInsets.all(20),
        children: [
          const Text(
            'القفل خاص بهذا المتجر على هذا الجهاز. احتفظ بالرمز لتتمكن من فتح البيانات المحلية.',
          ),
          const SizedBox(height: 24),
          VoiceField(
            controller: current,
            label: 'الرمز الحالي (إذا كان القفل مفعّلًا)',
            secret: true,
          ),
          VoiceField(
            controller: next,
            label: 'الرمز الجديد من 4 إلى 8 أرقام',
            secret: true,
          ),
          VoiceField(
            controller: confirmation,
            label: 'تأكيد الرمز الجديد',
            secret: true,
          ),
          SwitchListTile.adaptive(
            title: const Text('فتح بالبصمة أو Face ID'),
            value: biometric,
            onChanged: (v) => setState(() => biometric = v),
          ),
          const SizedBox(height: 20),
          FilledButton(onPressed: busy ? null : save, child: const Text('حفظ')),
          TextButton(
            onPressed: busy ? null : () => save(disable: true),
            child: const Text('إلغاء القفل باستخدام الرمز الحالي'),
          ),
        ],
      ),
    ),
  );
}

class WhatsAppPage extends StatefulWidget {
  final SadadController app;
  const WhatsAppPage(this.app, {super.key});
  @override
  State<WhatsAppPage> createState() => _WhatsAppPageState();
}

class _WhatsAppPageState extends State<WhatsAppPage> {
  final phoneId = TextEditingController(),
      token = TextEditingController(),
      template = TextEditingController(),
      language = TextEditingController(text: 'ar');
  bool busy = true, enabled = false, connected = false;
  String error = '';
  @override
  void initState() {
    super.initState();
    load();
  }

  Future<void> load() async {
    try {
      if (widget.app.session!.demo) {
        throw const FormatException(
          'ربط واتساب متاح للمتجر الرسمي بعد تفعيله من الإدارة.',
        );
      }
      final r = await widget.app.api.request(
        'GET',
        'mobile/whatsapp',
        token: widget.app.session!.token,
      );
      enabled = r['enabled'] == true;
      connected = r['connected'] == true;
      phoneId.text = '${r['phoneNumberId'] ?? ''}';
      template.text = '${r['templateName'] ?? ''}';
      language.text = '${r['templateLanguage'] ?? 'ar'}';
    } catch (e) {
      error = e is FormatException ? e.message : '$e';
    } finally {
      if (mounted) {
        setState(() => busy = false);
      }
    }
  }

  Future<void> save() async {
    if (busy) {
      return;
    }
    setState(() => busy = true);
    try {
      await widget.app.api.request(
        'PUT',
        'mobile/whatsapp',
        token: widget.app.session!.token,
        body: {
          'phoneNumberId': phoneId.text.trim(),
          'accessToken': token.text.trim(),
          'templateName': template.text.trim(),
          'templateLanguage': language.text.trim(),
        },
      );
      token.clear();
      connected = true;
      if (mounted) {
        notice(context, 'حُفظ ربط واتساب على الخادم.');
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
    phoneId.dispose();
    token.dispose();
    template.dispose();
    language.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('واتساب للأعمال')),
    body: constrained(
      ListView(
        padding: const EdgeInsets.all(20),
        children: [
          Text(connected ? 'الربط محفوظ على الخادم' : 'إعداد الربط'),
          if (error.isNotEmpty) Text(error),
          const SizedBox(height: 16),
          const Text(
            'يمكن مشاركة كشف PDF من صفحة الشخص عبر قائمة المشاركة. بيانات Meta مطلوبة لربط حساب واتساب للأعمال بالخادم.',
          ),
          const SizedBox(height: 20),
          VoiceField(controller: phoneId, label: 'Phone Number ID'),
          VoiceField(
            controller: token,
            label: 'Meta access token',
            secret: true,
          ),
          VoiceField(
            controller: template,
            label: 'اسم القالب المعتمد (اختياري)',
          ),
          VoiceField(controller: language, label: 'لغة القالب'),
          FilledButton(
            onPressed: busy || !enabled ? null : save,
            child: Text(busy ? 'جارٍ التحميل…' : 'حفظ الربط'),
          ),
        ],
      ),
    ),
  );
}

class VoucherPage extends StatefulWidget {
  final SadadController app;
  const VoucherPage(this.app, {super.key});
  @override
  State<VoucherPage> createState() => _VoucherPageState();
}

class _VoucherPageState extends State<VoucherPage> {
  final form = GlobalKey<FormState>();
  final name = TextEditingController(),
      amount = TextEditingController(),
      note = TextEditingController();
  bool busy = false;
  Future<void> print() async {
    if (busy || !form.currentState!.validate()) {
      return;
    }
    setState(() => busy = true);
    try {
      await Reports.deliver(
        widget.app,
        voucher: {
          'name': name.text.trim(),
          'amountCents': parseMoney(amount.text),
          'note': note.text.trim(),
        },
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
  void dispose() {
    name.dispose();
    amount.dispose();
    note.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('سند صرف')),
    body: constrained(
      Form(
        key: form,
        child: ListView(
          padding: const EdgeInsets.all(20),
          children: [
            const Text('نموذج للطباعة؛ لا يضيف حركة إلى دفتر الديون.'),
            const SizedBox(height: 24),
            VoiceField(
              controller: name,
              label: 'المستفيد',
              requiredField: true,
              voice: true,
              app: widget.app,
            ),
            VoiceField(
              controller: amount,
              label: 'المبلغ',
              moneyInput: true,
              requiredField: true,
            ),
            VoiceField(
              controller: note,
              label: 'البيان',
              maxLines: 3,
              voice: true,
              app: widget.app,
            ),
            FilledButton.icon(
              onPressed: busy ? null : print,
              icon: const Icon(Icons.print),
              label: const Text('عرض وطباعة السند'),
            ),
          ],
        ),
      ),
    ),
  );
}
