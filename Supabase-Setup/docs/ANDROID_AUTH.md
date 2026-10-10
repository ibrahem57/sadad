# Sadid Android Supabase Auth — 2026-10-10

The native Java Android app now targets the deployed `sadad-auth` gateway:

`https://vhftjmiltqfwmrszdtgf.supabase.co/functions/v1/sadad-auth/api`

Store-number/password sign-in is preserved. The gateway verifies credentials and device limits through the existing deployed `sadad-api`, then creates an opaque Supabase Auth identity and issues an Auth session using the server-side generateLink/verifyOtp flow. It does not send an email. Store credentials are still managed by Sadid; this is a Supabase session integration, not a replacement of the existing password database.

Every private gateway request requires a verified Supabase access token and a device credential bound to that user's exact Auth session. The gateway rechecks the store session and forwards existing device, suspension and permission checks. Temporary passwords permit session status, password change and logout only. Refresh also checks device revocation. Logout deletes the binding and revokes the local Supabase session.

The new `sadad_auth_bindings` table has RLS enabled, no client policies, and no grants to public, anon or authenticated. Only service_role accesses it. Security Advisor's “RLS Enabled No Policy” information is expected for this private table; see https://supabase.com/docs/guides/database/database-linter?lint=0008_rls_enabled_no_policy.

Android encrypts access, refresh and device credentials using Android Keystore AES-GCM. Credentials never enter the WebView. Expired access tokens refresh before API calls; a rejected session redirects to login without deleting local ledger data. Navigation, direct asset links, restored pages and native ledger methods share authentication/lock checks. The API URL is fixed by the build to prevent sending credentials to an arbitrary URL from the sign-in page. A stable device UUID and optional staff-name field support the existing device rules.

Existing legacy sessions require one new sign-in. A local ledger associated with another store, or with unknown ownership, is blocked from automatic import/upload during login; migrate its data explicitly first. This change does not migrate existing SQLite ledgers.

## Verification

- Deno type check passed. Supabase JS is pinned to 2.117.3 with a lockfile.
- Ten gateway tests passed: missing/invalid credentials, session binding, expiry, revocation, refresh, login, logout and route scope.
- Thirteen live HTTP checks passed, including first-user sign-in, existing-user sign-in, protected snapshot, refresh, logout, rejection after logout, invalid JWT, wrong device token, temporary-password restriction, and device revocation.
- Temporary store/Auth users, device sessions and bindings were removed. Final counts were zero, matching the pre-test state.
- Android `assembleDebug` passed with Java from Android Studio. The Windows Arabic directory requires `-Pandroid.overridePathCheck=true`.
- No emulator/physical-device UI run was performed. The APK is a debug build, not a production-signed release.

## Operation

Provision a real store using Sadid's existing admin workflow. The project currently has no real admin or store accounts. Its initial-admin setup and actual store credentials are still required for real use. No production credentials were created by this task.

The gateway is deployed as `sadad-auth` version 2. The original deployed `sadad-api` was not replaced. The local migration is `20261010083726_sadad_android_auth_bindings.sql`.

Relevant Supabase references:
- https://supabase.com/docs/reference/javascript/auth-admin-generatelink
- https://supabase.com/docs/reference/javascript/auth-verifyotp
- https://supabase.com/docs/reference/javascript/auth-admin-signout
