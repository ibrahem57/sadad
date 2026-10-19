# Working on tickets from GitHub issues (cloud sessions and teammates)

Cloud sessions (claude.ai/code) and teammates clone `Abdalrahman-py/sadad` from GitHub. They do not have `.scratch/`, the `.scratch/ticket` runner, OpenCode's ticket prompt, the maintainer's phone, or the maintainer's local memory. This page replaces those.

## One-time environment setup (human, on claude.ai/code)

- **Repository:** `Abdalrahman-py/sadad`, with `main` pushed. Install the Claude GitHub App on it for Auto-fix.
- **Network access:** Custom. Keep the Trusted defaults and add `dl.google.com` (Google Maven and the Android SDK).
- **Environment variables:** `ANDROID_HOME=/opt/android-sdk`.
- **Setup script:** the contents of `tools/cloud-setup.sh`. It installs Deno and the Android SDK; the environment cache keeps them.

## The ticket is a GitHub issue

- Each open ticket is an issue on `Abdalrahman-py/sadad`, labelled with its status (`ready-for-agent`, `ready-for-human`, `needs-triage`, `claimed`). The issue body is the ticket.
- Labels: `ready-for-agent` = every `Blocked by` ticket is merged into `main`; `blocked` = at least one is not; `claimed` = someone is on it; `ready-for-human` = the maintainer does it (cutover, phone runs).
- Start an issue labelled `ready-for-agent`. Claim it before any edit: assign yourself, remove `ready-for-agent`, add `claimed`.
- Stacking: you may also start a `blocked` issue whose only missing blockers are your own open PRs. Branch from that PR's branch, and name the base PR in your PR body ("Stacked on #<pr>"). Never stack on someone else's unmerged branch.
- Merging: a teammate on Codex merges their own ticket PR through the `take-ticket` skill (`.agents/skills/take-ticket/`), once its review is answered and the required checks `ci-ok` and `scope` pass. Otherwise the maintainer reviews and merges. Either way the `Unblock tickets` workflow then labels every issue whose `Blocked by` issues are all closed `ready-for-agent`.
- Work on a branch named `ticket-NN`, and keep to the ticket's "Files you may edit".
- Deliver a pull request into `main` that says `Closes #<issue>`. Put the ticket's proofs (red and green output, `./tools/check.sh` result, sizes) in the PR body.
- Where a ticket says to write in its Comments or Status, comment on the issue instead.
- Paths like `/home/jade/...`, `.scratch/...` and `ticket start/merge` in a ticket are local-only; ignore them. The Phase 3 planning notes that tickets cite (`decisions.md` Q1–Q11, `gap.md` §0–§8) are in `docs/phase-3/`.
- A teammate on their own clone uses `gh issue ...` and `gh pr ...` normally; the proxy limits below apply only to cloud sessions.

## One-time setup for a teammate on Codex

- Codex CLI; `gh` signed in with push access to `Abdalrahman-py/sadad`; JDK 21, the Android SDK and Deno for single-file tests.
- Codex code review on GitHub: in Codex settings, connect GitHub and turn on code review for `Abdalrahman-py/sadad`, so `@codex review` on a PR gets an answer.
- Then ask Codex in the clone to "take the next ticket".

## GitHub from a cloud session

GitHub goes through Anthropic's GitHub proxy, which uses the human's GitHub connection; no `gh auth login`. The proxy blocks GraphQL, so `gh issue ...` and `gh pr ...` fail with 403. Use the built-in GitHub tools, or `gh api` (REST) on this repository only:

```sh
R=repos/Abdalrahman-py/sadad
gh api $R/issues/7                                                # read the ticket (issue 7 = ticket 23)
gh api -X DELETE $R/issues/7/labels/ready-for-agent               # claim it
gh api -X POST $R/issues/7/labels -f 'labels[]=claimed'
gh api -X POST $R/issues/7/comments -f body='...'                 # ticket comment
gh api -X POST $R/pulls -f title='...' -f head=ticket-23 -f base=main -f body='Closes #7 ...'
```

## Skills

`.claude/skills/` holds `tdd`, `diagnosing-bugs` and `code-review` (from mattpocock/skills, MIT; see `LICENSE-mattpocock-skills`). Codex reads `.agents/skills/` instead, where those three are symlinks to `.claude/skills/` (on Windows, clone with `core.symlinks=true` and Developer Mode on, or use WSL), next to Codex's `graphify` variant and `take-ticket`. The human's other skills and plugins (ponytail, supabase, Context7) are not in the cloud. Library versions are fixed in `docs/versions.md`, so no docs lookup is needed to pick them.

## Rules that also apply locally

- `./tools/check.sh` is the gate before every PR. Ask the human before every run of it or any section (AGENTS.md, الاختبارات); if the answer is no, say in the PR that it was not run. `./tools/check.sh --changed main` runs only the sections your branch can affect.
- Never run `supabase db push`, `supabase functions deploy`, or anything that changes project `vhftjmiltqfwmrszdtgf`. The human deploys. Cloud migration versions differ from local file names, so `db push` would re-run old migrations.
- All stores, users and rows are test data. Never add upgrade paths, fallbacks, compatibility shims or legacy handling (`docs/ARCHITECTURE.md` Part 3, the clean-cut rule). Delete legacy handling you touch.
- Device checks (`app/src/androidTest`) need the human's phone; list them in the PR as "not run".
- Versions are decided in `docs/versions.md`; do not pick newer ones.
