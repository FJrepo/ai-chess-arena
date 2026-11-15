# AI Chess Arena

[![CI](https://github.com/FJrepo/ai-chess-arena/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/FJrepo/ai-chess-arena/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/FJrepo/ai-chess-arena)](https://github.com/FJrepo/ai-chess-arena/releases)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

Let large language models play chess against each other — in brackets, live, with trash talk.

AI Chess Arena runs single-elimination tournaments between [OpenRouter](https://openrouter.ai) models on your own machine. You pick the models, watch every move and chat message as it happens, can jump in as a human player, and look at who actually won, who kept making illegal moves, and what it all cost.

![Tournament bracket](docs/screenshots/tournament-bracket.png)

## Why this exists
**Mainly for fun.** Watching two frontier models confidently blunder their queens while taunting each other is entertainment in its own right, and a bracket makes it a tournament night.

> **Side note: a benchmaxxing smell test.** New model releases arrive with impressive benchmark numbers. Chess is a cheap, unrehearsed check against that: the rules are simple, legality is verified move by move, and illegal moves, timeouts and forfeits are recorded. A model that tops the leaderboards but cannot finish a game without illegal moves is worth a second look. Treat the results as a smell test, not as a rigorous benchmark — brackets are small, games are few, and chess skill is only one narrow ability.

## Features
- **Tournaments:** single elimination with best-of 1, 3, 5 or 7 series (separate setting for the final), configurable move timeout, retries for invalid output, and draw policies.
- **Any OpenRouter model** as a participant, with shared or per-participant custom instructions; optionally **one human player** who moves from the live game view.
- **Live games** over WebSocket: board, move list, model chat, retries, and an optional Stockfish 18 evaluation bar.
- **Reliable runs:** OpenRouter outages, rate limits or an invalid API key pause the game with the reason instead of deciding it; games interrupted by a restart can be resumed.
- **Analysis:** bracket overview with series scores and the champion, PGN export, and analytics on results, reliability (illegal moves, timeouts, forfeits) and cost.
- **Cost estimate** before you start: expected games, runtime and spend for the chosen models and format.

| Live game | Analytics |
|---|---|
| ![Live game with board, moves and chat](docs/screenshots/live-game-board-chat.png) | ![Analytics dashboard](docs/screenshots/analytics-dashboard.png) |

## Quick start
You need Docker Engine 28.0+ (or Docker Desktop with Engine 28.0+), Make, and an [OpenRouter API key](https://openrouter.ai/keys).

```bash
git clone https://github.com/FJrepo/ai-chess-arena.git && cd ai-chess-arena
make setup                     # creates .env (mode 0600) from .env.example
```

Edit `.env`:

```bash
IMAGE_NAMESPACE=fjrepo         # published images; use your own namespace for a fork
IMAGE_TAG=v1.1.0               # a release from https://github.com/FJrepo/ai-chess-arena/releases
OPENROUTER_API_KEY=sk-or-...
DB_PASSWORD='choose-a-strong-password'
```

Start it:

```bash
docker compose -f docker-compose.prod.yml pull
docker compose -f docker-compose.prod.yml up -d
```

Open **http://localhost:4200**, create a tournament, generate the bracket and start the first game. The API is available under `http://localhost:4200/api`.

Everything binds to `127.0.0.1` only. Upgrades, backups, password rotation and troubleshooting are covered in [docs/deployment.md](docs/deployment.md).

## How tournaments work
- **Bracket:** participants are seeded in the order they are added; byes are assigned automatically when the field is not a power of two.
- **Best of N:** a series lasts at most N games. A draw is a game without a winner, and a series ends as soon as the lead can no longer be caught.
- **Level after N games** (or a drawn single game): the tournament's draw policy decides — *White advances* (white in game 1), *Black advances*, *Higher seed advances*, *Random*, or *Replay game*, which keeps adding sudden-death games until one is decisive (so it has no fixed maximum length or cost).
- **Model turns:** each move must be a legal move in the requested JSON format within the move timeout. Invalid output is retried up to the configured limit; after that the model forfeits. A model that is too slow forfeits on time. Problems on OpenRouter's side never count against a model — the game pauses with the reason and can be resumed.

## Scope
- Built for **one operator on localhost**: no authentication, no user accounts. Exposing it to a network requires your own authentication, TLS and firewall in front of it.
- One human participant per tournament; single elimination only.
- Not a public chess site, a hosted service, or an engine-research suite.

## Development
**Source build with Docker:**

```bash
make setup
docker compose up --build -d   # frontend :4200, backend :8081, PostgreSQL :5433 (all loopback)
```

**Without Docker** (Java 25, Maven 3.9+, Node.js 24.15+ — 26 recommended, PostgreSQL 17; Stockfish 18 on `PATH` for evaluation):

```bash
docker compose up -d db                 # or your own PostgreSQL on :5433
cd backend && mvn quarkus:dev           # http://localhost:8081
cd frontend && npm ci && npm start      # http://localhost:4200, proxies /api and /ws
```

**Tests and checks:**

```bash
make test                               # backend unit + frontend tests
cd backend && mvn verify                # + PostgreSQL integration tests (Docker required)
cd frontend && npm run lint && npm run typecheck && npm run format:check
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for the contribution workflow.

## Stack
| Part | Technology |
|---|---|
| Backend | Quarkus 3.40 LTS on Java 25 — REST, WebSockets Next, Hibernate ORM Panache, Flyway; game loops on virtual threads |
| Frontend | Angular 21.2 with Angular Material, standalone components, CSS chessboard |
| Database | PostgreSQL 17 |
| Models | OpenRouter (any chat model) |
| Evaluation | Stockfish 18 (bundled in the backend image, `amd64`/AVX2; the app runs without it) |

## Repository layout
| Path | Contents |
|---|---|
| `backend/` | Quarkus API, game engine, OpenRouter and Stockfish integration, Flyway migrations |
| `frontend/` | Angular application |
| `docker-compose.yml` | Local source-build stack |
| `docker-compose.prod.yml` | Released-image stack |
| `docs/deployment.md` | Deployment, upgrade and troubleshooting guide |

## Releases
Releases are tagged `vX.Y.Z`. The release workflow runs the full CI suite, builds both images, smoke-tests them together, and only then publishes them to GHCR as the version tag and `latest`. Deploy a pinned version; `latest` is a convenience tag. Notes are on [GitHub Releases](https://github.com/FJrepo/ai-chess-arena/releases); full history is in [CHANGELOG.md](CHANGELOG.md).

## License
MIT — see [LICENSE](LICENSE). Bundled third-party components (including Stockfish, GPL-3.0) are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and [LOGO-LICENSES.md](LOGO-LICENSES.md).
