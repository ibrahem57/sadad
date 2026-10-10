import 'package:flutter/services.dart';
import 'package:pdf/pdf.dart';
import 'package:pdf/widgets.dart' as pw;
import 'package:printing/printing.dart';

import '../core/controller.dart';
import '../core/models.dart';
import '../ui/shared.dart';

class Reports {
  static Future<Uint8List> statement(
    SadadController app, {
    String? contactId,
    DateTime? from,
    DateTime? until,
    Json? receipt,
    Json? voucher,
  }) async {
    if (app.cache.ledger == null && voucher == null) {
      throw const FormatException('حمّل بيانات الحساب أولًا لإصدار كشف.');
    }
    final ledger = app.confirmed;
    final regular = pw.Font.ttf(
      await rootBundle.load('assets/fonts/Tajawal-Regular.ttf'),
    );
    final bold = pw.Font.ttf(
      await rootBundle.load('assets/fonts/Tajawal-Bold.ttf'),
    );
    final logo = pw.MemoryImage(
      (await rootBundle.load('assets/images/logo-light.jpeg')).buffer
          .asUint8List(),
    );
    final pdf = pw.Document();
    final contact = contactId == null ? null : ledger.contact(contactId);
    final events =
        ledger.transactions
            .where(
              (t) =>
                  (contactId == null || '${t['contactId']}' == contactId) &&
                  (from == null ||
                      integer(t['createdAt']) >= from.millisecondsSinceEpoch) &&
                  (until == null ||
                      integer(t['createdAt']) <
                          until
                              .add(const Duration(days: 1))
                              .millisecondsSinceEpoch),
            )
            .toList()
          ..sort(
            (a, b) =>
                integer(a['createdAt']).compareTo(integer(b['createdAt'])),
          );
    final title = voucher != null
        ? 'سند صرف'
        : receipt != null
        ? 'إيصال دفعة'
        : 'كشف حساب';
    pdf.addPage(
      pw.MultiPage(
        pageFormat: PdfPageFormat.a4,
        margin: const pw.EdgeInsets.all(32),
        theme: pw.ThemeData.withFont(base: regular, bold: bold),
        textDirection: pw.TextDirection.rtl,
        header: (_) => pw.Row(
          mainAxisAlignment: pw.MainAxisAlignment.spaceBetween,
          children: [
            pw.Column(
              crossAxisAlignment: pw.CrossAxisAlignment.start,
              children: [
                pw.Text(
                  '${app.session!.account['name']}',
                  style: pw.TextStyle(font: bold, fontSize: 20),
                ),
                pw.Text(title),
              ],
            ),
            pw.Image(logo, width: 64, height: 64),
          ],
        ),
        footer: (context) => pw.Text(
          'سدد · ${context.pageNumber} / ${context.pagesCount} · ${dateLabel(DateTime.now().millisecondsSinceEpoch)}',
          style: const pw.TextStyle(fontSize: 9),
        ),
        build: (_) => [
          pw.SizedBox(height: 16),
          if (app.session!.demo)
            pw.Text('نسخة تجربة محلية — لا يمثل هذا المستند سجلات خادم رسمي.'),
          if (app.pending.isNotEmpty)
            pw.Text(
              'توجد عمليات بانتظار الاعتماد. الكشف يعرض السجل المعتمد فقط.',
            ),
          if (contact != null)
            pw.Text('الشخص: ${contact['name']} · ${contact['phone'] ?? ''}'),
          if (from != null || until != null)
            pw.Text(
              'الفترة: ${from == null ? 'البداية' : dateLabel(from.millisecondsSinceEpoch)} إلى ${until == null ? 'اليوم' : dateLabel(until.millisecondsSinceEpoch)}',
            ),
          pw.Text('آخر تحديث للسجل: ${dateLabel(app.cache.refreshedAt)}'),
          pw.SizedBox(height: 18),
          if (voucher != null) ...[
            pw.Text('المستفيد: ${voucher['name']}'),
            pw.Text('المبلغ: ${money(integer(voucher['amountCents']))}'),
            pw.Text('البيان: ${voucher['note']}'),
            pw.SizedBox(height: 30),
            pw.Text('التوقيع: __________________'),
            pw.Text('هذا نموذج سند صرف؛ لا يضيف حركة إلى دفتر الديون.'),
          ] else if (receipt != null) ...[
            pw.Text('المبلغ: ${money(cents(receipt, 'amount'))}'),
            pw.Text('الطريقة: ${receipt['method']}'),
            pw.Text('التاريخ: ${dateLabel(receipt['createdAt'])}'),
            pw.Text('الملاحظة: ${receipt['note'] ?? ''}'),
            pw.Text('مرجع الدفعة: ${receipt['id']}'),
            if (receipt['reversedAt'] != null)
              pw.Text(
                'عُكست هذه الدفعة في ${dateLabel(receipt['reversedAt'])}',
              ),
          ] else ...[
            pw.Text(
              'الدين الحالي: ${money(contactId == null ? ledger.total : ledger.balance(contactId))}',
              style: pw.TextStyle(font: bold, fontSize: 18),
            ),
            pw.SizedBox(height: 12),
            pw.TableHelper.fromTextArray(
              headers: ['الملاحظة', 'المبلغ', 'الحركة', 'الشخص', 'التاريخ'],
              data: events
                  .map(
                    (t) => [
                      '${t['note'] ?? t['reason'] ?? ''}',
                      money(cents(t, 'amount')),
                      operationLabel('${t['kind']}'),
                      '${t['contactName'] ?? ledger.contact('${t['contactId']}')?['name'] ?? ''}',
                      dateLabel(t['createdAt']),
                    ],
                  )
                  .toList(),
              headerStyle: pw.TextStyle(font: bold),
              cellStyle: const pw.TextStyle(fontSize: 10),
              headerDecoration: const pw.BoxDecoration(
                color: PdfColors.green100,
              ),
              cellAlignments: {
                for (var i = 0; i < 5; i++) i: pw.Alignment.centerRight,
              },
            ),
            if (events.isEmpty) pw.Text('لا توجد حركات في الفترة المحددة.'),
          ],
        ],
      ),
    );
    return pdf.save();
  }

  static Future<void> deliver(
    SadadController app, {
    String? contactId,
    DateTime? from,
    DateTime? until,
    bool share = false,
    Json? receipt,
    Json? voucher,
  }) async {
    if (!app.session!.allowed('reports')) {
      throw const FormatException('التقارير غير مفعلة لهذا المتجر.');
    }
    app.privacySuspended = true;
    try {
      final bytes = await statement(
        app,
        contactId: contactId,
        from: from,
        until: until,
        receipt: receipt,
        voucher: voucher,
      );
      if (share) {
        await Printing.sharePdf(
          bytes: bytes,
          filename: 'Sadad-${DateTime.now().millisecondsSinceEpoch}.pdf',
        );
      } else {
        await Printing.layoutPdf(onLayout: (_) async => bytes, name: 'سدد');
      }
    } finally {
      app.privacySuspended = false;
    }
  }
}
