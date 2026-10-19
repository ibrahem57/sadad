# AGENTS.md — قواعد العمل في مستودع سدد

اقرأ هذا قبل أي تعديل. الهدف: مصدر واحد لكل مكوّن، ومنع إنشاء نسخة ثانية منه.
في بداية كل جلسة، اقرأ `docs/CONTINUE_HERE.md` وافحص `git status` والفرع.
المعمارية المستهدفة وخطة القطع النظيف في `docs/ARCHITECTURE.md`، وسجل الإصدارات في `docs/CHANGELOG.md`.

## المصادر المعتمدة

| المكوّن | المسار المعتمد | القاعدة |
| --- | --- | --- |
| تطبيق Android | `app/` (Kotlin + Compose، المرحلة 3) | عدّل المشروع المعتمد؛ لا تنشئ مشروعًا ثانيًا |
| الخادم | `Supabase-Setup/supabase/` (مشروع `vhftjmiltqfwmrszdtgf`) | لا تنشئ خادمًا موازيًا؛ النشر والترحيل باعتماد بشري |
| لوحة الإدارة | `server/public/` (Vite + TypeScript) تُبنى بـ`npm run build` إلى `_site/` | مصدر واحد للوحة |
| خطة المعمارية | `docs/ARCHITECTURE.md` | المرجع لما يحلّ محلّ كل مكوّن في المراحل 1–4 |
| سجل الإصدارات | `docs/CHANGELOG.md` | أضف سطرًا لكل إصدار أو مرحلة؛ لا تنشئ ملفات إصدار منفصلة |
| حالة العمل | `docs/CONTINUE_HERE.md` | أقل من 80 سطرًا للحالة الحالية فقط |

**إن احتجت تغيير سلوك مكوّن، عدّل المكوّن المعتمد. لا تُنشئ نسخة ثانية منه.**

## خريطة الكود (الشجرة الحالية)

انظر `docs/ARCHITECTURE.md` (الجزء 3) لمعرفة ما يستبدل كل مكوّن في المرحلتين 2 و3.

| الميزة | الملف | نقطة البداية |
| --- | --- | --- |
| تطبيق Android (Kotlin، المرحلة 3) | `app/src/main/kotlin/com/sadad/app/` | `MainActivity`، `SadadApplication` |
| قلب الدفتر بـKotlin (المرحلة 3) | `ledger-core/` (`com.sadad.ledger`) | `Commands`، `ProofText`، `Cents`، `LedgerState`، `PaymentAllocation` |
| وظيفة Edge الوحيدة `api` (Hono + zod) | `functions/api/app.ts` | `createApp()`، المسارات تحت `/v1` |
| مسارات الهاتف: التسجيل والترخيص والعمليات الموقّعة | `functions/api/mobile.ts` + `proof.ts` | `registerMobileRoutes()`، `verifyProof()` |
| مسارات الإدارة والحساب | `functions/api/admin.ts` + `admin/` + `account.ts` | `registerAdminRoutes()`، `registerAccountRoutes()` |
| العقد (OpenAPI ورموز الأخطاء) | `contract/` | `openapi.json` يولَّد بـ`functions/api/generate_openapi.ts` |
| لوحة الإدارة | `server/public/src/main.ts` | `npm run build` داخل `server/public/` |
| المخطط | `Supabase-Setup/supabase/schemas/*.sql` | الأساس `migrations/*_baseline.sql` = ملفات `schemas/` مدمجة بترتيب الاسم |

## البناء والفحص

```sh
# JDK 21 و Android SDK 37 (sdk.dir في local.properties المستبعد من Git)
./tools/check.sh
```

- `./tools/check.sh` هو البوابة الموحدة محليًا وفي CI، ويشغّل CI جميع أقسامه.
- النشر يدوي عبر `.github/workflows/supabase-deploy.yml` (`workflow_dispatch` من `main`): يشغّل `./tools/check.sh`، ثم ينتظر موافقة مراجعي بيئة `production` قبل `supabase db push` و`supabase functions deploy api`.
- إصدار الإنتاج يتطلب `SADAD_KEYSTORE_FILE` و`SADAD_KEYSTORE_PASSWORD` و`SADAD_KEY_ALIAS` و`SADAD_KEY_PASSWORD` مع `assembleRelease`؛ عنوان Supabase يحدد وقت البناء (`-PSADAD_SUPABASE_URL=...`).

## المفاتيح والبيانات الحساسة

- مفتاح `sb_publishable_...` وحده مسموح في التطبيق أو اللوحة أو Gradle.
- مفاتيح `sb_secret_...` و`service_role` وكلمات المرور وقواعد بيانات المستخدمين ممنوعة في المستودع العام (`Abdalrahman-py/sadad`)؛ تقرأ وظائف Edge الأسرار من بيئة المشروع فقط.

## الاختبارات

- كل الفحوص تعمل عبر `./tools/check.sh` (أو `./tools/check.sh <section>`، أو `./tools/check.sh --changed <base>` للأقسام التي تمسّها التغييرات منذ `<base>` فقط). يأخذ الفحص قفلًا، فينتظر أي تشغيل ثانٍ انتهاء الأول. `./tools/check.sh --changed <base> --list` يطبع الأقسام فقط ولا يشغّل شيئًا، فلا يحتاج إذنًا.
- **لا تشغّل `./tools/check.sh` (كاملًا أو أي قسم منه) قبل أن تسأل الإنسان ويوافق صراحةً، في كل مرة**، حتى لو طلبته خطوات التذكرة أو «Done when». الفحص ثقيل وأوقف الحاسوب أكثر من مرة. اسأل وانتظر؛ إن رفض فاذكر في تقريرك أن الفحص لم يُشغَّل. اختبار ملف واحد (`deno test <file>`، `./gradlew … --tests …`) لا يحتاج إذنًا.
- معرّف نسخة التطوير `com.sadad.app.debug` منفصل عن الإصدار `com.sadad.app`؛ اختبارات الجهاز الجديدة تضاف في تذاكر المرحلة 3.

## الكتابة والتوثيق

- العربية أولًا في ملفات المستخدم والتشغيل، والإنجليزية مقبولة في التوثيق التقني.
- وثّق الحقائق المتحققة فقط؛ كل ما لم يُتحقق منه يُوسم صراحةً بعبارة «غير محقّق».

## الحفظ والاسترجاع

- الوسم `pre-kotlin-app-2026-10` يحفظ تطبيق Java واختباراته قبل بدء هيكل Kotlin؛ تستعاد الملفات للقراءة عبر `git show pre-kotlin-app-2026-10:app/src/main/java/com/sadad/app/<Class>.java`.

- الأوسمة `pre-consolidation-2026-10-11` (الشجرة قبل التنظيف شاملة `archive/Sadad-Flutter`)، و`pre-supabase-ledger-correctness-2026-10-12` (نقطة بداية تدقيق الدفتر)، و`pre-prune-2026-10` (الشجرة قبل حذف `delivery/` و`docs/archive/` و`docs/plans/` التي حلّ محلّها `docs/ARCHITECTURE.md`) تحفظ التاريخ في Git.
- حزمة فروع الأرشيف الخاصة خارج المستودع في `~/sadad-private/sadad-archives-2026-10-14.bundle` (تحقّق في 17 أكتوبر 2026 بالأمر `git bundle verify` وباسترجاع تجريبي في مستودع فارغ)؛ تُسترجع عند الحاجة بالأمر:
  `git fetch ~/sadad-private/sadad-archives-2026-10-14.bundle 'refs/heads/archive/*:refs/heads/archive/*'`

## Agent skills

### Issue tracker

Issues and specs live as local markdown under `.scratch/` (gitignored, not pushed). See `docs/agents/issue-tracker.md`.
Without `.scratch/` (a Claude Code cloud session, or a teammate's clone) the ticket is a GitHub issue on `Abdalrahman-py/sadad`. See `docs/agents/cloud-sessions.md`.

A teammate on Codex takes a ticket with the `take-ticket` skill (`.agents/skills/`), which implements, reviews and merges its own PR.

### Triage labels

Default five-role vocabulary (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: root `GLOSSARY.md` + `docs/adr/`. See `docs/agents/domain.md`.

### Implementation skills

Use only: tdd, diagnosing-bugs, code-review, ponytail, supabase skills, writing-for-agents (doc tickets), graphify (queries). The ticket prompt replaces `implement`, which OpenCode cannot load (`disable-model-invocation`). Never use speckit-* or to-spec/to-tickets during implementation. In OpenCode, the repo `opencode.json` denies those skills and denies `git push`, destructive git and Supabase cloud commands; a human pushes and deploys.

### Code graph (graphify)

The `graphify` skill (`.claude/skills/graphify`, `.opencode/skills/graphify`, `.agents/skills/graphify` for Codex) maps the codebase into a knowledge graph in `graphify-out/`, gitignored and built per clone or worktree.

- Setup, once per developer: `uv tool install "graphifyy[sql]"` (or `pipx install "graphifyy[sql]"`; the `sql` extra parses `schemas/*.sql`). Then build the graph offline: `graphify extract . --code-only`.
- When `graphify-out/graph.json` exists, start a codebase question with `graphify query "<question>"`, `graphify path "<A>" "<B>"` or `graphify explain "<symbol>"`, then read only the files it names. After code edits, run `graphify update .`.
- Upgrade: `uv tool upgrade graphifyy`, then `./tools/graphify-skills.sh`, and commit the three skill directories. The script replaces `graphify install`, which also rewrites `CLAUDE.md`, `AGENTS.md` and hook settings.

## Review guidelines

Codex reads this section when it reviews a pull request (`@codex review`).

- Spec: the PR's `Closes #N` issue is the ticket. Check every "Test first" test and "Steps" item against the diff, and every decision under "Decisions already made".
- Scope: changed paths stay inside the issue's "Files you may edit".
- Clean cut: flag any upgrade path, fallback, compatibility shim or legacy handling (`docs/ARCHITECTURE.md` Part 3). All data is test data.
- Flag secrets (`sb_secret_...`, `service_role`, passwords) and any `supabase db push`, `functions deploy` or other cloud change.
- Money, auth, crypto and sync code: flag rounding, missing tests for refusal paths, and anything logged that holds a passphrase or key.
