# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and this project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
