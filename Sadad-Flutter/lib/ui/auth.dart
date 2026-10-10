import 'package:flutter/material.dart';

import '../core/config.dart';
import '../core/controller.dart';
import '../core/models.dart';
import 'shared.dart';

class LoginPage extends StatefulWidget {
  final SadadController app;
  final bool asRoute;
  const LoginPage(this.app, {super.key, this.asRoute = false});
  @override
  State<LoginPage> createState() => _LoginPageState();
}

class _LoginPageState extends State<LoginPage> {
  final username = TextEditingController(),
      password = TextEditingController(),
      staff = TextEditingController();
  final form = GlobalKey<FormState>();
  bool busy = false, customer = false;
  String error = '';
  Future<void> login() async {
    if (busy || !form.currentState!.validate()) {
      return;
    }
    setState(() {
      busy = true;
      error = '';
    });
    try {
      if (customer) {
        if (!AppConfig.demo) {
          throw const FormatException(
            'بوابة العملاء تحتاج تفعيل التحقق برسالة SMS من الخادم.',
          );
        }
        if (username.text != '0599999999' || password.text != '123123') {
          throw const FormatException('بيانات حساب العميل التجريبي غير صحيحة.');
        }
        final cache = await widget.app.store.cache('demo:merchant');
        if (mounted) {
          await openPage(context, CustomerDemo(cache.ledger, username.text));
        }
        return;
      }
      await widget.app.login(username.text, password.text, staff.text);
      if (widget.asRoute && mounted) {
        Navigator.pop(context);
      }
    } catch (e) {
      if (mounted) {
        setState(() => error = e is FormatException ? e.message : '$e');
      }
    } finally {
      if (mounted) {
        setState(() => busy = false);
      }
    }
  }

  @override
  void dispose() {
    username.dispose();
    password.dispose();
    staff.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: widget.asRoute ? AppBar(title: const Text('تسجيل الدخول')) : null,
    body: SafeArea(
      child: constrained(
        Form(
          key: form,
          child: ListView(
            padding: const EdgeInsets.all(24),
            children: [
              const SizedBox(height: 32),
              const Center(child: Brand(size: 128)),
              const SizedBox(height: 20),
              Text(
                'أهلًا بك في سدد',
                textAlign: TextAlign.center,
                style: Theme.of(context).textTheme.headlineSmall,
              ),
              const SizedBox(height: 8),
              const Text('دفتر متجرك، أينما كنت', textAlign: TextAlign.center),
              const SizedBox(height: 24),
              SegmentedButton<bool>(
                segments: const [
                  ButtonSegment(
                    value: false,
                    label: Text('متجر'),
                    icon: Icon(Icons.storefront),
                  ),
                  ButtonSegment(
                    value: true,
                    label: Text('عميل'),
                    icon: Icon(Icons.person_outline),
                  ),
                ],
                selected: {customer},
                onSelectionChanged: busy
                    ? null
                    : (v) => setState(() {
                        customer = v.first;
                        username.clear();
                        password.clear();
                        error = '';
                      }),
              ),
              const SizedBox(height: 24),
              VoiceField(
                controller: username,
                label: customer ? 'رقم الهاتف' : 'اسم المستخدم',
                requiredField: true,
              ),
              VoiceField(
                controller: password,
                label: 'كلمة المرور',
                secret: true,
                requiredField: true,
              ),
              if (!customer)
                VoiceField(
                  controller: staff,
                  label: 'اسم مستخدم الجهاز (للمتاجر متعددة الأجهزة)',
                ),
              if (error.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.only(bottom: 16),
                  child: Text(
                    error,
                    style: TextStyle(
                      color: Theme.of(context).colorScheme.error,
                    ),
                  ),
                ),
              FilledButton(
                onPressed: busy ? null : login,
                child: Padding(
                  padding: const EdgeInsets.all(6),
                  child: Text(busy ? 'جارٍ الدخول…' : 'دخول'),
                ),
              ),
              if (AppConfig.demo)
                const Padding(
                  padding: EdgeInsets.only(top: 20),
                  child: Text(
                    'نسخة تجربة دون إنترنت\nالمتجر: 123 / 123\nالعميل: 0599999999 / 123123',
                    textAlign: TextAlign.center,
                  ),
                ),
              if (!customer && widget.app.accounts.isNotEmpty) ...[
                const SizedBox(height: 24),
                const Text('حسابات محفوظة على هذا الجهاز'),
                ...widget.app.accounts.map(
                  (s) => ListTile(
                    leading: const Icon(Icons.storefront),
                    title: Text('${s.account['name']}'),
                    subtitle: const Text('فتح البيانات المحلية'),
                    onTap: () async {
                      try {
                        await widget.app.switchAccount(s);
                        if (widget.asRoute && context.mounted) {
                          Navigator.pop(context);
                        }
                      } catch (e) {
                        if (context.mounted) {
                          notice(context, e);
                        }
                      }
                    },
                  ),
                ),
              ],
              const SizedBox(height: 32),
            ],
          ),
        ),
      ),
    ),
  );
}

class CustomerDemo extends StatelessWidget {
  final Ledger? ledger;
  final String phone;
  const CustomerDemo(this.ledger, this.phone, {super.key});
  @override
  Widget build(BuildContext context) {
    final contact = ledger?.contacts
        .where(
          (c) => normalizedPhone('${c['phone']}') == normalizedPhone(phone),
        )
        .firstOrNull;
    return Scaffold(
      appBar: AppBar(title: const Text('حساب العميل · تجربة')),
      body: constrained(
        ListView(
          padding: const EdgeInsets.all(20),
          children: [
            const Text(
              'يعرض هذا الحساب السجلات الفعلية المحفوظة برقمك في متجر التجربة.',
            ),
            if (contact == null)
              const EmptyState(
                'لا يوجد سجل لهذا الرقم',
                'أضف الشخص ورقمه من حساب المتجر لتظهر بياناته هنا.',
              )
            else ...[
              const SizedBox(height: 24),
              Text(
                '${contact['name']}',
                style: Theme.of(context).textTheme.titleLarge,
              ),
              Amount(ledger!.balance(idOf(contact))),
              ...ledger!.transactions
                  .where((t) => '${t['contactId']}' == idOf(contact))
                  .map(
                    (t) => ListTile(
                      title: Text(operationLabel('${t['kind']}')),
                      subtitle: Text(dateLabel(t['createdAt'])),
                      trailing: Amount(cents(t, 'amount')),
                    ),
                  ),
            ],
          ],
        ),
      ),
    );
  }
}

class LockPage extends StatefulWidget {
  final SadadController app;
  const LockPage(this.app, {super.key});
  @override
  State<LockPage> createState() => _LockPageState();
}

class _LockPageState extends State<LockPage> {
  final pin = TextEditingController();
  bool busy = false;
  String error = '';
  Future<void> unlock({bool biometric = false}) async {
    if (busy) {
      return;
    }
    setState(() => busy = true);
    try {
      final ok = biometric
          ? await widget.app.biometricUnlock()
          : await widget.app.unlock(pin.text);
      if (!ok && mounted) {
        setState(
          () => error = biometric
              ? 'لم يتم تأكيد البصمة.'
              : 'رمز القفل غير صحيح.',
        );
      }
    } catch (e) {
      if (mounted) {
        setState(() => error = e is FormatException ? e.message : '$e');
      }
    } finally {
      if (mounted) {
        setState(() => busy = false);
      }
    }
  }

  @override
  void dispose() {
    pin.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => PopScope(
    canPop: false,
    child: Scaffold(
      body: SafeArea(
        child: constrained(
          ListView(
            padding: const EdgeInsets.all(32),
            children: [
              const SizedBox(height: 80),
              const Center(child: Brand()),
              const SizedBox(height: 24),
              const Text(
                'سجل متجرك مقفل',
                textAlign: TextAlign.center,
                style: TextStyle(fontSize: 24),
              ),
              const SizedBox(height: 24),
              TextField(
                controller: pin,
                obscureText: true,
                keyboardType: TextInputType.number,
                textDirection: TextDirection.ltr,
                decoration: const InputDecoration(labelText: 'رمز القفل'),
                onSubmitted: (_) => unlock(),
              ),
              if (error.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.all(16),
                  child: Text(
                    error,
                    style: TextStyle(
                      color: Theme.of(context).colorScheme.error,
                    ),
                  ),
                ),
              const SizedBox(height: 20),
              FilledButton(
                onPressed: busy ? null : unlock,
                child: const Text('فتح'),
              ),
              TextButton.icon(
                onPressed: busy ? null : () => unlock(biometric: true),
                icon: const Icon(Icons.fingerprint),
                label: const Text('استخدام البصمة'),
              ),
            ],
          ),
        ),
      ),
    ),
  );
}

class PasswordPage extends StatefulWidget {
  final SadadController app;
  const PasswordPage(this.app, {super.key});
  @override
  State<PasswordPage> createState() => _PasswordPageState();
}

class _PasswordPageState extends State<PasswordPage> {
  final old = TextEditingController(),
      next = TextEditingController(),
      confirm = TextEditingController();
  bool busy = false;
  Future<void> save() async {
    if (busy) {
      return;
    }
    setState(() => busy = true);
    try {
      if (next.text.length < 12 ||
          next.text.length > 128 ||
          next.text != confirm.text) {
        throw const FormatException(
          'استخدم كلمة مرور من 12 إلى 128 محرفًا، وتأكد أن التأكيد مطابق.',
        );
      }
      await widget.app.changePassword(old.text, next.text);
      if (mounted) {
        if (Navigator.canPop(context)) {
          Navigator.pop(context);
        }
        notice(context, 'تم تغيير كلمة المرور.');
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
    old.dispose();
    next.dispose();
    confirm.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('تغيير كلمة المرور')),
    body: constrained(
      ListView(
        padding: const EdgeInsets.all(20),
        children: [
          if (widget.app.session!.forcePasswordChange)
            const Padding(
              padding: EdgeInsets.only(bottom: 20),
              child: Text('غيّر كلمة المرور المؤقتة لإكمال تفعيل حسابك.'),
            ),
          VoiceField(
            controller: old,
            label: 'كلمة المرور الحالية',
            secret: true,
          ),
          VoiceField(
            controller: next,
            label: 'كلمة المرور الجديدة',
            secret: true,
          ),
          VoiceField(
            controller: confirm,
            label: 'تأكيد كلمة المرور الجديدة',
            secret: true,
          ),
          FilledButton(onPressed: busy ? null : save, child: const Text('حفظ')),
        ],
      ),
    ),
  );
}
