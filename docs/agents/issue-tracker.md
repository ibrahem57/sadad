# Issue tracker: Local Markdown

Issues and specs for this repo live as markdown files in `.scratch/`.

## Conventions

- One feature per directory: `.scratch/<feature-slug>/`
- The spec is `.scratch/<feature-slug>/spec.md`
- Implementation issues are one file per ticket at `.scratch/<feature-slug>/issues/<NN>-<slug>.md`, numbered from `01`, never a single combined tickets file
- Triage state is recorded as a `Status:` line near the top of each issue file (see `triage-labels.md` for the role strings)
- Comments and conversation history append to the bottom of the file under a `## Comments` heading

## When a skill says "publish to the issue tracker"

Create a new file under `.scratch/<feature-slug>/` (creating the directory if needed).

## When a skill says "fetch the relevant ticket"

Read the file at the referenced path. The user will normally pass the path or the issue number directly.

## Implementation tickets

Tickets in `.scratch/<feature-slug>/issues/` that OpenCode implements, one git worktree per ticket.

- **Status flow**: `ready-for-agent` → `claimed` → `review-pending (commit <sha>, branch ticket-NN)` → `done (commit <sha>, merged to main <sha>, <date>)`, or `blocked: <reason>`. The agent commits on its branch at every risk level; only `ticket merge` (or `ticket close NN <sha>` for a merged PR) writes `done`.
- **Blocking**: a ticket is unblocked when every `Blocked by` ticket has a Status starting with `done`, so it always starts from a `main` that has its blockers. The human picks the ticket number; agents never pick the next ticket themselves.
- **Files section**: every repo path that Test first or Steps edits must appear under `Files you may edit`.
- **Runner**: `.scratch/ticket` (`ready`, `lint NN`, `start NN`, `merge NN`, `close NN`); run it without arguments for usage. Run `ticket lint NN` after writing or changing a ticket. `merge` refuses a branch that changes files outside the ticket's Files section. Merge reviewed branches with `ticket merge NN`, not plain `git merge`, so the Status, worktree and branch are handled too.
- **Merging a batch**: merge every approved ticket first, then run one `./tools/check.sh --changed <main before the batch>` on `main` (after the human's yes), push, and run `ticket close NN` for each. `close` closes the GitHub issue and flips dependents from `blocked` to `ready-for-agent`; the `Unblock tickets` workflow does the same flip on GitHub after any merge. `merge` and `.github/workflows/ticket-scope.yml` share the scope check in `tools/ticket_files.py`. A review needs no check run in the worktree when the branch merges into `main` as is; the one check on `main` covers it.

## Wayfinding operations

Used by `/wayfinder` for exploration efforts that have a `map.md`; the implementation-ticket rules above do not apply to them. The **map** is a file with one **child** file per ticket.

- **Map**: `.scratch/<effort>/map.md` (the Notes / Decisions-so-far / Fog body).
- **Child ticket**: `.scratch/<effort>/issues/NN-<slug>.md`, numbered from `01`, with the question in the body. A `Type:` line records the ticket type (`research`/`prototype`/`grilling`/`task`); a `Status:` line records `claimed`/`resolved`.
- **Blocking**: a `Blocked by: NN, NN` line near the top. A ticket is unblocked when every file it lists is `resolved`.
- **Frontier**: scan `.scratch/<effort>/issues/` for files that are open, unblocked, and unclaimed; first by number wins.
- **Claim**: set `Status: claimed` and save before any work.
- **Resolve**: append the answer under an `## Answer` heading, set `Status: resolved`, then append a context pointer (gist + link) to the map's Decisions-so-far in `map.md`.
