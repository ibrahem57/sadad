#!/usr/bin/env node
/** Local evidence/preparation only. This module intentionally has no network API. */
import { existsSync } from "node:fs";
import path from "node:path";
import { backup, DatabaseSync } from "node:sqlite";
import { MAX_CENTS, UUID, fail, canonical, hash, fileHash, json, writeJson, newDirectory, integer, text, bool, entityId, reconcileRows, required, parseOptions } from "./migration-lib.mjs";

const VERSION = 1;
const LEDGER = ["contacts", "debts", "payments"];
const HISTORY = ["audit_events", "contact_archives", "store_backups"];
function sqlName(value) { return `"${value.replaceAll('"', '""')}"`; }
function rows(db, sql, ...bindings) {
  const statement = db.prepare(sql);
  statement.setReadBigInts(true);
  return statement.all(...bindings);
}
function openReadOnly(file) {
  const db = new DatabaseSync(path.resolve(file), { readOnly: true, allowExtension: false, timeout: 5000 });
  db.exec("PRAGMA query_only=ON; PRAGMA trusted_schema=OFF;");
  return db;
}
function inspect(db) {
  const tables = rows(db, "SELECT name FROM sqlite_schema WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name").map(row => row.name);
  for (const table of LEDGER) if (!tables.includes(table)) fail(`Missing legacy table ${table}.`);
  const columns = Object.fromEntries(LEDGER.map(table => [table, rows(db, `PRAGMA table_info(${sqlName(table)})`).map(row => row.name)]));
  const scoped = LEDGER.map(table => columns[table].includes("store_id"));
  if (scoped.some(Boolean) !== scoped.every(Boolean)) fail("Mixed store scoping; manual schema review required.");
  const sourceKind = scoped.every(Boolean) ? "server" : "handset";
  if ((sourceKind === "server") !== tables.includes("stores")) fail("Ambiguous source ownership/schema.");
  const integrity = rows(db, "PRAGMA integrity_check");
  if (integrity.length !== 1 || integrity[0].integrity_check !== "ok") fail("SQLite integrity check failed. Preserve the source and recover a sound backup first.");
  const foreignKeys = rows(db, "PRAGMA foreign_key_check");
  if (foreignKeys.length) fail(`SQLite contains ${foreignKeys.length} foreign-key violations; automatic preparation refused.`);
  const counts = Object.fromEntries(tables.map(table => [table, rows(db, `SELECT count(*) AS n FROM ${sqlName(table)}`)[0].n.toString()]));
  const stores = sourceKind === "server" ? rows(db, "SELECT id,name,username,status FROM stores ORDER BY id") : [];
  return { sourceKind, tables, columns, counts, stores, sqliteUserVersion: rows(db, "PRAGMA user_version")[0].user_version.toString() };
}
function selectedRows(db, sourceKind, table, storeId) {
  return rows(db, `SELECT * FROM ${sqlName(table)}${sourceKind === "server" ? " WHERE store_id=?" : ""} ORDER BY id`, ...(sourceKind === "server" ? [BigInt(storeId)] : []));
}
async function capture(options) {
  const source = path.resolve(required(options, "source"));
  const output = newDirectory(required(options, "out"));
  const db = openReadOnly(source);
  try {
    // backup() includes committed WAL pages; copying only a .db file does not.
    await backup(db, path.join(output, "evidence.sqlite"));
  } finally { db.close(); }
  const snapshotPath = path.join(output, "evidence.sqlite");
  // Change only the newly created backup into a standalone rollback-journal file.
  // Otherwise a backup of a WAL source can retain WAL mode and need sidecars.
  const standalone = new DatabaseSync(snapshotPath, { allowExtension: false });
  try { standalone.exec("PRAGMA trusted_schema=OFF; PRAGMA journal_mode=DELETE;"); }
  finally { standalone.close(); }
  const frozen = openReadOnly(snapshotPath);
  let inventory;
  try { inventory = inspect(frozen); } finally { frozen.close(); }
  const snapshotSha256 = await fileHash(snapshotPath);
  writeJson(path.join(output, "inventory.json"), { schemaVersion: VERSION, capturedAt: new Date().toISOString(), originalSourcePath: source, snapshotSha256, ...inventory });
  writeJson(path.join(output, "mapping.example.json"), {
    schemaVersion: VERSION, sourceKind: inventory.sourceKind,
    sourceLineageId: "REPLACE_WITH_ONE_UUID_FOR_THIS_DATABASE_LINEAGE_AND_KEEP_IT",
    snapshotSha256, sourceStoreId: inventory.sourceKind === "handset" ? "unscoped-handset" : "REPLACE_WITH_SOURCE_STORE_ID",
    targetProjectRef: "vhftjmiltqfwmrszdtgf", targetStoreId: "REPLACE_WITH_VERIFIED_TARGET_STORE_ID",
    ownershipConfirmedBy: "REPLACE_WITH_REVIEWER", ownershipEvidence: "REPLACE_WITH_STORE_OWNERSHIP_EVIDENCE_REFERENCE",
    writeFreezeReference: "REPLACE_WITH_WRITE_FREEZE_RECORD", frozenAt: "REPLACE_WITH_ISO_TIMESTAMP",
    handsetOverlapDecision: inventory.sourceKind === "handset" ? "REQUIRES_REVIEW_OF_SERVER_AND_OTHER_HANDSETS" : "not-applicable",
  });
  console.log(`Captured a consistent ${inventory.sourceKind} snapshot. SHA256: ${snapshotSha256}`);
  console.log(`Evidence and mapping template: ${output}. No remote change was made. The evidence database contains private data and may contain credentials.`);
}
async function prepare(options) {
  const source = path.resolve(required(options, "snapshot"));
  const mapping = json(required(options, "mapping"));
  if (mapping.schemaVersion !== VERSION) fail("Unsupported mapping schemaVersion.");
  if (!UUID.test(mapping.sourceLineageId || "")) fail("sourceLineageId must be an explicitly assigned, permanent UUID for the original database lineage.");
  if (!/^[a-z0-9]{20}$/.test(mapping.targetProjectRef || "")) fail("Explicit targetProjectRef is required.");
  mapping.targetStoreId = integer(mapping.targetStoreId, "targetStoreId", 1n);
  for (const key of ["ownershipConfirmedBy", "ownershipEvidence", "writeFreezeReference", "frozenAt"]) {
    text(mapping[key], key, 2000, true);
    if (mapping[key].startsWith("REPLACE_")) fail(`${key} has not been completed.`);
  }
  if (!/^\d{4}-\d{2}-\d{2}T.*(?:Z|[+-]\d{2}:\d{2})$/.test(mapping.frozenAt) || !Number.isFinite(Date.parse(mapping.frozenAt)) || Date.parse(mapping.frozenAt) > Date.now()) fail("frozenAt must be a valid past ISO timestamp with timezone.");
  const snapshotSha256 = await fileHash(source);
  if (snapshotSha256 !== mapping.snapshotSha256) fail("Snapshot SHA256 differs from the approved ownership mapping.");
  for (const suffix of ["-wal", "-journal"]) if (existsSync(`${source}${suffix}`)) fail(`Snapshot has a ${suffix} sidecar. Capture a standalone snapshot first.`);
  const db = openReadOnly(source);
  let bundle;
  try {
    db.exec("BEGIN;");
    const inventory = inspect(db);
    if (mapping.sourceKind !== inventory.sourceKind) fail("The mapping sourceKind disagrees with the captured schema.");
    let sourceStore = null;
    if (inventory.sourceKind === "server") {
      mapping.sourceStoreId = integer(mapping.sourceStoreId, "sourceStoreId", 1n);
      sourceStore = inventory.stores.find(store => store.id.toString() === mapping.sourceStoreId);
      if (!sourceStore) fail("Source store does not exist in this snapshot.");
    } else if (mapping.sourceStoreId !== "unscoped-handset") fail("A handset must use sourceStoreId=unscoped-handset; login identity cannot establish database ownership.");
    const legacy = Object.fromEntries(LEDGER.map(table => [table, selectedRows(db, inventory.sourceKind, table, mapping.sourceStoreId)]));
    const idMap = [];
    function base(row, kind) {
      const legacyId = integer(row.id, `${kind}.id`, 1n);
      const id = entityId(mapping, kind, legacyId);
      idMap.push({ kind, legacyId, id, sourceRowSha256: hash(canonical(row)) });
      return { id, legacyId, createdAt: integer(row.created_at, `${kind}.created_at`, 1n, BigInt(Number.MAX_SAFE_INTEGER)), createdBy: text(row.created_by ?? "", `${kind}.created_by`, 500) };
    }
    const contacts = legacy.contacts.map(row => ({ ...base(row, "contact"), name: text(row.name, "contact.name", 120, true), phone: text(row.phone ?? "", "contact.phone", 40), category: text(row.category ?? "", "contact.category", 40), note: text(row.note ?? "", "contact.note", 500), whatsappOptIn: bool(row.whatsapp_opt_in ?? 0n, "contact.whatsapp_opt_in"), creditLimitCents: integer(row.credit_limit_cents ?? 0n, "contact.credit_limit_cents", 0n, MAX_CENTS), archivedAt: null }));
    const debts = legacy.debts.map(row => ({ ...base(row, "debt"), contactId: entityId(mapping, "contact", integer(row.contact_id, "debt.contact_id", 1n)), direction: text(row.direction, "debt.direction", 20, true), amountCents: integer(row.amount_cents, "debt.amount_cents", 1n, MAX_CENTS), note: text(row.note ?? "", "debt.note", 500), dueDate: text(row.due_date ?? "", "debt.due_date", 20) }));
    const payments = legacy.payments.map(row => {
      if (!["cash", "bank", "wallet"].includes(row.method)) fail(`Payment ${row.id} has an unknown method. No substitution was made.`);
      return { ...base(row, "payment"), debtId: entityId(mapping, "debt", integer(row.debt_id, "payment.debt_id", 1n)), amountCents: integer(row.amount_cents, "payment.amount_cents", 1n, MAX_CENTS), method: row.method, note: text(row.note ?? "", "payment.note", 500), serverReceivedAt: integer(row.server_received_at ?? 0n, "payment.server_received_at"), reversedAt: null };
    });
    const legacyHistory = Object.fromEntries(HISTORY.map(table => [table, inventory.sourceKind === "server" && inventory.tables.includes(table) ? selectedRows(db, "server", table, mapping.sourceStoreId) : []]));
    const reconciliation = reconcileRows(contacts, debts, payments);
    const review = ["Authenticated, transactional v3 historical-import executor must be implemented/reviewed before remote loading.", "Target ownership, empty target/overlap registry and source write freeze must be verified remotely at cutover.", "Old authentication credentials, account configuration and WhatsApp encryption keys require a separate secure migration decision; sessions are never replayed."];
    if (legacyHistory.contact_archives.length || legacyHistory.store_backups.length) review.push("Archive/backup evidence is retained verbatim but is not counted as new live money. Resolve archived-only customers, repeated/restored archive snapshots and historical edits before activating the store.");
    if (inventory.sourceKind === "handset") review.push("Unscoped handset ownership and overlap with the server/other devices require row-level review. Matching amount/date/name alone never proves two payments identical.");
    if (BigInt(reconciliation.totals.creditCents) > 0n) review.push("Legacy overpayments are preserved as credit; investigate any difference from old UI totals before cutover.");
    bundle = { format: "sadad-v3-import-preparation", schemaVersion: VERSION, preparedAt: new Date().toISOString(), readyForImport: false, source: { kind: inventory.sourceKind, lineageId: mapping.sourceLineageId.toLowerCase(), sourceStoreId: mapping.sourceStoreId, snapshotSha256, sourceStore, counts: inventory.counts }, target: { projectRef: mapping.targetProjectRef, storeId: mapping.targetStoreId }, ownership: mapping, currency: "ILS", ledger: { contacts, debts, payments }, idMap, legacyHistory, reconciliation, requiredReview: review };
    db.exec("COMMIT;");
  } finally { db.close(); }
  if (await fileHash(source) !== snapshotSha256) fail("Snapshot changed during preparation; no bundle was emitted.");
  const output = newDirectory(required(options, "out"));
  const contentSha256 = hash(canonical(bundle));
  writeJson(path.join(output, "bundle.json"), { contentSha256, bundle });
  writeJson(path.join(output, "reconciliation.json"), { contentSha256, ...bundle.reconciliation, requiredReview: bundle.requiredReview });
  console.log(`Prepared immutable local bundle ${contentSha256}. Counts: ${JSON.stringify(bundle.reconciliation.counts)}`);
  console.log(`Saved to ${output}. Remote import remains gated by the recorded review items.`);
}
async function main() {
  const [command, ...args] = process.argv.slice(2);
  if (command === "capture") return capture(parseOptions(args, ["source", "out"]));
  if (command === "prepare") return prepare(parseOptions(args, ["snapshot", "mapping", "out"]));
  console.log("Local-only migration preparation (Node 24+):\n  node prepare-v3-import.mjs capture --source <legacy.sqlite> --out <new-evidence-directory>\n  node prepare-v3-import.mjs prepare --snapshot <evidence.sqlite> --mapping <ownership.json> --out <new-bundle-directory>\nSee docs/MIGRATION_AND_RECOVERY.md. No upload command exists.");
  if (command && command !== "--help") process.exitCode = 1;
}
main().catch(error => { console.error(`Preparation stopped: ${error.message}`); process.exitCode = 1; });
