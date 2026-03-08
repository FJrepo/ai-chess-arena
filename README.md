# AI Chess Arena

AI Chess Arena is a local-first chess tournament app for OpenRouter models.

Run tournaments, include one local human participant, watch games live, and review which models perform well, stay reliable, and justify their cost.

> Local-first by design: this project is intended for localhost and private environments. Public auth, multi-tenant isolation, and internet-facing hardening are out of scope for the supported product.

## What It Is For
- Running model-vs-model chess tournaments from a local machine
- Observing live games with move, chat, and status updates
- Comparing model results with bracket views, PGN export, and analytics
- Including one local human participant in the tournament flow

## What You Can Do
- Create single-elimination tournaments with best-of `1`, `3`, `5`, or `7` series.
- Add OpenRouter models as participants with shared or participant-specific custom instructions.
- Add one local human participant and play moves from the live game view.
- Watch games live with move feed, chat, PGN export, and optional Stockfish evaluation.
- Review bracket progress, system health, and model analytics from the web UI.

## Current Scope
- Tournament format: single elimination
- Human participants: one local human participant per tournament
- Authentication: none
- Multi-user ownership or permissions: none
- Recommended runtime: localhost or private network

## Stack
- Backend: Quarkus 3.21.3, Java 21, REST, WebSocket, Panache, Flyway
- Frontend: Angular 21.1.x, Angular Material 21.1.x, TypeScript 5.9.x
- Database: PostgreSQL 17
- Model provider: OpenRouter
- Evaluation engine: Stockfish 18

## Screenshots
### Tournament Bracket
![Tournament bracket view](docs/screenshots/tournament-bracket.png)

### Live Game
![Live game with board, moves, and chat](docs/screenshots/live-game-board-chat.png)

### Analytics Dashboard
![Analytics dashboard with model metrics](docs/screenshots/analytics-dashboard.png)

## Typical Workflow
1. Pick a few models and create a tournament.
2. Tune the format, retries, timeouts, and optional custom instructions.
3. Start the bracket and watch matches play out live.
4. Jump into a game when a human-controlled participant is on move.
5. Use analytics and PGNs to decide which models were actually worth running.

## Quick Start
For most users, the released-image path is the fastest way to get to a working arena.

### Run Released Images
1. Create an env file:

```bash
cp .env.example .env
```

2. Edit `.env` and set:
- `IMAGE_NAMESPACE`
- `IMAGE_TAG`
- `OPENROUTER_API_KEY`
- `DB_PASSWORD`

3. Pull and start the released stack:

```bash
docker compose -f docker-compose.prod.yml pull
docker compose -f docker-compose.prod.yml up -d
```

4. Verify the stack:

```bash
docker compose -f docker-compose.prod.yml ps
curl http://localhost:${BACKEND_PORT:-8081}/q/health/live
curl http://localhost:${BACKEND_PORT:-8081}/q/health/ready
```

Default URLs:
- Frontend: `http://localhost:${FRONTEND_PORT:-4200}`
- Backend API: `http://localhost:${BACKEND_PORT:-8081}`

See [docs/deployment.md](docs/deployment.md) for upgrade, persistence, and troubleshooting details.

## Local Development
Use this path when you want the current source tree instead of published images.

### Docker Compose From Source
```bash
cp .env.example .env
docker compose up --build -d
docker compose ps
docker compose logs -f backend frontend
```

Default local ports:
- Frontend: `http://localhost:4200`
- Backend API: `http://localhost:8081`
- PostgreSQL: `localhost:5433`

### Run Services Without Docker
Prerequisites:
- Java 21
- Maven 3.9+
- Node.js 22+
- npm 11+
- PostgreSQL 17
- OpenRouter API key
- Stockfish 18 on `PATH` if you want evaluation outside Docker

Start only the database with Docker if needed:

```bash
docker compose up -d db
```

Run the backend:

```bash
cd backend
mvn quarkus:dev
```

Run the frontend:

```bash
cd frontend
npm ci
npm start
```

### Useful Commands
```bash
cd backend && mvn test -DskipITs
cd frontend && npm run lint
cd frontend && npm run typecheck
cd frontend && npm test -- --watch=false
cd frontend && npm run build -- --configuration development
cd frontend && npm run format:check
```

## Intended Use
This project is a good fit if you want:
- a local operator tool for running and observing model-vs-model tournaments
- a UI for live games, bracket state, and post-run analysis
- a lightweight comparison harness without building your own orchestration layer

It is not intended to be:
- a public multiplayer chess site
- a hosted SaaS platform
- a general-purpose chess engine research suite

## Stockfish Notes
- The released backend image bundles Stockfish 18 for evaluation.
- The current bundled binary targets `amd64` / AVX2-class environments.
- If Stockfish is unavailable, the app still runs and the frontend disables evaluation features.
- Licensing and bundled-notice details live in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Repository Layout
- `backend/`: Quarkus API, game engine, DB migrations
- `frontend/`: Angular application
- `docker-compose.yml`: local source-build stack
- `docker-compose.prod.yml`: released-image deployment stack
- `docs/deployment.md`: deployment and upgrade guide
- `task-packages/`: task specs and planning documents

## Releases
- Release tags use `vX.Y.Z`.
- Released images are published to GHCR for backend and frontend.
- Pinned tags are the recommended deployment target.
- `latest` is convenience only.
- Release notes live in GitHub Releases, and project history lives in [CHANGELOG.md](CHANGELOG.md).

## Related Docs
- Deployment guide: [docs/deployment.md](docs/deployment.md)
- Third-party notices: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
- Contributing: [CONTRIBUTING.md](CONTRIBUTING.md)
- Changelog: [CHANGELOG.md](CHANGELOG.md)
