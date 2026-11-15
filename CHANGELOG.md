# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and this project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed
- The released-image stack (`docker-compose.prod.yml`) no longer publishes a backend host port; the frontend proxies `/api` and `/ws`, so the API is at `http://localhost:${FRONTEND_PORT}/api`. `BACKEND_PORT` is no longer used there.
- `POST /api/tournaments` accepts optional `participants` and `clientRequestId` fields and returns `200` with the existing tournament when the same `clientRequestId` is repeated.
- Dependabot no longer proposes Angular/TypeScript/ESLint majors or Java/Node base-image version jumps; those upgrades are done deliberately.
- Development and released-image Compose ports now bind to loopback by default on the documented Docker Engine 28.0+ baseline, preserving local access without exposing the unauthenticated control plane to the surrounding network.
- Production Compose now requires an explicit database password, includes a one-time rotation path for volumes created with the former fallback, and `make setup` creates or tightens `.env` to owner-only permissions.
- Backend upgraded from Quarkus 3.21.3 to the 3.40 LTS stream; the CORS switch is now `quarkus.http.cors.enabled` and the Hibernate schema setting uses `schema-management.strategy`.
- Frontend upgraded from Angular 21.1 to the latest 21.2.x patch release, including Angular Material, CDK and angular-eslint.
- Container base images re-pinned to current digests.
- CI and release workflows moved to Node 24-based action majors after GitHub removed the Node 20 Actions runtime; frontend CI now builds on Node 24 to match the container image.
- Dependabot now proposes weekly Maven, npm and Docker updates and monthly GitHub Actions updates.
- Backend database integration tests (`*IT`, run by `mvn verify` and in CI) use a Quarkus Dev Services PostgreSQL; `mvn test` still runs only Docker-free unit tests. The datasource URL and credentials are now configured per profile (`prod`, `dev`).
- Releases now run the full CI suite on the tagged commit, accept only exact `vX.Y.Z` tags from `main`, push images as commit-addressed candidates first, smoke-test both candidates together via `docker-compose.prod.yml`, and only then promote both digests to the version tag and `latest`. A failed release no longer leaves a mismatched `latest` pair.
- `docker-compose.prod.yml` now requires `IMAGE_TAG` instead of defaulting to `latest`; set a release version such as `v1.1.0` in `.env`.

### Fixed
- OpenRouter infrastructure failures no longer decide games. Rate limits, server errors and network failures are retried with backoff without using up the model's attempts; invalid keys, missing credits, unknown models, or retries that run out pause the game with a visible reason instead of recording a forfeit. Resume the game once the problem is fixed.
- Finished or running games can no longer be started again: `POST /api/games/{id}/start` only accepts `WAITING` and `PAUSED` games (atomically) and returns `409` otherwise, and a finished game's result can no longer be recorded or advanced twice.
- Concurrent human or override moves can no longer write two moves for the same ply: move writes lock the game row and are rejected with `409` when the position changed, and the database enforces one move per ply (migration V8).
- Best-of series now end after at most N games: a draw counts as a game without a winner, a series ends as soon as the lead can no longer be caught, and a series that is level after N games is decided by the tournament's draw policy (White advances = white in game 1, Black advances, Higher seed, Random). Only `Replay game` adds sudden-death tiebreak games until one is decisive; the setup estimate now shows that maximum as open-ended and the bracket labels those games as tiebreaks.
- The game that decides a series records who advanced, so draw-policy decisions (including random ones) are kept: the bracket highlights and names the right player, and a completed tournament shows its champion (`winnerName` in the tournament API, `advancedPlayerName` per game).
- Generating a bracket twice (double click, retry, concurrent requests) no longer creates duplicate brackets: generation locks the tournament, only runs for `CREATED` tournaments without games, and returns `409` otherwise; the button is disabled while the request runs. Migration V9 adds a unique bracket-slot index (skipped with a notice if old duplicates exist).
- Extra series games created by older versions no longer count toward the result; only the best-of games (and `Replay game` tiebreaks after a level series) do.
- Creating a tournament is now one request: the tournament and all participants are stored together or not at all, a retry with the same client request id returns the first tournament, the Create button is disabled while saving, and errors are shown instead of silently leaving a partial tournament. Participants can no longer be added after the bracket is generated (`409`).
- With `openrouter.model-validation-fail-open=true`, adding participants now works while the OpenRouter model catalog is unavailable; previously every model was rejected.
- Bracket advancement no longer rewrites the pairing of a next-round game that has already started, and concurrent advancements into the same game are serialized.
- Pausing, completing and forfeiting lock the game row, so they can no longer overwrite a concurrently recorded move; repeated pause requests can no longer let a resumed game run two loops at once.
- Games left in progress by a backend restart, or by an unexpected game-loop failure, are now paused with a visible reason instead of being stuck; resume them from the game view. Open game views reload their state after the live connection reconnects.
- A paused game can no longer be forfeited, and pause requests that coincide with a human move or a loop handing over to a human turn are no longer lost.
- `POST /api/games/{id}/start` returns the game state after the start instead of the previous snapshot.
- WebSocket game subscriptions now reject missing games, move a connection cleanly between games, and remove empty subscription state on unsubscribe or disconnect.

### Security
- Live-update WebSockets now limit message size (4 KB), cap simultaneous connections (256 by default), rate-limit subscribe requests per connection, detect dead peers with pings, and drop subscriptions from connections that closed while subscribing.
- Default Docker networking now matches the documented localhost-only product boundary for the unauthenticated application, with Docker Engine 28.0+ required for loopback isolation.
- Frontend runtime dependencies no longer carry known vulnerabilities (previously seven high-severity advisories in `@angular/core` and `@angular/compiler`); remaining moderate advisories are confined to build tooling and resolve with Angular 22.

## [1.0.0] - 2026-03-08

### Added
- Local human participants can now be included in tournaments, with live move submission from the game view and normal bracket progression after human turns.

### Changed
- README and deployment docs now focus on the supported local-first product scope, streamlined setup, and `v1.0.0` release guidance.
- Game prompts and UI labels now use neutral tournament wording so model-vs-model and human-participant matches read consistently.

### Fixed
- Pausing a game while it is waiting on a local human move now transitions the game to `PAUSED` immediately instead of leaving it live with a stale engine marker.
- Model analytics now ignore human moves without a model ID instead of aggregating them under a fake `unknown` model row.

## [0.9.10] - 2026-03-08

### Changed
- Release image publishing now explicitly sets up a Buildx builder before backend and frontend publish jobs, so GitHub Actions can use the `gha` cache backend correctly.

## [0.9.9] - 2026-03-08

### Changed
- Backend release-image builds now retry whole Maven `go-offline` and package steps instead of failing on a single transient invocation error.
- Release image publishing now uses GitHub Actions build cache for backend and frontend Docker builds to reduce repeated cold-start dependency work.

## [0.9.8] - 2026-03-08

### Changed
- Prompt resolution and conversation initialization now live in a dedicated backend conversation service instead of being embedded directly in `GameEngineService`.
- Backend refactor coverage now includes focused tests for conversation prompt selection and board replay initialization.

## [0.9.7] - 2026-03-08

### Changed
- Single-turn retry execution, timeout handling, and invalid-move retry flow now live in a dedicated backend turn service instead of being embedded directly in `GameEngineService`.
- Backend refactor coverage now includes focused tests for turn execution outcomes.

## [0.9.6] - 2026-03-07

### Changed
- Game lifecycle state transitions now live in a dedicated backend service instead of being embedded directly in `GameEngineService`.
- Move recording, override application, chat persistence, and evaluation-update handling now live in a dedicated backend service instead of being embedded directly in `GameEngineService`.
- Backend refactor coverage now includes focused tests for game lifecycle and move-recording behavior.

## [0.9.5] - 2026-03-07

### Changed
- Analytics window loading and terminal-game filtering now live in a dedicated backend loader instead of being embedded directly in `AnalyticsService`.
- Analytics game-outcome interpretation now lives in a dedicated backend classifier instead of being mixed into analytics aggregation logic.
- Backend analytics refactor coverage now includes focused tests for outcome classification behavior.

## [0.9.4] - 2026-03-07

### Changed
- Analytics reliability scoring now lives in a dedicated backend collaborator instead of being duplicated across analytics code paths.
- Backend analytics scoring behavior now has focused unit coverage outside the broader analytics service tests.

## [0.9.3] - 2026-03-07

### Changed
- Game view live turn and timer state now lives in a dedicated frontend service instead of being embedded directly in `GameView`.
- Game view now surfaces load, pause, and resume failures to the operator instead of failing silently.

## [0.9.2] - 2026-03-07

### Changed
- Frontend live-update handling now uses concrete websocket message variants instead of broad catch-all typing.
- Live-update and tournament-load failures now surface through the UI instead of relying on console-only diagnostics.

## [0.9.1] - 2026-03-07

### Changed
- Default backend runtime configuration now requires explicit database credentials instead of shipping committed fallback values.
- Quarkus `dev` profile keeps the previous local `chess/chess` database defaults for local development convenience.

## [0.9.0] - 2026-03-07

### Added
- New comparison-first analytics endpoint at `GET /api/analytics/comparison`.
- Analytics dashboard now identifies strongest, most reliable, fastest, and best-value models for the selected sample window.

### Changed
- Analytics now exposes win/draw/loss records, forfeit behavior, white-vs-black splits, spend, and cost-per-win in one sortable model comparison view.
- Pricing-aware analytics now render incomplete price coverage explicitly instead of implying full cost comparability.

## [0.8.0] - 2026-03-07

### Added
- New toolbar brand mark with a cleaner chess-and-arena visual identity.

### Changed
- Desktop pages now share a more consistent shell, hero treatment, and card language across tournament list, analytics, system status, and bracket views.
- Tournament, analytics, and system headers now use a unified desktop title hierarchy instead of separate page-specific patterns.

## [0.7.0] - 2026-03-06

### Added
- Game view now includes a unified event timeline for moves, chat, retries, overrides, and result events.

### Changed
- Timeline entries can jump directly to the associated move context when available.
- The game timeline now complements the SAN move list with broader match context instead of relying on the move list alone.

## [0.6.0] - 2026-03-06

### Added
- Bracket cards now present richer live matchup state with cleaner hierarchy, including live, paused, ready, pending, complete, forfeit, and bye states.

### Changed
- Bracket cards now prioritize state, score, and current series context over duplicated result badges.
- In-progress series can expose a direct `Review Previous` action for the prior game in the series.

## [0.5.0] - 2026-03-06

### Added
- Tournament history now shows a first-run onboarding flow with starter presets for the first launch.
- Tournament setup now includes a confidence panel with projected games, runtime, and rough spend heuristics.

### Changed
- Tournament setup presets can prefill starter configurations through onboarding links.
- The frontend component-style warning budget was raised to reflect the richer onboarding layout.

## [0.4.0] - 2026-03-06

### Added
- Immutable system rules are now separated from optional prompt customization.
- Tournament setup now supports shared custom instructions for all participants.
- Tournament setup now supports participant-specific instruction overrides.

### Changed
- New tournaments no longer edit the full system prompt directly.
- Prompt resolution now applies a stable precedence model: legacy full-template override, participant instructions, shared instructions, then base rules only.

## [0.3.0] - 2026-03-06

### Added
- Dedicated `/system` dashboard for operator visibility into backend, OpenRouter, and Stockfish status.
- Backend system-status payload now includes backend version and status check timestamp.

### Changed
- Runtime application metadata is aligned with the current release line so the system dashboard reflects shipped versions accurately.

## [0.2.1] - 2026-03-06

### Added
- Backend image now includes explicit Stockfish notice/license artifacts for distribution compliance.
- Game view now shows explicit evaluation engine status.
- Tournament list now supports filtering and sorting in the UI.

### Changed
- Release/deployment policy is now documented explicitly for maintainers.
- Stockfish evaluation is queued only after move persistence commits successfully.

### Fixed
- Backend now degrades gracefully when Stockfish is unavailable instead of leaving evaluation behavior ambiguous.
- Advantage-bar updates no longer race move persistence and get dropped intermittently.
- Frontend game view makes evaluation availability clearer when Stockfish is offline.

## [0.2.0] - 2026-03-06

### Added
- Best-of tournament matchup series with optional finals-specific override.
- Release workflow for version tags that publishes frontend/backend images to GitHub Container Registry.
- Production deployment assets:
  - `docker-compose.prod.yml`
  - `docs/deployment.md`
  - expanded `.env.example`
- Repo workflow policy in `AGENTS.md`.

### Changed
- README now distinguishes released-image deployment from local source-build development.
- Tournament brackets and setup now document released behavior for best-of series.

### Fixed
- Stockfish evaluation scores are normalized to White's perspective before persistence/rendering.
- Bound-only Stockfish scores are ignored so the advantage bar does not treat them as exact evaluations.

## [0.1.0] - 2026-02-25

### Added
- Initial public project structure for AI Chess Arena.
- Quarkus backend with REST and WebSocket endpoints for tournaments, games, and analytics.
- Angular frontend with tournament setup, bracket view, live game view, and analytics dashboard.
- PostgreSQL + Flyway migrations for persisted game/tournament data.
- Docker Compose local stack (`db`, `backend`, `frontend`).
- Branding/legal inventory file: `LOGO-LICENSES.md`.
- Repository governance and quality files:
  - `LICENSE` (MIT)
  - `CONTRIBUTING.md`
  - GitHub issue templates and PR template
  - Baseline GitHub Actions CI workflow (`.github/workflows/ci.yml`)

### Changed
- Standardized core naming to **AI Chess Arena** across key metadata and UI labels.
- Migrated Java package namespace from `com.example.chess` to `dev.aichessarena`.
- Updated project metadata versions to `0.1.0` (`backend/pom.xml`, `frontend/package.json`).
- Renamed Angular project key to `ai-chess-arena` and aligned Docker dist output path.
- Added root `.gitignore`, `.env.example`, and Docker ignore files for backend/frontend.

### Security
- Added environment template and ignore rules to reduce accidental secret commits.
- Parameterized database credentials in `docker-compose.yml`.
