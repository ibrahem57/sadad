#!/usr/bin/env node
// Fail closed for old runbooks: the snapshot writer cannot target the v3 ledger.
console.error(`The legacy SQLite-to-Supabase writer has been retired.
No network request or database write was performed.

Use scripts/prepare-v3-import.mjs capture and prepare to create a read-only,
ownership-mapped migration bundle. See docs/MIGRATION_AND_RECOVERY.md.
--resume and --dry-run no longer enable the former importer.`);
process.exitCode = 1;
