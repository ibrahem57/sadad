# Sadad server-authoritative migration — 2026-10-05

## Research and decisions before implementation

Reviewed the existing Android bridge, unscoped SQLite ledger, snapshot sync RPCs,
Edge authentication, admin screens and the live Supabase tables. The 11 existing
tables are empty. No production SQLite database is present in this workspace.

References used:
- https://developer.android.com/topic/architecture/data-layer/offline-first
- https://developer.android.com/jetpack/androidx/releases/room (Java annotation processing; Room 2.8.5)
- https://developer.android.com/jetpack/androidx/releases/work (WorkManager 2.11.2 supports minSdk 23)
- https://supabase.com/docs/guides/database/functions
- https://www.postgresql.org/docs/current/explicit-locking.html
- https://supabase.com/docs/guides/platform/backups

Postgres owns confirmed balances and validation. Edge authenticates the current
Sadad bearer session and calls a single database transaction. Android uses Room
only for an account/server-scoped confirmed cache and a durable operation journal.
Money in commands is integer minor units (ILS); entity and operation IDs are UUID
strings. Local pending amounts are previews, never confirmed server balances.

## Implementation sequence

1. Add versioned ledger tables, immutable operations/events, restrictive foreign
   keys, soft archives, transaction RPCs, commit-ordered per-store cursor and epoch.
2. Add Edge v3 operation/read endpoints; disable snapshot replacement, destructive
   restore and hard deletion for v3 stores. Connect the admin reads to v3 balances.
3. Add Room journal, native repository, WorkManager retries, scoped sessions and
   account isolation. Preserve the legacy sadad.db untouched for explicit import.
4. Connect existing Android screens to UUIDs and pending/synced/attention states;
   retain offline input after process death, expired sessions and logout.
5. Compile/type-check available targets and inspect security grants and migration
   results. Failure-scenario/device tests require a separate explicit test request.
6. Deploy the additive schema and Edge code. New empty stores use v3. Import an
   existing store only with its actual source data and explicit ownership mapping,
   reconciliation and a write freeze of the old backend. Do not invent records.

## Shared v3 contract

API prefix remains /functions/v1/sadad-api/api. Custom Sadad bearer authentication
and public apikey header remain; service-role credentials stay in Edge only.

POST mobile/v3/operations accepts ONE immutable command:
```
{schemaVersion:1, operationId:"uuid", type:"contact.create", entityId:"uuid",
 createdAt:1780000000000, expectedVersion:"1", dependsOn:["operation-uuid"],
 payload:{...}}
```
expectedVersion is mandatory on contact.update/archive/restore and debt.correct.
Supported commands:
- contact.create: name, phone, category, note, whatsappOptIn, creditLimitCents (default 0)
- contact.update: same editable fields, missing fields left unchanged
- contact.archive / contact.restore: reason
- debt.create: contactId, direction(receivable/payable), amountCents, note, dueDate
- debt.correct: amountCents, note, dueDate, reason; direction and customer immutable
- payment.create: debtId, amountCents, method(cash/bank/wallet), note
- payment.reverse: paymentId, reason; entityId is a new reversal UUID

Result: {ok:true,operationId,status:"committed",entityId,sequence:"N",epoch:"uuid"}.
The exact request JSON is the canonical fingerprint. Reusing an operationId with
different JSON returns 409 operation_id_reused. Successful duplicate deliveries
return the stored result. Business conflicts return 409 with code/message and
currentVersion where relevant; input remains in the client's attention journal.
Missing dependencies return 409 dependency_pending and remain retryable.
401 waits for login; 403/423 retain input and show attention; network/429/5xx retry.

GET mobile/v3/snapshot?after=N&epoch=UUID returns:
{ok:true,snapshot:{contacts,debts,payments,transactions,totals,cursor:"N",epoch,
 currency:"ILS",revision:N,account},cursor:"N",epoch,unchanged:false}.
If cursor/epoch match, snapshot:null and unchanged:true. No after means full read.
Snapshot arrays are COMPLETE (including archived customers and reversed payments).
JSON IDs and version are strings. Fields match existing UI camelCase; money has
both integer *Cents and display major-unit fields. contacts have archivedAt,
version, receivable/payable/net/credit; debts have version, amount/paid/remaining/
credit; payments have reversedAt. transactions preserve corrections/reversals.
totals include receivable/payable/net/credit (all customers, including archives).

This first transport uses a lightweight cursor check then an atomic complete
snapshot when changed. It avoids partial pages and lost cursor events. A permanent
change journal is also kept; paginated record deltas can replace full refreshes
later without changing write semantics. There is no Realtime dependency.

SQL RPC signatures for Edge:
- sadad_v3_apply(p_store_id bigint,p_session_hash text,p_command jsonb)
  validates store session/device/permissions inside the transaction, derives actor.
- sadad_v3_snapshot(p_store_id bigint,p_after bigint default null,p_epoch uuid default null)
  returns {snapshot,cursor,epoch,unchanged}; Edge supplies account metadata.

## Invariants and operational rules

- Store row/cursor lock serializes writes, dependency checks, receipt and event
  publication. Receipt/event/financial mutation either all commit or none do.
- Debt corrections append an adjustment event; payment reversals append a new
  reversal and retain the original payment. Genuine overpayments become visible
  unapplied credit. Archives retain all history and unpaid balances.
- Client persists command before reporting saved. ACK and cache progress are
  durable. Receipt sequence never advances a read cursor. Acknowledged commands
  stay pending for display until a canonical snapshot includes their sequence.
- Cache refresh never deletes queued/attention commands. Failed dependencies
  block only their chain. Retry payloads/IDs never change.
- Server grants are service-only. New RPCs have fixed search_path. Normal service
  access cannot delete ledger history. Current bearer tokens are not Supabase JWTs.
- Fresh login must not upload a legacy handset snapshot. A new empty v3 cache
  displays a pending server load, never pretends that missing history is zero.
- Backup/PITR, offsite export and restore drills must be configured before a real
  production rollout. Device loss before any remote copy cannot be made lossless.
- Rollback after v3 writes must preserve the v3 journal; never restore an old
  writable SQLite snapshot over new cloud data.

## Release checks still required on devices

Offline create customer -> debt -> payment; process death and restart; duplicate
delivery/lost ACK; two-device payments; conflict+independent chain; expired login;
archive/restore/reversal; account switch; OS force-stop; storage full; upgrade;
server rollback/recovery epoch; source import counts and every customer balance.
