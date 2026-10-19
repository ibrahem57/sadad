---
name: take-ticket
description: Take one Sadad ticket (a GitHub issue on Abdalrahman-py/sadad) end to end — claim, implement test-first, open the PR, get it reviewed, merge it. Use when asked to take, start or do the next ticket, or ticket/issue N.
---

# Take a ticket

One ticket per run, from claim to merge. The issue body is the ticket. The rules in [`docs/agents/cloud-sessions.md`](../../../docs/agents/cloud-sessions.md) (claiming, stacking, "Rules that also apply locally") bind every step; read that file first.

Set once: `R=Abdalrahman-py/sadad`, and `BASE=main`, or `BASE=ticket-MM` when stacking on your own open PR for ticket MM. `NN` is the ticket number (issue title `NN: ...`), `N` is the issue number.

## 1. Pick and claim

- The issue the human named; otherwise the `ready-for-agent` issue with the lowest ticket number: `gh issue list -R $R --label ready-for-agent --json number,title`.
- Claim before any edit: `gh issue edit N -R $R --add-assignee @me --remove-label ready-for-agent --add-label claimed`.
- Re-read the issue. If someone else is also assigned, the winner is the current assignee with the earliest `assigned` event (`gh api repos/$R/issues/N/events`). If that is not you, remove only yourself (`gh issue edit N -R $R --remove-assignee @me`), leave the labels to the winner, and pick again.

Done when the issue is assigned only to you and labelled `claimed`.

## 2. Implement

- `git fetch origin && git switch -c ticket-NN origin/$BASE` (stacking rules: cloud-sessions.md).
- Read every file under the ticket's "Read first". Use the `tdd` skill for the ticket's "Test first", and `diagnosing-bugs` when a test fails for a reason you cannot see.
- Edit only paths under "Files you may edit". Check it before every commit:
  `gh issue view N -R $R --json body --jq .body > /tmp/ticket-NN.md && git diff --name-only origin/$BASE... | python3 tools/ticket_files.py scope /tmp/ticket-NN.md` prints nothing when the scope is clean.
- Run single test files (`deno test <file>`, `./gradlew … --tests …`). Ask the human before any `./tools/check.sh` run; CI runs all of it on the PR.

Done when every "Test first" test went red, then green (keep both outputs), every "Steps" item is done, and the scope check prints nothing.

## 3. Open the PR

`git push -u origin ticket-NN`, then `gh pr create -R $R --base $BASE --title "NN: <issue title>"` with a body holding:

- `Closes #N` (the merge into `main` closes the issue; the `Ticket scope` check reads it). Stacked: also "Stacked on #<pr>".
- The red and green outputs, the sizes or other proofs the ticket asks for.
- `./tools/check.sh`: "run by CI" (or the local result if the human allowed a run).
- Device checks (`app/src/androidTest`): "not run, needs the maintainer's phone".

## 4. Review

The reviewer is never the context that wrote the code.

- Comment `@codex review` on the PR: `gh pr comment <pr> -R $R --body "@codex review"`. Codex reviews on GitHub in a fresh context.
- If the issue says `**Risk:** high`, also run a second review in a fresh process:
  `codex exec "Use the code-review skill. Fixed point: origin/$BASE. Spec: /tmp/ticket-NN.md. Report findings; edit nothing."`
- Wait for the review (`gh pr view <pr> -R $R --comments`, and `gh api repos/$R/pulls/<pr>/comments` for line comments). No Codex review after 20 minutes: run the `codex exec` review instead.
- For each finding: fix it, or reply on the PR with the evidence that it is wrong. Push the fixes and comment `@codex review` once more.

Done when every finding is fixed or answered and the last review raised nothing new.

## 5. Merge

- Wait for the required checks: `gh pr checks <pr> -R $R --watch --required`. Both `ci-ok` and `scope` must pass.
- `ci-ok` red: read the failing job's log (`gh run view <run> -R $R --log-failed`), fix on the branch, push, wait again.
- `scope` red: a file outside "Files you may edit" changed. Remove that change. If the ticket cannot be done without it, stop: comment on the issue naming the file and why, and leave the PR open for the maintainer.
- Green: `gh pr merge <pr> -R $R --merge --delete-branch`. Merge stacked PRs bottom first: deleting the merged base branch moves the next PR onto `main`, where its checks run again before you merge it.

Done when the PR is merged and issue #N is closed. The `Unblock tickets` workflow then labels every issue whose blockers are all closed `ready-for-agent`.

## Report

Tell the human: the PR link, the merge commit, the review findings and what you did with each, the checks that were not run, and the issues now `ready-for-agent` (`gh issue list -R $R --label ready-for-agent`).

## Guardrails

Change `main` only through a merged PR, and merge only your own ticket PR. Deploys and every Supabase cloud command belong to the maintainer.
