# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and this project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
