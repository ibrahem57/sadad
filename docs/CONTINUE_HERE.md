# متابعة الإصدار الثالث

اكتمل تنفيذ النسخة التجريبية الجديدة. ابدأ من [تقرير التنفيذ](../delivery/SADID_IMPLEMENTATION_REPORT_AR.md) و[دليل التشغيل](../delivery/SADID_RUNBOOK_AR.md). المصدر الحالي في Supabase-Setup/android-official/Sadad-AndroidStudio ووظائف الخادم في Supabase-Setup/supabase. بيانات الدخول خارج المستودع.

التوثيق التالي محفوظ كسجل سابق؛ حالة التسليم الحالية في التقرير أعلاه.

---

# Sadid continuation handoff — 10 October 2026

The published Android 2.5.10 source is at the repository root. The ongoing Supabase and Android v3 work from the local project is preserved in `Supabase-Setup/`. That work was still being tested in another chat when this snapshot was saved; review it before replacing the published root Android project. `Sadad-Flutter/` preserves the separate Flutter implementation for reference.

## Admin dashboard connection

Both `server/public/admin.html` and `Supabase-Setup/admin/Sadad-Admin-Server/public/admin.html` now point to:

`https://vhftjmiltqfwmrszdtgf.supabase.co/functions/v1/sadad-api`

The pages contain only the public publishable key. The JavaScript sends it as `apikey`, and sends the admin session as the bearer token after login. Financial data and private keys are not included in this repository.

Verified live during this work: health returned HTTP 200 and `storage: supabase-postgres`; setup status returned HTTP 200 and `bootstrapRequired: false`; unauthenticated admin store/overview requests returned HTTP 401; browser CORS preflight returned HTTP 204 with permitted headers. The local dashboard loaded its login screen without console errors. Successful admin login and authenticated data loading remain unverified because no admin session was supplied.

The hosted dashboard has NOT been updated or verified. Its URL and deployment access were unavailable. Pushing these changes to GitHub does not prove deployment. Publish the updated static assets to the actual existing host, then verify login, overview, store list and permitted account management through the browser. Do not guess the hosting URL or create replacement admin credentials.

## Backend and future implementation

Read `SADID_IMPLEMENTATION_HANDOFF.md` in this directory for the agreed backend, ledger, offline sync and recovery specification. It is the original plan with explicit gaps, not evidence of completed implementation. It records an earlier database snapshot; the live backend now reports an existing admin, so recheck live state before continuing. Additional v3 migrations, Android integration, scripts and `Supabase-Setup/docs/V3_CONTRACT_AR.md` have since been created by the implementation chat and are included here. Its latest progress reported an Android build and emulator testing in progress. Do not treat the earlier plan's list of missing features as a current live-state report.

The live Edge Function and local source can differ. Inspect deployed source before deploying the local function or applying pending migrations. Do not blindly apply the prepared v3 migration. Existing legacy sync routes still need the controlled cutover described in the comprehensive handoff.

## Local dashboard

From the repository root with Node installed:

```powershell
node tools/admin-preview.cjs
```

Open `http://localhost:8081`. This serves only static admin assets; the page calls Supabase. It does not initialize a local SQLite database. An existing SQLite server may already occupy that port; stop it or use `SADAD_ADMIN_PREVIEW_PORT` to choose another port.

Local credentials, databases, caches and generated builds were excluded. The existing GitHub v2.5.10 release remains the source of previously published APKs; this commit does not build or release a new APK.
