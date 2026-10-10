# Migration, cutover and recovery

Prepared 2026-10-05. This runbook accompanies the server-authoritative v3 ledger.
The scripts here prepare and reconcile local evidence. They never upload a record
or modify the source ledger. A real production database has not been supplied.
An empty Supabase project is not proof that the existing app has no customer data.

## What is implemented

| Tool | Purpose | Write scope |
| --- | --- | --- |
| `scripts/prepare-v3-import.mjs capture` | Make a consistent SQLite backup, inventory tables and issue a mapping template | A new local evidence directory only |
| `scripts/prepare-v3-import.mjs prepare` | Validate ownership mapping, assign stable UUIDs, preserve history and calculate exact balances | A new local bundle directory only |
| `scripts/reconcile-v3-import.mjs` | Compare a prepared ledger with a saved authoritative v3 snapshot, including every customer balance | A new local report directory only |
| `scripts/import-sqlite-to-supabase.mjs` | Retired entry point that fails closed | None |

Use Node 24 or later with `node:sqlite`. No npm package installation or Supabase
secret is needed for these tools. Output directories must not already exist;
their parents must exist. Existing outputs are never replaced. A failed capture
may leave an incomplete directory; preserve it for diagnosis and use a new name.
The scripts have been syntax-checked, not exercised against a production source.

The capture contains **all source tables**, including authentication hashes and
encrypted WhatsApp tokens if present. Inventory and bundles contain private
customer information. Store all outputs outside the repository in an encrypted,
access-restricted directory; do not upload them to chat, Git or a public bucket.
POSIX mode flags in Node do not configure Windows ACLs; configure the destination
folder's Windows permissions separately. Keep a second encrypted offsite copy.
No credentials or records are printed by the scripts, only hashes and counts.

## Collect the actual sources before changing authority

1. Identify the current Node server's actual `sadad.sqlite`/configured data path,
   deployment location, app version and last successful synchronization time.
2. Identify every handset that might contain unuploaded input. Preserve its
   `sadad.db` together with WAL/SHM files using an authorized app export or a
   consistent backup. Do not uninstall, clear app storage or copy only the `.db`
   while a WAL database is live.
3. Establish each database's owner. The old handset schema has no store column;
   the most recent login does not prove which business owns those rows. A handset
   may have been used with more than one account. Mixed ownership requires a
   separately reviewed row-by-row partition; this tool intentionally refuses to
   infer it.
4. Assign one permanent `sourceLineageId` UUID to each original database lineage.
   Copies and later captures of that same database retain that UUID. Different
   independent databases must not share it. Record this registry durably.
5. Record a planned per-store write freeze, who can enforce it, and how users with
   old app versions will be prevented from submitting further snapshot writes.
   Preparatory captures may be taken earlier; the final approved bundle must use
   the frozen capture taken after outstanding old-device input is reconciled.

No source is silently treated as empty. Missing production data is a migration
prerequisite, not a reason to manufacture sample customers, admins or sessions.

## Capture and inspect a source

Run from `Supabase-Setup` with real local paths:

```powershell
node .\scripts\prepare-v3-import.mjs capture --source 'D:\PrivateMigration\legacy.sqlite' --out 'D:\PrivateMigration\capture-001'
```

The source connection is read-only and extensions are disabled. Node's SQLite
backup API includes committed WAL pages in a consistent backup. Only the new
backup is converted to standalone journal mode. The tool performs integrity and
foreign-key checks, discovers the server/handset schema, then writes:

- `evidence.sqlite`: a complete consistent copy, including all original tables.
- `inventory.json`: source kind, table counts, source store identity metadata,
  capture time, schema details and SHA-256 of the exact backup.
- `mapping.example.json`: a template requiring human ownership evidence.

The backup API can obtain a consistent image while a source is active, but that
does not freeze subsequent business writes. A capture alone does not establish
that all devices have uploaded or that the old backend is no longer writable.

Keep the original source and sidecars until cutover and recovery acceptance are
complete. Do not modify either the source or captured evidence to fix bad rows.
Record corrections separately and create a new reviewed bundle when necessary.

## Make the explicit ownership mapping

Copy the template to a separate `ownership.json` and fill in:

- `sourceLineageId`: stable UUID from the source registry.
- `snapshotSha256`: the unchanged hash reported by capture.
- `sourceKind`: `server` or `handset`, confirmed by the tool.
- `sourceStoreId`: exact old store ID for a server; the literal
  `unscoped-handset` for a handset. One server bundle covers one store.
- `targetProjectRef`: `vhftjmiltqfwmrszdtgf` for this project, checked against the
  intended deployment.
- `targetStoreId`: the actual existing target account ID. Create/provision the
  account through the authenticated admin flow if necessary, then record it.
- `ownershipConfirmedBy` and `ownershipEvidence`: reviewer and durable reference
  to the verified business/device/source mapping.
- `writeFreezeReference` and `frozenAt`: real freeze record and timestamp.
- `handsetOverlapDecision`: supporting review record for handset/server overlap.
  This field documents the review; it does not automatically authorize merging.

Provision accounts and credentials independently from ledger preparation. Never
put bearer tokens, service-role keys, bootstrap keys or plaintext passwords in the
mapping. Never create a synthetic session to disguise a historical import as a
live staff action. Preserve the original actor string and separately attribute
the import to the authenticated operator.

## Prepare an immutable bundle

```powershell
node .\scripts\prepare-v3-import.mjs prepare --snapshot 'D:\PrivateMigration\capture-001\evidence.sqlite' --mapping 'D:\PrivateMigration\ownership.json' --out 'D:\PrivateMigration\bundle-001'
```

Preparation verifies the backup hash, source schema, source-store existence,
required mapping fields, integer money, reference links and supported values.
Invalid amounts, broken relationships, unknown payment methods or overlong text
stop preparation. No amount is rounded and no text is truncated.

`bundle.json` contains the source identity, exact snapshot fingerprint, mapped
ledger, legacy-to-UUID map, row fingerprints, original audit/archive/backup rows,
per-customer reconciliation and outstanding review requirements. Its canonical
content hash detects accidental changes. It is not a cryptographic signature;
keep the hash in a separate trusted cutover record. `reconciliation.json` provides
the balances and counts in a smaller review file.

Record UUIDs are deterministically derived from source lineage, original store,
entity kind and legacy ID using a SHA-256 based UUIDv8. They do **not** depend on
the changing snapshot hash. Recapturing the same record therefore retains its
identity. The exact backup and original row hashes still identify what content
was imported. A changed legacy row must be resolved as a new reviewed revision,
never silently overwritten by another run.

All money is integer cents represented as decimal strings in the bundle. The
reconciliation includes counts, principal, payments, remaining receivables and
payables, net and overpayment credit for every mapped customer. It calculates
with `BigInt`, preserving cents even when aggregate values exceed JavaScript's
safe number range. Legacy payments are never deduplicated by amount or time.

`readyForImport` remains `false`: this artifact is preparation, and the tool has
no uploader. The required historical import executor must be reviewed against the
actual source and new schema before remote loading.

## Resolve archives and overlaps explicitly

The current rows describe the last legacy state; the old application allowed
hard deletes and edits. That makes an invented complete historical sequence
unreliable. Import current rows as a clearly attributed legacy opening state,
preserve original timestamps/actors, and retain the full source evidence.

Old `audit_events`, `contact_archives` and `store_backups` are copied verbatim into
the bundle for review. They are not automatically replayed as new debts/payments.
An archive may contain a customer later restored under different numeric IDs;
several backups may describe the same receipt. Archived-only balances and restored
rows need explicit disposition and identity links before the business can become
active. Preserve the raw records even when they are not live financial entries.
If the legacy application already deleted/pruned history, record that gap; do not
claim the migration can reconstruct evidence that no longer exists.

For multiple devices, distinguish:

- A proven copy of a server record: link to its established UUID with evidence.
- A genuinely unuploaded record: import once with its own original identity.
- Ambiguous overlap: retain both evidence records and require review before
  adding money; similar names, amounts and timestamps are insufficient.

This preparation tool does not implement a cross-source merge or accept ad hoc
edits of its bundle to bypass review. A reviewed merge manifest and dedicated
transaction are needed when overlap exists.

## Required remote import transaction

The loading path must be restricted to a real authorized migration operator and
must not use the mobile command endpoint to impersonate historical actors. It
must, in one database transaction:

1. Lock the target store and require a frozen/migrating state with all old writes
   disabled. Verify the project, store, source fingerprint and ownership mapping.
2. Register the source lineage and immutable bundle hash. Enforce a unique mapping
   on `(source_lineage, source_store, entity_kind, legacy_id)` and a target binding
   for the source. An identical completed import returns its stored result.
   Reuse with different content or a different target fails.
3. Reject an occupied target ledger unless an explicitly reviewed import plan
   defines every existing/new identity and amount. `ON CONFLICT DO UPDATE` on
   financial rows is not an acceptable general resume mechanism.
4. Insert mapped customers, opening financial entries and preserved history with
   restrictive references. Record original time/actor and import time/operator
   separately. Preserve archives/tombstones and overpayment credit.
5. Record the import receipt, permanent audit entry, mapping registry and ordered
   change publication atomically. Keep login sessions and rate-limit buckets out
   of migration. Roll back every import mutation if any part fails.
6. Return counts, per-customer cents and snapshot epoch/cursor for reconciliation.

Large datasets may require a private staging area. Staging is not visible as a
live ledger; final activation remains atomic and imports never delete an outbox.
Do not resume by rerunning the retired legacy snapshot importer.

## Reconcile before activating the store

While both old and new writers remain frozen, obtain a complete canonical v3
snapshot through the authenticated read path. Save the response's `snapshot`
object inside a local acquisition envelope:

```json
{
  "projectRef": "vhftjmiltqfwmrszdtgf",
  "storeId": "THE_VERIFIED_TARGET_ID",
  "acquiredAt": "THE_ACTUAL_ISO_ACQUISITION_TIME",
  "snapshot": {}
}
```

The real snapshot must include `account.id`, currency, cursor, epoch and integer
cent fields. Do not provide a pending handset preview or an unchanged response.

```powershell
node .\scripts\reconcile-v3-import.mjs --bundle 'D:\PrivateMigration\bundle-001\bundle.json' --server-snapshot 'D:\PrivateMigration\target-snapshot.json' --out 'D:\PrivateMigration\reconcile-001'
```

The tool verifies the bundle checksum and target identity, matches every source
UUID, reports missing/extra rows, compares contact/debt/payment fields and checks
each customer balance, each debt's paid/remaining/credit and server totals. Exit
code `0` means the compared live ledger matched; `2` means differences; `1` means
the comparison could not be completed. The report never authorizes cutover.

The comparison intentionally expects an initial import with no later corrections
or reversals. It cannot validate a merged archive plan without a corresponding
reviewed expected dataset. Counts and totals alone never establish that historical
audit, authentication, backups, permissions or device behavior are correct.

## Controlled activation and rollback

Before the first real v3 financial write, complete and record:

- Source freeze and reconciliation of each participating handset's old input.
- Target ownership, archive/overlap review, exact cents and identity comparison.
- Backup policy, encrypted offsite copy and successful isolated restore drill.
- Device acceptance checks in the main implementation plan, including lost ACK,
  process death, expired sessions, account switch and concurrent payments.
- Old backend/snapshot endpoints blocked for this store, with an upgrade message.
- Support procedure for an old device that reconnects after cutover: preserve its
  input, collect evidence and perform an authorized reconciliation; never upload
  its entire ledger over the server.

Activate one store first and monitor rejected commands, queue age, conflicts,
snapshot failures and the server change cursor. Keep sensitive payloads out of
routine logs. Expand only after the store's acceptance record is complete.

Before any v3 writes, a failed rollout may leave the target frozen while the old
system is reopened under a recorded rollback. After any v3 write, rolling back
must preserve the v3 ledger and operation receipts. Fix forward or migrate those
new events through a reviewed process. Restoring an old SQLite snapshot as a
writable replacement would lose the new events and is prohibited.

## Backups and disaster recovery

Choose a documented recovery point objective (how much confirmed data loss is
acceptable) and recovery time objective before production. Evaluate the project's
actual Supabase backup/PITR availability and retention; no paid feature has been
enabled by this work. Use encrypted independent exports as an additional recovery
copy and protect the associated secrets, deployment version and migration files.
Record and monitor successful backup times, retention and restore procedures.

Supabase database backups do not include the actual objects stored through the
Storage API. If attachments are added, maintain a separate object backup and
restore plan. A backup also does not replace the application's permanent audit
and append-only financial history. See [Supabase database backup guidance](https://supabase.com/docs/guides/platform/backups).

After suspected database loss or rollback:

1. Freeze financial writes; preserve surviving database exports, operation
   receipts, logs and device journals before recovery changes.
2. Restore into an isolated environment and reconcile counts, every account's
   money, historical import registry and operation receipts. Verify which committed
   operations are newer than the restored point.
3. Give the recovered database a new recovery epoch using a controlled server
   operation so clients cannot mistake an old cursor for current data.
4. Require clients to refresh the confirmed cache for that epoch while retaining
   all local queued/attention/acknowledged operations. Do not blindly replay old
   acknowledged commands: a missing receipt after restore needs reconciliation
   against surviving evidence before money is reapplied.
5. Reconcile retained commands and external receipts, then reopen the store with
   a recorded recovery audit. If confirmed information cannot be reconstructed,
   disclose the specific gap and keep the affected account under review.

No design can recover offline input from a device that is lost or wiped before
any second copy exists. The app must say whether an item is stored locally or
confirmed on the server. Never clear an outbox to solve a connection problem.

## Research references

- [Node SQLite API](https://nodejs.org/api/sqlite.html): read-only connections,
  integer/BigInt conversion and the backup API.
- [SQLite online backup API](https://www.sqlite.org/backup.html): consistent
  database snapshots including data beyond the main file.
- [PostgreSQL transactions](https://www.postgresql.org/docs/current/tutorial-transactions.html):
  atomic visibility and all-or-nothing changes.
- [Supabase database backups](https://supabase.com/docs/guides/platform/backups):
  backup availability, restoration and Storage object limitations.
