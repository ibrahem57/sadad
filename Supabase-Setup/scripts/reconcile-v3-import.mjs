#!/usr/bin/env node
/** Compare an approved source bundle to a saved authoritative v3 snapshot. */
import path from "node:path";
import { MAX_CENTS, canonical, fail, hash, integer, json, newDirectory, parseOptions, reconcileRows, required, uniqueById, writeJson } from "./migration-lib.mjs";

function normalized(value, field) {
  if (field.endsWith("Cents")) return integer(value, field, 0n, MAX_CENTS);
  if (field === "createdAt") return integer(value, field, 1n, BigInt(Number.MAX_SAFE_INTEGER));
  return value;
}
async function main() {
  const args = process.argv.slice(2);
  if (!args.length || args[0] === "--help") {
    console.log("node reconcile-v3-import.mjs --bundle <bundle.json> --server-snapshot <target.json> --out <new-report-directory>\nThe snapshot envelope must identify projectRef, storeId, acquiredAt and snapshot. No network or database writes occur.");
    return;
  }
  const options = parseOptions(args, ["bundle", "server-snapshot", "out"]);
  const prepared = json(required(options, "bundle"));
  const bundle = prepared.bundle;
  if (bundle?.format !== "sadad-v3-import-preparation" || bundle.schemaVersion !== 1) fail("Unrecognized import bundle.");
  if (hash(canonical(bundle)) !== prepared.contentSha256) fail("Bundle checksum does not match; the evidence was changed.");
  const target = json(required(options, "server-snapshot"));
  if (target.projectRef !== bundle.target.projectRef || String(target.storeId) !== bundle.target.storeId) fail("Snapshot project/store differs from the ownership mapping.");
  if (!target.acquiredAt || !Number.isFinite(Date.parse(target.acquiredAt))) fail("Snapshot acquisition timestamp is required.");
  const snapshot = target.snapshot;
  if (!snapshot || !Array.isArray(snapshot.contacts) || !Array.isArray(snapshot.debts) || !Array.isArray(snapshot.payments)) fail("Provide the complete canonical snapshot, not an unchanged/pending cache response.");
  if (String(snapshot.account?.id) !== bundle.target.storeId) fail("Canonical snapshot account.id does not confirm the target store.");
  if (snapshot.currency !== "ILS" || !snapshot.epoch || snapshot.cursor == null) fail("Missing v3 currency/epoch/cursor metadata.");
  const differences = [];
  const comparedFields = {
    contacts: ["name", "phone", "category", "note", "whatsappOptIn", "creditLimitCents", "createdAt", "createdBy", "archivedAt"],
    debts: ["contactId", "direction", "amountCents", "note", "dueDate", "createdAt", "createdBy"],
    payments: ["debtId", "amountCents", "method", "note", "createdAt", "createdBy", "reversedAt"],
  };
  for (const [table, fields] of Object.entries(comparedFields)) {
    const expected = uniqueById(bundle.ledger[table], table);
    const actual = uniqueById(snapshot[table], table);
    for (const [id, wanted] of expected) {
      const found = actual.get(id);
      if (!found) { differences.push({ table, id, issue: "missing_record" }); continue; }
      for (const field of fields) {
        const left = normalized(wanted[field], field);
        const right = normalized(found[field], field);
        if (canonical(left) !== canonical(right)) differences.push({ table, id, field, issue: "different_value", expected: left, actual: right });
      }
    }
    for (const id of actual.keys()) if (!expected.has(id)) differences.push({ table, id, issue: "unexpected_record" });
  }
  const expectedReconciliation = reconcileRows(bundle.ledger.contacts, bundle.ledger.debts, bundle.ledger.payments);
  if (canonical(expectedReconciliation) !== canonical(bundle.reconciliation)) fail("Prepared source reconciliation does not match its own ledger.");
  const actualReconciliation = reconcileRows(snapshot.contacts, snapshot.debts, snapshot.payments);
  const actualBalances = new Map(actualReconciliation.perContact.map(row => [row.contactId, row]));
  const contactSnapshots = new Map(snapshot.contacts.map(row => [row.id, row]));
  for (const expected of expectedReconciliation.perContact) {
    const actual = actualBalances.get(expected.contactId);
    if (canonical(expected) !== canonical(actual)) differences.push({ table: "contact_balances", id: expected.contactId, issue: "different_balance", expected, actual: actual ?? null });
    const rendered = contactSnapshots.get(expected.contactId);
    if (!rendered) continue;
    for (const field of ["receivableCents", "payableCents", "creditCents", "netCents"]) {
      const actualValue = integer(rendered[field], field, field === "netCents" ? -9_223_372_036_854_775_807n : 0n);
      if (actualValue !== actual[field]) differences.push({ table: "contact_balances", id: expected.contactId, field, issue: "server_balance_disagrees_with_rows", expected: actual[field], actual: actualValue });
    }
  }
  for (const field of ["receivableCents", "payableCents", "creditCents", "netCents"]) {
    const actualValue = integer(snapshot.totals?.[field], field, field === "netCents" ? -9_223_372_036_854_775_807n : 0n);
    if (actualValue !== actualReconciliation.totals[field]) differences.push({ table: "totals", field, issue: "server_total_disagrees_with_rows", expected: actualReconciliation.totals[field], actual: actualValue });
  }
  const paid = new Map();
  for (const payment of snapshot.payments) paid.set(payment.debtId, (paid.get(payment.debtId) || 0n) + BigInt(payment.amountCents));
  for (const debt of snapshot.debts) {
    const amount = BigInt(debt.amountCents), amountPaid = paid.get(debt.id) || 0n;
    const expected = { paidCents: amountPaid, remainingCents: amount > amountPaid ? amount - amountPaid : 0n, creditCents: amountPaid > amount ? amountPaid - amount : 0n };
    for (const [field, value] of Object.entries(expected)) if (integer(debt[field], field) !== value.toString()) differences.push({ table: "debt_balances", id: debt.id, field, issue: "server_balance_disagrees_with_rows", expected: value.toString(), actual: debt[field] });
  }
  const report = { format: "sadad-v3-import-reconciliation", checkedAt: new Date().toISOString(), bundleSha256: prepared.contentSha256, snapshotSha256: hash(canonical(target)), target: bundle.target, cursor: String(snapshot.cursor), epoch: snapshot.epoch, matches: differences.length === 0, authorizesCutover: false, expected: expectedReconciliation, actual: actualReconciliation, differences, requiredReview: bundle.requiredReview };
  const output = newDirectory(required(options, "out"));
  writeJson(path.join(output, "report.json"), report);
  console.log(`Initial ledger reconciliation: ${report.matches ? "MATCH" : `${differences.length} DIFFERENCES`}. Report: ${output}`);
  console.log("This comparison does not verify historical evidence, auth, backups or device behavior and does not authorize cutover.");
  if (!report.matches) process.exitCode = 2;
}
main().catch(error => { console.error(`Reconciliation stopped: ${error.message}`); process.exitCode = 1; });
