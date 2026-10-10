# Team admin dashboard

Use one dashboard at `http://localhost:8081/`. Store accounts, financial balances, phone approval, recovery permits, and admin activity are integrated in this screen. Old `/v3-admin` links redirect to it.

The shared test username is `admin_sadid`, provisioned in the live `sadid` Supabase project (`vhftjmiltqfwmrszdtgf`). Get its password from the team lead. Starting the dashboard does not create or reset an admin account.

## Start on any team machine

Install Node.js 24 or newer and run from the repository root:

```sh
node tools/admin-preview.cjs
```

Alternatively run `npm start` inside `server/` or `Supabase-Setup/admin/Sadad-Admin-Server/`. Windows users can use `server/Start-Sadad.cmd`. No package installation, `.env`, SQLite database, local account setup, or Supabase CLI login is needed. Keep the process running and open `http://localhost:8081/`.

Each teammate starts their own preview; all authenticate against the same hosted database. Internet access to Supabase is required. To choose another port:

```sh
SADAD_ADMIN_PREVIEW_PORT=8082 node tools/admin-preview.cjs
```

PowerShell:

```powershell
$env:SADAD_ADMIN_PREVIEW_PORT = '8082'
node tools/admin-preview.cjs
```

## Create and activate a store

1. Create the account with a store name, unique username, subscription and temporary password of **12–128 characters**. Copy the displayed password and share it securely.
2. The owner signs in through the app and changes the temporary password.
3. The phone's enrollment request appears in the store's **اعتماد هواتف المتجر** section. Approve it with a reason.
4. The owner can synchronize the ledger once the account/subscription is active and the phone is approved.

The current backend allows **one approved ledger phone per store**. The login-device limit controls entry to the account; increasing it does not grant additional phones ledger synchronization. Approving a replacement phone revokes the preceding phone's approval. Revoked phones need a new installation registration before they can be approved again.

## Store administration

- Name, username, verification, active-contact limit, login-device limit, feature permissions, and WhatsApp availability save to live Supabase.
- Subscription duration, extension, pause, suspension and activation use hosted account rules.
- Password reset forces a change at next login and terminates both legacy and ledger session authorizations.
- Phone requests, approval, revocation, login-session termination and all-device revocation are available in the selected store.
- Recovery accepts the original command list from the replacement phone's recovery screen. Select the revoked old phone and approved replacement, enter a reason, approve the exact list and copy the resulting permit into the phone. Permits expire after 24 hours.
- Archiving disables the account and devices while preserving financial history. Enable **إظهار المؤرشفة** to inspect or restore it. Restoring leaves it suspended; activate it and approve a newly registered phone separately. Permanent deletion is available only when the account has no current ledger or installation history.

## Financial data and audit

The main screen reads current ledger tables through authenticated, audited server functions. The list and detailed balances use the latest debt correction and exclude reversed payments from paid totals. Original payments, corrections and reversals remain in the transaction report. Archived contacts remain visible with their historical balances.

Opening a store records the admin identity, reason, request ID and record counts. Financial summaries are also audited. Ledger activity, phone decisions, recovery permits and account actions appear within the store. CSV export includes the financial transactions.

Admins inspect the ledger and authorize recovery; financial corrections remain owner operations in the app's immutable ledger. The dashboard does not replace financial history with a legacy backup. Legacy restoration controls appear only for a store that has not initialized the current ledger.

## Backend deployment and checks

The `admin_dashboard_integration` migration and updated `sadad-api` function are deployed to the hosted project. The dashboard requires those backend changes. Migration source is in `Supabase-Setup/supabase/migrations/`; the edge function and its normalization tests are in `Supabase-Setup/supabase/functions/sadad-api/`.

For local database tests, install the pinned test runtime at the repository root:

```sh
npm install --prefix .local-tools --no-save @electric-sql/pglite@0.3.14
node Supabase-Setup/scripts/test-admin-dashboard.mjs
node Supabase-Setup/scripts/test-v3-ledger.mjs
```

For normalization/type checks, use Deno inside the `sadad-api` function directory:

```sh
deno test admin-ledger_test.ts
deno check --config deno.json index.ts
```

## GitHub Pages

No dashboard has been published on a fork. The workflow is prepared for the original `ibrahem57/sadad` owner to enable GitHub Pages with GitHub Actions and merge these changes. `node tools/build-admin-pages.cjs` builds `_site/` using an explicit dashboard asset list. The old `/v3-admin/` path redirects to the unified dashboard.

Pages serves static UI; login and administration still go directly to live Supabase. Only the existing public project key is in frontend assets. Admin passwords, service credentials, databases and other delivery files are excluded.

The preview binds to `127.0.0.1` and serves static assets only. The legacy Node/SQLite server is available explicitly via `npm run start:legacy` and does not control hosted authentication.
