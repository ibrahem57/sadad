# Sadad Supabase backend migration

Project `vhftjmiltqfwmrszdtgf` has the private PostgreSQL schema from `migrations/202610040001_initial_sadad.sql` plus the additive migrations for atomic snapshot commits, admin queries, restore guards, device/session registration, and SQLite identity sequences. Business tables keep RLS enabled, with access reserved for the Edge Function's service-role client.

## Edge Function

`functions/sadad-api/index.ts` is deployed and active at `https://vhftjmiltqfwmrszdtgf.supabase.co/functions/v1/sadad-api`. It implements Sadad's own expiring bearer sessions, login and device checks, the admin API, the mobile snapshot contract, and atomic offline sync. Android continues to keep its local SQLite database and sends the same revisioned full snapshot; a network failure therefore leaves the local ledger available for later sync.

The Supabase service-role key is read only by the Edge Function from `SUPABASE_SERVICE_ROLE_KEY`. Never put it in Android, the admin page, Gradle properties, or a checked-in `.env` file. The Edge gateway's Supabase JWT check is disabled for this function because requests authenticate with Sadad sessions; the handler still validates a session on every private route.

The function is deployed. `INITIAL_ADMIN_KEY` is now configured for first-admin setup; after creating that admin, remove this one-time secret from the project. Add the other project secrets before enabling the related operations:

- `INITIAL_ADMIN_KEY`: one-time key required to bootstrap the first admin at `/api/setup/admin`.
- `TOKEN_ENCRYPTION_KEY`: random 32-byte key, hex or base64, used to encrypt WhatsApp access tokens.
- `META_GRAPH_VERSION`: supported Meta Graph API version for WhatsApp report sending.
- `PASSWORD_SCRYPT_N`: optional scrypt cost. Leave unset to use 32768; set 8192 only to match an existing deployment configured with that cost.

Supabase supplies `SUPABASE_URL` and `SUPABASE_SERVICE_ROLE_KEY` to Edge Functions. Store any additional values as project secrets, never in source.

## Android connection settings

The existing local API default is retained while the Edge API is being brought to route parity. A build can target the new endpoint with:

```powershell
.\gradlew.bat assembleRelease `
  -PSADAD_API_BASE_URL=https://vhftjmiltqfwmrszdtgf.supabase.co/functions/v1/sadad-api/api `
  -PSADAD_SUPABASE_PUBLISHABLE_KEY=sb_publishable_dK7HuYY8TbO8ZiBPbOZ2cw_1ZRL_chh
```

The publishable key is safe for client use; it only passes the Supabase gateway. The private service-role key remains in the function. Do not switch production builds until the SQLite data import is complete.

The static admin page in `admin/Sadad-Admin-Server/public/admin.html` is configured to use the deployed Supabase API with these two meta tags. Publish the updated static page to apply this configuration to a hosted dashboard:

```html
<meta name="sadad-api-base-url" content="https://vhftjmiltqfwmrszdtgf.supabase.co/functions/v1/sadad-api">
<meta name="sadad-publishable-key" content="sb_publishable_dK7HuYY8TbO8ZiBPbOZ2cw_1ZRL_chh">
```

The page appends its existing `/api/...` routes. The publishable key can be public; the service-role key must never be placed in this page.

## One-time SQLite data import

`../scripts/import-sqlite-to-supabase.mjs` reads a SQLite file in read-only mode, preserves row IDs and password hashes, converts JSON and boolean columns, and upserts records in foreign-key order. It refuses a non-empty Supabase target unless `--resume` is supplied for an interrupted import. Run the import before creating the first Supabase admin or enabling app traffic. The script does not delete or modify the SQLite source.

On a trusted server administration machine with Node.js 24 or newer, set `SADAD_SQLITE_PATH`, `SUPABASE_URL`, and `SUPABASE_SERVICE_ROLE_KEY` in the process environment, then run:

```powershell
node .\Supabase-Setup\scripts\import-sqlite-to-supabase.mjs --dry-run
node .\Supabase-Setup\scripts\import-sqlite-to-supabase.mjs
```

Keep the service-role key in that trusted server-side environment. Never add it to the Android build, static admin page, or source control. Use `--resume` only to continue an interrupted import before app traffic has been enabled.

## Migration status

The server-side sync commit RPC, admin aggregates, device/session transaction, restore operations, login throttling, sequence helper, and RLS-helper permission restriction are installed on project `vhftjmiltqfwmrszdtgf`. Edge Function `sadad-api` is active, and `INITIAL_ADMIN_KEY` is configured for the first admin. Android and the static admin page remain on the current API while the migration is staged. The SQLite server data has not been imported; no `.sqlite`/`.db` file was present in the source tree or delivery archives. Import the actual server database before switching existing users to the new API.
