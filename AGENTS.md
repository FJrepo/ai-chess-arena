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
- If persistence changes, add a Flyway migration.

### 3. README updates are required when applicable
Update `README.md` when a change affects:
- user-visible behavior
- feature limits or defaults
- setup or configuration
- runtime expectations
- admin workflow
- deployment or operational behavior

If a feature does not affect any of those, say so in the final summary.

### 4. Tests are part of feature work
When behavior changes, add or adjust tests.

Expected coverage:
- backend tests for service and resource behavior
- frontend tests for changed logic or interaction where practical
- regression tests for bugs fixed during implementation

If a test is intentionally deferred, call it out explicitly with the reason.

### 5. Validation is required before calling work done
Run the relevant checks for the files and behavior touched.

Default validation set:
- `cd backend && mvn test -DskipITs`
- `cd frontend && npm run lint`
- `cd frontend && npm run typecheck`
- `cd frontend && npm test -- --watch=false`
- `cd frontend && npm run build -- --configuration development`
- `cd frontend && npm run format:check`

If a command is not applicable or cannot be run, state that explicitly.

### 6. Commit scope must stay clean
- Do not mix feature code with unrelated cleanup.
- Do not include `analysis/`, `task-packages/`, or `CLAUDE.md` in commits unless the user explicitly asks for it.
- Keep formatting-only follow-ups separate when practical.
- If history must be rewritten, prefer `git push --force-with-lease`.

### 7. Deployment must be explicit
Do not imply a feature is live unless it has actually been rebuilt/redeployed.

When deployment is requested:
- rebuild the affected services
- recreate the containers
- verify service health or startup logs
- state whether old persisted data remains unchanged

## Definition of Done
A feature is done when all of the following are true:
- the feature spec is concrete enough to implement without guesswork
- code changes are complete across backend/frontend/schema as needed
- tests were added or adjusted for behavior changes
- relevant validation commands passed
- `README.md` was updated if applicable
- migrations were added if applicable
- deploy status is explicitly reported
- residual limitations or deferred follow-up work are explicitly listed

## Final Response Requirements
When finishing a feature task, include:
- what changed
- what was validated
- whether deployment happened
- any residual limits, risks, or deferred work

Do not assume “implemented” means “deployed”.
