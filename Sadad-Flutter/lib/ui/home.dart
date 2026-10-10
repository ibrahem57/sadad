import 'package:flutter/material.dart';

import '../core/config.dart';
import '../core/controller.dart';
import '../core/models.dart';
import 'auth.dart';
import 'ledger_pages.dart';
import 'settings.dart';
import 'shared.dart';

class HomeShell extends StatefulWidget {
  final SadadController app;
  const HomeShell(this.app, {super.key});
  @override
  State<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends State<HomeShell> {
  int tab = 0;
  @override
  Widget build(BuildContext context) {
    final app = widget.app;
    final pages = [
      Overview(app, onContacts: () => setState(() => tab = 1)),
      ContactsView(app),
      HistoryView(app),
      SettingsView(app),
    ];
    return Scaffold(
      appBar: AppBar(
        title: Row(
          children: [
            const Brand(size: 40),
            const SizedBox(width: 8),
            const Text('سدد'),
            const Spacer(),
            Flexible(
              child: Text(
                '${app.session!.account['name']}',
                textAlign: TextAlign.left,
                overflow: TextOverflow.ellipsis,
                style: Theme.of(context).textTheme.titleMedium,
              ),
            ),
          ],
        ),
        actions: [
          IconButton(
            tooltip: 'التنبيهات',
            onPressed: () => openPage(context, NotificationsPage(app)),
            icon: Badge(
              isLabelVisible: app.pending.isNotEmpty,
              label: Text('${app.pending.length}'),
              child: const Icon(Icons.notifications_none),
            ),
          ),
        ],
      ),
      body: Column(
        children: [
          if (AppConfig.demo)
            Container(
              width: double.infinity,
              color: Theme.of(context).colorScheme.primaryContainer,
              padding: const EdgeInsets.all(5),
              child: const Text(
                'نسخة تجربة محلية',
                textAlign: TextAlign.center,
              ),
            ),
          if (app.syncing) const LinearProgressIndicator(minHeight: 2),
          if (app.error.isNotEmpty)
            Material(
              color: Theme.of(context).colorScheme.errorContainer,
              child: ListTile(
                dense: true,
                leading: const Icon(Icons.cloud_off),
                title: Text(
                  app.error,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                ),
                onTap: () => openPage(context, JournalPage(app)),
                trailing: IconButton(
                  tooltip: 'إعادة المحاولة',
                  onPressed: app.syncing
                      ? null
                      : () => app.sync(retryNow: true),
                  icon: const Icon(Icons.refresh),
                ),
              ),
            ),
          Expanded(
            child: constrained(IndexedStack(index: tab, children: pages)),
          ),
        ],
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: tab,
        onDestinationSelected: (v) => setState(() => tab = v),
        destinations: const [
          NavigationDestination(
            icon: Icon(Icons.home_outlined),
            selectedIcon: Icon(Icons.home),
            label: 'الرئيسية',
          ),
          NavigationDestination(
            icon: Icon(Icons.people_outline),
            selectedIcon: Icon(Icons.people),
            label: 'الأشخاص',
          ),
          NavigationDestination(icon: Icon(Icons.history), label: 'السجل'),
          NavigationDestination(
            icon: Icon(Icons.settings_outlined),
            selectedIcon: Icon(Icons.settings),
            label: 'الإعدادات',
          ),
        ],
      ),
    );
  }
}

class Overview extends StatelessWidget {
  final SadadController app;
  final VoidCallback onContacts;
  const Overview(this.app, {super.key, required this.onContacts});
  @override
  Widget build(BuildContext context) {
    final ledger = app.visible;
    final recent =
        ledger.contacts.where((c) => c['archivedAt'] == null).toList()
          ..sort((a, b) {
            int last(Json c) => ledger.transactions
                .where((t) => '${t['contactId']}' == idOf(c))
                .fold(
                  0,
                  (sum, t) => integer(t['createdAt']) > sum
                      ? integer(t['createdAt'])
                      : sum,
                );
            return last(b).compareTo(last(a));
          });
    return RefreshIndicator(
      onRefresh: () => app.sync(retryNow: true),
      child: ListView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.all(20),
        children: [
          Text(
            'أهلًا ${app.session!.staffName.isEmpty ? 'بك' : app.session!.staffName}',
            style: Theme.of(context).textTheme.headlineSmall,
          ),
          const SizedBox(height: 8),
          Text(
            'آخر تحديث: ${dateLabel(app.cache.refreshedAt)}',
            style: Theme.of(context).textTheme.bodySmall,
          ),
          const SizedBox(height: 24),
          Card(
            color: Theme.of(context).colorScheme.primary,
            child: InkWell(
              borderRadius: BorderRadius.circular(20),
              onTap: onContacts,
              child: Padding(
                padding: const EdgeInsets.all(24),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Row(
                      children: [
                        Icon(
                          Icons.account_balance_wallet_outlined,
                          color: Colors.white,
                        ),
                        SizedBox(width: 12),
                        Text(
                          'الدين الحالي',
                          style: TextStyle(color: Colors.white, fontSize: 18),
                        ),
                      ],
                    ),
                    const SizedBox(height: 16),
                    if (app.cache.ledger == null)
                      const Text(
                        'بانتظار تحميل السجل',
                        style: TextStyle(color: Colors.white, fontSize: 24),
                      )
                    else
                      Amount(
                        app.confirmed.total,
                        style: const TextStyle(
                          color: Colors.white,
                          fontSize: 34,
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                    if (app.pending.isNotEmpty) ...[
                      const SizedBox(height: 8),
                      Text(
                        'بعد العمليات المعلقة: ${money(ledger.total)}',
                        style: const TextStyle(color: Colors.white),
                      ),
                      const Text(
                        'بانتظار اعتماد الخادم',
                        style: TextStyle(color: Colors.white),
                      ),
                    ],
                  ],
                ),
              ),
            ),
          ),
          const SizedBox(height: 16),
          Wrap(
            spacing: 10,
            runSpacing: 10,
            children: [
              FilledButton.icon(
                onPressed: app.canWrite ? () => addContact(context, app) : null,
                icon: const Icon(Icons.person_add_alt),
                label: const Text('إضافة شخص'),
              ),
              OutlinedButton.icon(
                onPressed: () => openPage(context, ReportPage(app)),
                icon: const Icon(Icons.description_outlined),
                label: const Text('التقارير'),
              ),
              OutlinedButton.icon(
                onPressed: () => openPage(context, JournalPage(app)),
                icon: const Icon(Icons.cloud_sync_outlined),
                label: Text(
                  'المزامنة${app.pending.isEmpty ? '' : ' (${app.pending.length})'}',
                ),
              ),
            ],
          ),
          SectionTitle(
            'الأشخاص',
            action: TextButton(
              onPressed: onContacts,
              child: const Text('عرض الكل'),
            ),
          ),
          if (recent.isEmpty)
            const EmptyState(
              'ابدأ دفتر متجرك',
              'أضف الشخص، ثم سجّل دينًا أو دفعة من صفحته.',
              icon: Icons.storefront,
            ),
          ...recent.take(8).map((c) => ContactTile(app, c)),
        ],
      ),
    );
  }
}

class NotificationsPage extends StatelessWidget {
  final SadadController app;
  const NotificationsPage(this.app, {super.key});
  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: app,
    builder: (context, _) {
      final due = app.visible.debts
          .where(
            (d) =>
                cents(d, 'remaining') > 0 &&
                integer(d['dueDate']) > 0 &&
                integer(d['dueDate']) <= DateTime.now().millisecondsSinceEpoch,
          )
          .toList();
      return Scaffold(
        appBar: AppBar(title: const Text('التنبيهات')),
        body: constrained(
          ListView(
            padding: const EdgeInsets.all(20),
            children: [
              if (app.needsLogin)
                Card(
                  child: ListTile(
                    leading: const Icon(Icons.login),
                    title: const Text('يلزم تسجيل الدخول مجددًا'),
                    onTap: () =>
                        openPage(context, LoginPage(app, asRoute: true)),
                  ),
                ),
              if (app.pending.isNotEmpty)
                Card(
                  child: ListTile(
                    leading: const Icon(Icons.sync),
                    title: Text(
                      '${app.pending.length} عملية بانتظار المزامنة أو المراجعة',
                    ),
                    onTap: () => openPage(context, JournalPage(app)),
                  ),
                ),
              ...due.map(
                (d) => Card(
                  child: ListTile(
                    leading: const Icon(Icons.event),
                    title: Text(
                      'استحقاق ${app.visible.contact('${d['contactId']}')?['name']}',
                    ),
                    subtitle: Text(dateLabel(d['dueDate'])),
                    trailing: Amount(cents(d, 'remaining')),
                    onTap: () => openPage(context, DebtPage(app, idOf(d))),
                  ),
                ),
              ),
              if (due.isEmpty && app.pending.isEmpty && !app.needsLogin)
                const EmptyState(
                  'لا توجد تنبيهات',
                  'ستظهر هنا الديون المستحقة والعمليات التي تحتاج متابعة.',
                ),
            ],
          ),
        ),
      );
    },
  );
}
