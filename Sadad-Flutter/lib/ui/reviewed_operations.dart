import 'package:flutter/material.dart';

import '../core/controller.dart';
import '../core/models.dart';
import 'shared.dart';

class ReviewedOperationsPage extends StatelessWidget {
  final SadadController app;
  const ReviewedOperationsPage(this.app, {super.key});
  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('إدخالات احتُفظ بها للمراجعة')),
    body: constrained(
      FutureBuilder<List<Command>>(
        future: app.store.reviewed(app.session!.scope),
        builder: (context, snapshot) {
          if (snapshot.hasError) {
            return const EmptyState('تعذّر فتح السجل', 'أعد فتح الصفحة.');
          }
          if (!snapshot.hasData) {
            return const Center(child: CircularProgressIndicator());
          }
          final rows = snapshot.data!;
          if (rows.isEmpty) {
            return const EmptyState(
              'لا توجد إدخالات محفوظة للمراجعة',
              'تظهر هنا العمليات التي راجعتها وقررت عدم إرسالها.',
            );
          }
          return ListView.builder(
            padding: const EdgeInsets.all(20),
            itemCount: rows.length,
            itemBuilder: (context, index) {
              final command = rows[index], payload = command.payload;
              return Card(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        operationLabel(command.type),
                        style: Theme.of(context).textTheme.titleMedium,
                      ),
                      Text(dateLabel(command.createdAt)),
                      if (payload['name'] != null)
                        SelectableText('${payload['name']}'),
                      if (payload['phone'] != null)
                        SelectableText('${payload['phone']}'),
                      if (payload['amountCents'] != null)
                        Amount(integer(payload['amountCents'])),
                      if (payload['note'] != null)
                        SelectableText('${payload['note']}'),
                      if (payload['reason'] != null)
                        SelectableText('${payload['reason']}'),
                      Text(command.error),
                      const SizedBox(height: 8),
                      const Text(
                        'احتُفظ بالإدخال؛ لا يمثل عملية مالية معتمدة.',
                      ),
                    ],
                  ),
                ),
              );
            },
          );
        },
      ),
    ),
  );
}
