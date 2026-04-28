# Project Instructions for AI Agents

This file provides instructions and context for AI coding agents working on this project.

<!-- BEGIN BEADS INTEGRATION v:1 profile:minimal hash:ca08a54f -->
## Beads Issue Tracker

This project uses **bd (beads)** for issue tracking. Run `bd prime` to see full workflow context and commands.

### Quick Reference

```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --claim  # Claim work
bd close <id>         # Complete work
```

### Rules

- Use `bd` for ALL task tracking — do NOT use TodoWrite, TaskCreate, or markdown TODO lists
- Run `bd prime` for detailed command reference and session close protocol
- Use `bd remember` for persistent knowledge — do NOT use MEMORY.md files

## Session Completion

**When ending a work session**, you MUST complete ALL steps below. Work is NOT complete until `git push` succeeds.

**MANDATORY WORKFLOW:**

1. **File issues for remaining work** - Create issues for anything that needs follow-up
2. **Run quality gates** (if code changed) - Tests, linters, builds
3. **Update issue status** - Close finished work, update in-progress items
4. **PUSH TO REMOTE** - This is MANDATORY:
   ```bash
   git pull --rebase
   bd dolt push
   git push
   git status  # MUST show "up to date with origin"
   ```
5. **Clean up** - Clear stashes, prune remote branches
6. **Verify** - All changes committed AND pushed
7. **Hand off** - Provide context for next session

**CRITICAL RULES:**
- Work is NOT complete until `git push` succeeds
- NEVER stop before pushing - that leaves work stranded locally
- NEVER say "ready to push when you are" - YOU must push
- If push fails, resolve and retry until it succeeds
<!-- END BEADS INTEGRATION -->


## Publish-on-completion policy — local-only commits (OVERRIDES "Session Completion" above)

> **NOTE TO FUTURE EDITORS / `bd onboard` regenerators:** This section
> intentionally overrides the bead-default "Session Completion" / "PUSH
> TO REMOTE is MANDATORY" workflow inside the `BEGIN BEADS INTEGRATION`
> block above. The default is wrong for this repository; if a future
> `bd onboard` (or other tooling) regenerates the bead block in place,
> THIS section is the final word and must be preserved. Do not delete
> it as part of "cleaning up duplication" with the bead defaults.

re-frame-debux is **not** owned by the agents working on it. It's a
fork of `philoskim/debux` maintained by `day8`, and changes here are
proposed via PRs that the human operator reviews and submits. Agents
working through `gascity` or any local automation:

- **DO NOT `git push`.** Commits are local-only.
- **DO NOT `git tag`.** Releases are the operator's call.
- **DO NOT `bd dolt push`.** The bead store is local-only too.
- **DO NOT open or push to upstream PRs.** The operator opens PRs to
  `day8/re-frame-debux` after reviewing the local commits.
- **DO write commits.** Each commit should be cleanly self-describing
  and reference the bead id it satisfies — so the operator can rebase,
  squash, or re-author trivially when preparing a PR.

In short: complete the work, commit it, **stop**. The operator does
the rest.

This policy applies to the `re-frame-debux` rig only; the analogous
note in re-frame-pair's `CLAUDE.md` covers that repo independently.


## Build & Test

_Add your build and test commands here_

```bash
# Example:
# npm install
# npm test
```

## Architecture Overview

_Add a brief overview of your project architecture_

## Conventions & Patterns

### For agent contributors

(Local-only commits are already covered in detail in the
"Publish-on-completion policy" section above — this block is the
shorter day-to-day reminder list.)

- **Local-only commits.** Do NOT `git push`, `git tag`, `bd dolt push`,
  or open upstream PRs. Each commit references the bead id.
- **No bead-ID prefixes in code comments.** Don't write
  `;; rfd-XXX item N — ...` or similar. Comments should explain the
  WHY only — the commit message carries the bead-id linkage.
- **Verify APIs and paths before relying on them.** Cross-repo
  references in bead descriptions can drift; `grep` first.
- **Tests before close.** `bb test` for the macroexpansion suite.
  `bb test-browser` is operator-pending (requires chromium binary).
- **`bb` is on PATH; `lein` is NOT installed** in agent envs — flag
  any lein-only validation as operator-owned.
- **Cross-rig coordination via mayor.** If a bead's work touches
  multiple rigs, refuse the bead (release the claim) and message
  mayor with the split needed.
