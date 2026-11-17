# AI Chess Arena

Let large language models play chess against each other — in brackets, live, with trash talk.

[![CI](https://github.com/FJrepo/ai-chess-arena/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/FJrepo/ai-chess-arena/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/FJrepo/ai-chess-arena)](https://github.com/FJrepo/ai-chess-arena/releases)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

Run single-elimination tournaments between [OpenRouter](https://openrouter.ai) models on your own machine. Pick the participants, watch every move and message, join as a human player, and compare results, reliability and reported cost.

![Tournament bracket](docs/screenshots/tournament-bracket.png)

## Why this exists

**Mainly for fun.** Watching models blunder their queens while taunting each other makes for a tournament night.

As a side note, it is a **benchmaxxing smell test**: legality checks, retries and forfeits offer an informal second look at model claims. Small brackets and one narrow skill cannot establish whether benchmark gains generalize.

| Live game | Analytics |
|---|---|
| ![Finished game: resignation with last words, move-quality marks, ACPL per side and the chat](docs/screenshots/live-game-board-chat.png) | ![Analytics dashboard: headline cards and the model comparison table](docs/screenshots/analytics-dashboard.png) |
| **Move quality** | **Model picker** |
| ![Move quality table: ACPL, blunders per game, mistakes and inaccuracies per model](docs/screenshots/analytics-move-quality.png) | ![Model picker with the details card: prices, context, reasoning effort levels and Intelligence Index](docs/screenshots/model-picker.png) |

## Features

- **[Tournaments](docs/how-it-works.md#tournament-formats-and-draw-policies):** pit models against each other in brackets and best-of series.
- **[Auto-run](docs/how-it-works.md#auto-run):** run tournaments with concurrent games, budget controls and pause/resume.
- **[Live games and chat](docs/how-it-works.md#live-settings):** watch every move, follow the trash talk and review earlier positions.
- **[Move quality](docs/how-it-works.md#move-quality):** spot blunders, mistakes and missed mates with Stockfish evaluation.
- **[Model picker](docs/how-it-works.md#choosing-participants-and-model-picker) and [reasoning effort](docs/how-it-works.md#reasoning-effort):** compare models, prices and capabilities, then choose how hard they think.
- **[Analytics](docs/how-it-works.md#analytics-and-reliability) and [leaderboard](docs/how-it-works.md#leaderboard):** compare results, reliability, costs and Elo across tournaments.
- **[Export](docs/how-it-works.md#export):** download annotated games and tournament data as PGN, CSV or JSON.
- **[Human play](docs/how-it-works.md#choosing-participants-and-model-picker):** join a tournament yourself and challenge the models.
- **[Cost preview](docs/how-it-works.md#cost-estimate):** estimate games, runtime and spend before starting.

…and more: openings, adjudication, resignation, cancellation, resumable games and custom instructions — see [How it works](docs/how-it-works.md).

## Quick start

You need Docker Desktop on macOS/Windows (including Apple Silicon), or Docker Engine 28+ with Compose on Linux, and an [OpenRouter API key](https://openrouter.ai/keys).

```bash
mkdir chess-arena
cd chess-arena
curl -fL -o docker-compose.yml https://github.com/FJrepo/ai-chess-arena/releases/latest/download/docker-compose.yml
curl -fL -o env.example https://github.com/FJrepo/ai-chess-arena/releases/latest/download/env.example
install -m 600 env.example .env
```

Set `OPENROUTER_API_KEY` in `.env` (or use a [secret file](docs/deployment.md#secrets)), then start. Database credentials are generated automatically; upgrades must keep their existing `DB_PASSWORD`.

```bash
docker compose up -d --wait
```

Open **http://localhost:4200**, create a tournament, generate the bracket and start the first game. The API is available under `http://localhost:4200/api`.

Windows: use the [PowerShell variant](docs/deployment.md#windows-powershell).

Pin a version: replace `latest/download` with `download/vX.Y.Z`.

Contributors: see the [source-checkout setup](docs/deployment.md#run-from-a-source-checkout) using `make setup` and `docker-compose.prod.yml`.

Everything binds to loopback. See the [deployment guide](docs/deployment.md) for ports, host names, upgrades, backups and troubleshooting.

## How it works

Models receive the current position, legal moves and recent conversation. Answers are checked for legality; exhausted attempts or turn deadlines cause forfeits. Provider problems pause play for you to resume.

[Conversation history](docs/how-it-works.md#prompt-caching) survives human turns; deleting or finishing a game clears its retained history, with periodic cleanup as a safety net.

See [How AI Chess Arena works](docs/how-it-works.md) for rules, defaults, limits, prompt versions, API fields and recovery instructions.

Built for **one operator on localhost**, without authentication or accounts, on desktop browsers. Read the [supported scope and limitations](docs/how-it-works.md#scope-and-interpretation) before changing network access.

## Development

**Source build with Docker:**

```bash
make setup
docker compose up --build -d   # frontend :4200, backend :8081, PostgreSQL :5433 (all loopback)
```

**Without Docker** (Java 25, Maven 3.9+, Node.js 24.15+ — 26 recommended, PostgreSQL 17; Stockfish 19 on `PATH` for evaluation). Maven does not read the root `.env`, so export the settings in the backend terminal; run backend and frontend in separate terminals, each from the repository root:

```bash
docker compose up -d db                 # or your own PostgreSQL on localhost:5433

# terminal 1 – backend on http://localhost:8081
export OPENROUTER_API_KEY=sk-or-...
export DB_USER=chess DB_PASSWORD=chess  # dev-profile defaults; match your PostgreSQL
cd backend && mvn quarkus:dev

# terminal 2 – frontend on http://localhost:4200 (proxies /api and /ws to :8081)
cd frontend && npm ci && npm start
```

**Tests and checks** (from the repository root):

```bash
make test                               # backend unit + frontend tests
(cd backend && mvn verify)              # + PostgreSQL integration tests (Docker required)
(cd frontend && npm run lint && npm run typecheck && npm run format:check)
python3 -m pip install -r scripts/requirements-test.txt # YAML parser for script tests
python3 -B -m unittest discover -s scripts -p 'test_*.py' # offline installer and release tests
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for the contribution workflow.

## Stack

| Part | Technology |
|---|---|
| Backend | Quarkus 3.40 LTS on Java 25 — REST, WebSockets Next, Hibernate ORM Panache, Flyway; game loops on virtual threads |
| Frontend | Angular 21.2 with Angular Material, standalone components, CSS chessboard |
| Database | PostgreSQL 17 |
| Models | OpenRouter chat models (text output) |
| Evaluation | Stockfish 19 (bundled in the backend image, `amd64` or `arm64` universal binary; the app runs without it) |

## Repository layout

| Path | Contents |
|---|---|
| `backend/` | Quarkus API, game engine, OpenRouter and Stockfish integration, Flyway migrations |
| `frontend/` | Angular application |
| `docker-compose.yml` | Local source-build stack |
| `docker-compose.prod.yml` | Released-image stack |
| `docs/deployment.md` | Deployment, upgrade and troubleshooting guide |
| `docs/how-it-works.md` | Detailed tournament rules, model settings, analytics and API conventions |

## Releases

Releases use `vX.Y.Z`. Deploy a pinned version; `latest` is a convenience tag. See [GitHub Releases](https://github.com/FJrepo/ai-chess-arena/releases), [CHANGELOG.md](CHANGELOG.md) and the [release publishing notes](docs/deployment.md#release-publishing-notes) for CI gates, images, setup assets and verification.

## Credits

Chess piece images (Cburnett set) by Colin M.L. Burnett, with later revisions by Wikimedia Commons contributors, from Wikimedia Commons, used under the BSD licence option; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md#chess-piece-images-cburnett-set).

Intelligence Index by [Artificial Analysis](https://artificialanalysis.ai) (via the OpenRouter model catalog). Model metadata and prices come from [OpenRouter](https://openrouter.ai).

Move quality is judged using the win-probability model Lichess uses; see the [detailed method](docs/how-it-works.md#move-quality).

## License

MIT — see [LICENSE](LICENSE). Bundled third-party components (including Stockfish, GPL-3.0-or-later) are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and [LOGO-LICENSES.md](LOGO-LICENSES.md).
