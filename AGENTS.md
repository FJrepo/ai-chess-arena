# AGENTS.md

## Purpose
This file defines the default workflow for implementing features in this repository.

The goal is simple:
- feature work should be specified before coding
- user-visible behavior should be documented
- behavior changes should be tested
- deploy status and residual risks should be explicit

## Core Rules

### 1. Start with a feature spec
Before implementing a feature, make sure the task package or equivalent spec covers:
- context
- goal
- non-goals
- product decisions
- backend impact
- frontend impact
- data model or migration impact
- edge cases
- acceptance criteria
- validation plan
- README/docs impact
- deploy or backfill impact

If the feature spec is missing decisions that affect implementation, resolve them before coding.

### 2. Keep implementation explicit
- Prefer explicit data model changes over inference-heavy logic.
- Preserve backward compatibility unless the task explicitly allows breaking change.
- Update backend DTOs, API contracts, and frontend types together.
- If persistence changes, add a Flyway migration with the next free `V` number. Never edit a migration that is already on `main`; fix forward with a new one.
- Inputs fail closed: invalid query parameters or form values are rejected with a clear error (HTTP 400 / validation message), never silently replaced by a default.

### 3. Architecture invariants
These rules each come from a real bug. Keep them when touching the related code and cover them with tests.
- **Tournament writes:** every mutation of a tournament (settings, participants, bracket, run state, cancellation) takes the tournament row lock and re-reads fresh state before changing it.
- **Broadcasts after commit:** status, game-over and evaluation updates are sent only after the transaction commits, with data captured while entities are attached. WebSocket sends never carry the caller's transaction context.
- **Fresh state per turn:** the game loop re-reads game and tournament state every turn; no entity is reused across turns.
- **Fairness:** a provider problem (HTTP errors, safety cap, cache-marker fallback running out of time) pauses the game; it never counts as the model's timeout, invalid move or forfeit.
- **Durable runs:** every application-issued model POST, including the cache-control plain fallback, needs a fresh admission (game status, loop owner, run state, budget) and a committed request marker first; no request is sent while a marker is unresolved. The transaction recording a usable answer (move, resignation, or an attempt that is not delivery-uncertain) clears the marker; recording a delivery-uncertain attempt saves its reported usage but keeps the marker, which then clears only in the forfeit transaction or through an operator acknowledgement naming its exact `requestSentAt`. The owner token fences every loop write (a stale token writes nothing); lease expiry only makes the game eligible for recovery. Restart and lease recovery finalize already-decided games first, then resume clean work or hold it for a spend check.
- **Prompts:** existing prompt templates keep their exact texts, versions and hashes (pinned by tests). Within one history window and while no prompt-affecting setting changes, the prompt prefix sent for a side stays byte-stable across turns so provider caches can hit (a window re-anchor changes the prefix); only the newest user message carries per-turn content. Supported live changes to prompt-affecting settings (chat mode, banter level, resignation, custom instructions) rebuild the system prompt on purpose, from the next turn.
- **Analytics scope:** Tournament Analytics model metrics (comparison, reliability, move quality, talkativeness, conversion, leaderboard) count finished, non-bye games and model sides only, with one canonical move per ply (earliest row wins; legacy duplicates are allowed by V8); book moves and cancelled games never count toward them. Operational views keep their own scope: health counts unfinished games, and spend summaries include every recorded paid answer, also from unfinished games, within each report's own game scope. A charge that was never recorded (see durable runs) is missing from every total.

### 4. Documentation guardrails
- UI changes follow docs/design-system.md; new colours, fonts or spacing only as tokens.
- `README.md` is the front page: keep it under ~1,100 words.
- Keep Features to at most 10 one-line bullets, ordered by importance. A feature gets at most one short bullet (~20 words), describing the user benefit and linking to the matching `docs/how-it-works.md` section.
- Keep implementation details, edge cases, status codes, configuration internals and UI measurements out of README feature bullets. Minor features belong in the "…and more" line; never add paragraphs under Features.
- Put details, behavior rules, edge cases and API specifics in `docs/how-it-works.md`.
- Put operations, upgrades, configuration and troubleshooting in `docs/deployment.md`.
- Put what changed per release in `CHANGELOG.md`; update `THIRD_PARTY_NOTICES.md` when bundled/distributed third-party software changes.
- `CHANGELOG.md` format: `## [Unreleased]` has exactly one heading per section, in the order Upgrade notes, Added, Changed, Fixed (Security if needed). Add bullets to existing headings, never duplicate a heading, never edit released sections. Each new migration gets an upgrade note.
- Update the appropriate docs when behavior, limits, defaults, setup, runtime expectations or admin workflows change. A README edit is only needed when the front-page overview or quick start needs to change.
- Before finishing a task that touched `README.md`, check its word count and compliance with these guardrails. If no docs update is applicable, say so in the final summary.

### 5. Tests are part of feature work
When behavior changes, add or adjust tests.

Expected coverage:
- backend tests for service and resource behavior
- frontend tests for changed logic or interaction where practical
- regression tests for bugs fixed during implementation

If a test is intentionally deferred, call it out explicitly with the reason.

### 6. Validation is required before calling work done
Run the relevant checks for the files and behavior touched.

Default validation set:
- `cd backend && mvn -B verify checkstyle:check spotless:check` (unit tests plus `*IT` database tests via Dev Services; needs Docker)
- `cd frontend && rm -rf node_modules/.vitest-cache` first: the Vitest module cache can serve stale code
- `cd frontend && npm run lint`, `npm run typecheck`, `npm run format:check`, `npm test -- --watch=false`, `npm run build`
- `python3 -m unittest discover -s scripts -p 'test_*.py'` when scripts or workflows change

If a command is not applicable or cannot be run, state that explicitly.

### 7. Commit scope must stay clean
- Do not mix feature code with unrelated cleanup.
- Do not include `analysis/`, `task-packages/`, `CLAUDE.md` or local planning files in commits unless the user explicitly asks for it.
- Keep formatting-only follow-ups separate when practical.
- Do not rewrite pushed history or force-push; update branches with a merge of `main`. Never bypass hooks with `--no-verify`.
- Repository docs must use repo-relative paths or normal markdown links, never chat-style absolute local filesystem paths.
- This is a public repository: no secrets, API keys, local paths or personal data in code, commits, PR text or screenshots.

### 8. Boundaries for agents
- Do not start, stop, rebuild or call running Docker Compose projects or ports you did not create (e.g. 4200, 8081, other sessions' stacks). Testcontainers/Dev Services in tests and your own uniquely named project on a free port (torn down afterwards) are fine.
- Do not call OpenRouter with a real API key; tests use fakes or fixtures. Reading the public model catalog without a key is fine.
- Do not push, open PRs, merge, tag or publish releases unless the task explicitly says so.

### 9. Deployment must be explicit
Do not imply a feature is live unless it has actually been rebuilt/redeployed.

When deployment is requested:
- rebuild the affected services
- recreate the containers
- verify service health or startup logs
- state whether old persisted data remains unchanged

When release/distribution behavior changes:
- validate `docker-compose.prod.yml`
- validate the relevant Docker image build path
- do not assume a GitHub Release or registry publish succeeded until it is verified

## Definition of Done
A feature is done when all of the following are true:
- the feature spec is concrete enough to implement without guesswork
- code changes are complete across backend/frontend/schema as needed
- tests were added or adjusted for behavior changes
- relevant validation commands passed
- docs updated per the documentation guardrails
- migrations were added if applicable
- deploy status is explicitly reported
- residual limitations or deferred follow-up work are explicitly listed

## Release Policy
- Release tags use `vX.Y.Z`.
- `main` should remain releasable.
- Semantic versioning: feature additions -> minor bump; fixes, docs, CI/release pipeline changes and hardening -> patch bump.
- A tag needs a `## [X.Y.Z] - date` section in `CHANGELOG.md`; the release workflow builds the release notes from it and rejects tags without one.
- Release highlights (the text between `## [X.Y.Z] - date` and the first `###`; it becomes "Highlights" in the GitHub release unchanged):
  - one bold tagline with the release theme and a short sentence;
  - 3–6 bullets, each `- <one emoji> **Feature name**: one sentence of user benefit` (no implementation details; those belong under Added/Changed);
  - for releases with migrations, end with `> 💾 [Back up your database](docs/deployment.md#backups-and-rollback) before upgrading.`;
  - one emoji per bullet, matching its topic; no emoji rows or decoration elsewhere.
- Releases are immutable once published: everything attached to a release (assets) must be uploaded in the step that creates it.
- A release is only considered complete when all of the following exist and are verified:
  - git tag
  - GitHub Release
  - GHCR backend image
  - GHCR frontend image
- Pinned version tags are the supported deployment target; `latest` is convenience only.
- Release-affecting changes should update `CHANGELOG.md`.
- Do not cut a release if tests/builds are failing, docs are materially misleading, or release artifacts are known broken/unverified.
- After feature, fix, or release-pipeline work, explicitly assess whether a new release is now appropriate and tell the user when it is.

## Final Response Requirements
When finishing a feature task, include:
- what changed
- what was validated
- whether deployment happened
- any residual limits, risks, or deferred work
- whether the current state suggests cutting a new release

Do not assume “implemented” means “deployed”.
