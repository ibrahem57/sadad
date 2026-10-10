// Shared local-only migration primitives. No network or source writes.
import { createHash } from "node:crypto";
import { createReadStream, existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import path from "node:path";

export const MAX_CENTS = 9_000_000_000_000n;
export const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
export function fail(message) { throw new Error(message); }
export function canonical(value) {
  if (typeof value === "bigint") return JSON.stringify(value.toString());
  if (Array.isArray(value)) return `[${value.map(canonical).join(",")}]`;
  if (value && typeof value === "object") return `{${Object.keys(value).sort().map(key => `${JSON.stringify(key)}:${canonical(value[key])}`).join(",")}}`;
  return JSON.stringify(value);
}
export function hash(value) { return createHash("sha256").update(value).digest("hex"); }
export async function fileHash(file) {
  const digest = createHash("sha256");
  for await (const chunk of createReadStream(file)) digest.update(chunk);
  return digest.digest("hex");
}
export function json(file) { return JSON.parse(readFileSync(file, "utf8").replace(/^\uFEFF/, "")); }
export function writeJson(file, value) { writeFileSync(file, `${JSON.stringify(value, (_, v) => typeof v === "bigint" ? v.toString() : v, 2)}\n`, { flag: "wx", mode: 0o600 }); }
export function newDirectory(file) {
  const output = path.resolve(file);
  if (existsSync(output)) fail(`Output already exists; evidence is never overwritten: ${output}`);
  // Reserve the final directory atomically; its parent must already exist.
  mkdirSync(output, { mode: 0o700 });
  return output;
}
export function integer(value, label, min = 0n, max = 9_223_372_036_854_775_807n) {
  if (typeof value === "number" && !Number.isSafeInteger(value)) fail(`${label} is not an exact integer.`);
  if (!/^-?\d+$/.test(String(value))) fail(`${label} is not an integer.`);
  const result = BigInt(value);
  if (result < min || result > max) fail(`${label} is outside the supported range.`);
  return result.toString();
}
export function text(value, label, max, required = false) {
  if (typeof value !== "string") fail(`${label} must be text.`);
  if ((required && !value.trim()) || value.length > max) fail(`${label} requires review; empty or exceeds ${max} characters. No truncation was performed.`);
  return value;
}
export function bool(value, label) {
  if (value === 0n || value === 0 || value === false) return false;
  if (value === 1n || value === 1 || value === true) return true;
  fail(`${label} is not a boolean.`);
}
// UUIDv8: permanent database lineage defines identity. Backup fingerprints are
// tracked independently so another capture cannot generate replacement IDs.
export function entityId(mapping, kind, id) {
  const digest = createHash("sha256").update(canonical(["sadad-v3-legacy", mapping.sourceLineageId.toLowerCase(), mapping.sourceStoreId, kind, id])).digest();
  digest[6] = (digest[6] & 0x0f) | 0x80;
  digest[8] = (digest[8] & 0x3f) | 0x80;
  const hex = digest.subarray(0, 16).toString("hex");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
export function uniqueById(records, kind) {
  const byId = new Map();
  for (const record of records) {
    if (!UUID.test(record.id || "")) fail(`${kind} has a non-UUID id.`);
    if (byId.has(record.id)) fail(`Duplicate ${kind} ID ${record.id}.`);
    byId.set(record.id, record);
  }
  return byId;
}
export function reconcileRows(contacts, debts, payments) {
  const byContact = uniqueById(contacts, "contact");
  const byDebt = uniqueById(debts, "debt");
  uniqueById(payments, "payment");
  const balances = new Map(contacts.map(c => [c.id, { contactId: c.id, debtCount: 0, paymentCount: 0, principalCents: 0n, paymentCents: 0n, receivableCents: 0n, payableCents: 0n, creditCents: 0n }]));
  const paid = new Map();
  for (const payment of payments) {
    const debt = byDebt.get(payment.debtId);
    if (!debt) fail(`Payment ${payment.id} has no corresponding debt.`);
    if (payment.reversedAt != null) fail("Initial-import reconciliation cannot include reversed payments.");
    const amount = BigInt(integer(payment.amountCents, "payment.amountCents", 1n, MAX_CENTS));
    paid.set(payment.debtId, (paid.get(payment.debtId) || 0n) + amount);
    const balance = balances.get(debt.contactId);
    if (!balance) fail(`Debt ${debt.id} has no corresponding contact.`);
    balance.paymentCount++;
    balance.paymentCents += amount;
  }
  for (const debt of debts) {
    if (!byContact.has(debt.contactId)) fail(`Debt ${debt.id} has no corresponding contact.`);
    if (!["receivable", "payable"].includes(debt.direction)) fail(`Debt ${debt.id} has an invalid direction.`);
    const balance = balances.get(debt.contactId);
    const amount = BigInt(integer(debt.amountCents, "debt.amountCents", 1n, MAX_CENTS));
    const payment = paid.get(debt.id) || 0n;
    balance.debtCount++;
    balance.principalCents += amount;
    balance[`${debt.direction}Cents`] += amount > payment ? amount - payment : 0n;
    balance.creditCents += payment > amount ? payment - amount : 0n;
  }
  const perContact = [...balances.values()].sort((a, b) => a.contactId.localeCompare(b.contactId)).map(row => ({ ...row, netCents: row.receivableCents - row.payableCents }));
  const totals = { principalCents: 0n, paymentCents: 0n, receivableCents: 0n, payableCents: 0n, creditCents: 0n, netCents: 0n };
  for (const balance of perContact) for (const key of Object.keys(totals)) totals[key] += balance[key];
  return JSON.parse(canonical({ counts: { contacts: contacts.length, debts: debts.length, payments: payments.length }, totals, perContact }));
}
export function required(options, name) { if (!options[name]) fail(`Missing --${name}.`); return options[name]; }
export function parseOptions(args, accepted) {
  const options = {};
  for (let i = 0; i < args.length; i += 2) {
    const key = args[i]?.replace(/^--/, "");
    if (!args[i]?.startsWith("--") || !args[i + 1] || args[i + 1].startsWith("--") || options[key] || !accepted.includes(key)) fail("Arguments must be known, unique --name value pairs.");
    options[key] = args[i + 1];
  }
  return options;
}
