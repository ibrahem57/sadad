type Row = Record<string, any>;
const timestamp = (value: unknown) => typeof value === 'number' ? value : Date.parse(String(value));
const money = (value: number) => value / 100;

/** Convert an audited ledger read to the dashboard's existing display contract.
 * Corrections replace principal; reversals remove a payment from the balance,
 * while every original transaction remains visible in the report.
 */
export function adminLedgerSnapshot(raw: Row, account: Row): Row {
  const corrections = new Map<string, Row>();
  for (const adjustment of raw.adjustments || []) {
    const previous = corrections.get(adjustment.debt_id);
    if (!previous || Number(adjustment.version) > Number(previous.version)) corrections.set(adjustment.debt_id, adjustment);
  }
  const reversed = new Set<string>((raw.reversals || []).map((row: Row) => row.payment_id));
  const paid = new Map<string, number>();
  for (const payment of raw.payments || []) {
    if (!reversed.has(payment.id)) paid.set(payment.debt_id, (paid.get(payment.debt_id) || 0) + Number(payment.amount_cents));
  }
  const contactRows = new Map<string, Row>((raw.contacts || []).map((row: Row) => [row.id, row]));
  const debts = (raw.debts || []).map((row: Row) => {
    const principal = Number(corrections.get(row.id)?.amount_cents ?? row.amount_cents);
    const paidCents = paid.get(row.id) || 0;
    return { id: row.id, contactId: row.contact_id, contactName: contactRows.get(row.contact_id)?.name || '', direction: row.direction,
      originalAmount: money(Number(row.amount_cents)), amount: money(principal), paid: money(paidCents), remaining: money(principal - paidCents),
      note: row.note || '', dueDate: row.due_date || '', createdAt: timestamp(row.created_at) };
  });
  const debtRows = new Map<string, Row>(debts.map((row: Row) => [row.id, row]));
  const payments = (raw.payments || []).map((row: Row) => {
    const debt = debtRows.get(row.debt_id);
    return { id: row.id, debtId: row.debt_id, contactId: debt?.contactId, contactName: debt?.contactName || '', direction: debt?.direction,
      amount: money(Number(row.amount_cents)), reversed: reversed.has(row.id), method: row.method, note: row.note || '', createdAt: timestamp(row.created_at) };
  });
  const paymentRows = new Map<string, Row>(payments.map((row: Row) => [row.id, row]));
  const contacts = (raw.contacts || []).map((row: Row) => {
    const own = debts.filter((debt: Row) => debt.contactId === row.id);
    return { id: row.id, name: row.name, phone: row.phone || '', category: row.category || '', note: row.note || '', archived: !!row.archived,
      receivable: own.filter((debt: Row) => debt.direction === 'receivable').reduce((sum: number, debt: Row) => sum + debt.remaining, 0),
      payable: own.filter((debt: Row) => debt.direction === 'payable').reduce((sum: number, debt: Row) => sum + debt.remaining, 0) };
  });
  const transactions: Row[] = [
    ...(raw.debts || []).map((row: Row) => ({ ...debtRows.get(row.id), kind: 'debt', amount: money(Number(row.amount_cents)) })),
    ...payments.map((row: Row) => ({ ...row, kind: 'payment' })),
    ...(raw.adjustments || []).map((row: Row) => ({ ...debtRows.get(row.debt_id), id: row.id, kind: 'correction', amount: money(Number(row.amount_cents) - Number(row.previous_amount_cents)), note: row.reason, createdAt: timestamp(row.created_at) })),
    ...(raw.reversals || []).map((row: Row) => ({ ...paymentRows.get(row.payment_id), id: row.id, kind: 'reversal', amount: -(paymentRows.get(row.payment_id)?.amount || 0), note: row.reason, createdAt: timestamp(row.created_at) })),
  ].sort((a, b) => b.createdAt - a.createdAt);
  const receivableCents = debts.filter((row: Row) => row.direction === 'receivable').reduce((sum: number, row: Row) => sum + Math.round(row.remaining * 100), 0);
  const payableCents = debts.filter((row: Row) => row.direction === 'payable').reduce((sum: number, row: Row) => sum + Math.round(row.remaining * 100), 0);
  return { account, contacts, debts, payments, transactions, totals: { receivable: money(receivableCents), payable: money(payableCents), net: money(receivableCents - payableCents) },
    requestId: raw.requestId, epoch: raw.epoch, cursor: raw.cursor };
}

export function ledgerAuditEvents(raw: Row): Row[] {
  return (raw.events || []).map((row: Row) => ({ action: row.kind, actor: row.installation_id,
    description: row.command?.payload?.reason || row.command?.payload?.note || row.command?.payload?.name || '',
    createdAt: timestamp(row.created_at), deviceId: row.installation_id })).sort((a: Row, b: Row) => b.createdAt - a.createdAt).slice(0, 200);
}
