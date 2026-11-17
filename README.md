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

- **[Tournaments](docs/how-it-works.md#tournament-formats-and-draw-policies):** random or added-order bracket draws (UI defaults to random), best-of series, separate final settings and draw policies.
- **[Opening variety](docs/how-it-works.md#opening-variety):** standard starts, random book lines or a fixed opening from 42 authored lines; color-reversed series partners share the same opening, with book moves excluded from model metrics. Start/resume prompts name the final stored move and restore its message when present.
- **[Participants](docs/how-it-works.md#choosing-participants-and-model-picker):** OpenRouter chat models, custom instructions and optionally one human player.
- **[Model picker](docs/how-it-works.md#choosing-participants-and-model-picker):** catalog search, provider filters, prices, reasoning support and Intelligence Index.
- **[Live games](docs/how-it-works.md#live-settings):** board, moves, chat and retries over WebSocket, with optional Stockfish 19 evaluation. Background refreshes preserve the position being reviewed; viewers at the latest move keep following new moves.
- **[Move quality](docs/how-it-works.md#move-quality):** inaccuracies, mistakes, blunders, missed mates and average centipawn loss (ACPL).
- **[Adjudication](docs/how-it-works.md#adjudication):** optional decisive wins after 10 evaluated plies; per-model [conversion metrics](docs/how-it-works.md#conversion-metrics).
- **[Turn controls](docs/how-it-works.md#model-turns-and-prompts):** attempts, optional deadlines, output-token limits and reasoning effort overrides.
- **[Chat](docs/how-it-works.md#chat-mode):** off, optional or mandatory, with talkativeness tracked per mode.
- **[Resignation](docs/how-it-works.md#resignation):** optional from each model's tenth non-book move (nine own played moves completed), with last words and resignation analytics.
- **[Cancellation](docs/how-it-works.md#cancelling-a-tournament):** end unfinished games without a result and keep finished games and spend. Cancelled and completed tournaments reject settings and participant reasoning-effort changes (409). Late paid answers still update attempts and spend in open game views.
- **[Resumable games](docs/how-it-works.md#provider-problems-and-resuming-games):** provider problems and unusable cut-off answers pause play; restart interruptions can be resumed.
- **[Analysis](docs/how-it-works.md#analytics-and-reliability):** series scores, PGN export, reliability, stored attempts, prompt caching metrics and reported costs.
- **[Cost preview](docs/how-it-works.md#cost-estimate):** estimated games, runtime and spend before starting.

Tournament setup starts with **Add participant**, with optional custom instructions revealed on demand; collapsing the field preserves its draft when adding a participant. The **Tournament lineup** groups the tournament name, roster, visible participant reasoning-effort overrides, readiness checklist and **Create Tournament** action before the rough runtime and spend estimate. At widths of at least 1200px, the sidebar stays sticky only when its measured height fits the window with an 88px top offset and a 24px bottom gap; otherwise it uses natural page scrolling. Without ResizeObserver support, it also uses natural page scrolling. Below 1200px, the order is add participant, settings, lineup, then estimate, matching keyboard navigation. Settings keep prompt rules and shared instructions in a collapsed **Advanced: prompt** section, with a **custom** badge when shared instructions are set.

Prompt caching savings use one cached price snapshot per comparison. Analytics never waits for catalog requests: missing prices show **n/a**, stale prices remain usable, and failed refreshes back off for 60 seconds.

Concurrent catalog lookups use a consistent snapshot for freshness and model data, so model validation and reasoning-effort mapping can use a catalog published by another caller during a refresh.

Legacy duplicate move rows count as one ply for adjudication, move quality (including missed mates), Conversion and talkativeness: the earliest `created_at` wins, with `id` breaking ties. Later duplicates cannot supply a missing evaluation or extend the ten-ply window. Stored rows and completed results remain unchanged; see [duplicate-move troubleshooting](docs/deployment.md#migration-v8-reports-skipping-uq_moves_game_ply).

## Quick start

You need Docker Engine 28.0+ (or Docker Desktop with Engine 28.0+), Make, and an [OpenRouter API key](https://openrouter.ai/keys).

Release images support amd64 and arm64, including Apple Silicon.

```bash
git clone https://github.com/FJrepo/ai-chess-arena.git && cd ai-chess-arena
make setup                     # creates .env (mode 0600) from .env.example
```

Edit `.env`:

```bash
IMAGE_NAMESPACE=fjrepo         # published images; use your own namespace for a fork
IMAGE_TAG=v1.5.0               # a release from https://github.com/FJrepo/ai-chess-arena/releases
OPENROUTER_API_KEY=sk-or-...
DB_PASSWORD='choose-a-strong-password'
```

Start it:

```bash
docker compose -f docker-compose.prod.yml pull
docker compose -f docker-compose.prod.yml up -d
```

Open **http://localhost:4200**, create a tournament, generate the bracket and start the first game. The API is available under `http://localhost:4200/api`.

Everything binds to `127.0.0.1` only; set `FRONTEND_PORT` in `.env` to change the UI port. nginx proxies `/api` and `/ws` from the same origin.

The frontend accepts `localhost`, `127.0.0.1` and `[::1]`; other host names need [`ALLOWED_HOSTS`](docs/deployment.md#host-names-allowed_hosts).

See [docs/deployment.md](docs/deployment.md) for upgrades, backups, password rotation and troubleshooting.

From v1.6.0: two-file setup, see the [5-minute start](docs/deployment.md#5-minute-start-from-v160).

## How it works

- Add models or one human participant, then generate the seeded bracket; top seeds receive byes when needed.
- Each turn supplies the position, legal moves and PGN, together with a bounded recent conversation history.
- Answers are checked for legality; invalid moves use attempts, and exhausted attempts or turn deadlines cause forfeits.
- Provider problems and cut-off answers without a usable move pause play; adjust settings and resume from the game view.
- Stockfish evaluates moves in the background; requests arriving during engine recovery wait in the evaluation queue. The engine reports ready only after its UCI handshake succeeds. Handshakes time out after 10 seconds and evaluations after 30 seconds (configurable with `STOCKFISH_HANDSHAKE_TIMEOUT` and `STOCKFISH_EVALUATION_TIMEOUT`, as durations such as `PT10S`); a timeout returns no score and disables evaluation until backend restart. Analytics compare finished games, reliability, move quality and reported spend.
- Engine timeout and backend shutdown terminate Stockfish's child processes before the engine itself. Pipe closing runs in the background so stalled engine I/O does not block callers.
- Turn settings changed during play apply from the next turn, including participant reasoning overrides. Opening settings apply only to newly created games; regular color-reversed partners reuse their stored line.

See [How AI Chess Arena works](docs/how-it-works.md) for rules, defaults, limits, prompt versions, API fields and recovery instructions.

Built for **one operator on localhost**, without authentication or accounts, on desktop browsers around 1280 px wide or larger. Phones and tablets are unsupported. Network exposure needs your own authentication, TLS and firewall; a TLS-terminating reverse proxy in front of the frontend is unsupported. This is not a public chess site, hosted service or engine-research suite.

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

Releases use `vX.Y.Z`. The workflow runs full CI, builds both images and smoke-tests them together before publishing to GHCR as the version tag and `latest`. Deploy a pinned version; `latest` is a convenience tag. See [GitHub Releases](https://github.com/FJrepo/ai-chess-arena/releases) and [CHANGELOG.md](CHANGELOG.md).

Release notes use the matching changelog entry, with upgrade notes first and image index digests at the bottom; missing entries stop the release before builds. From v1.6.0, releases will include `docker-compose.yml` pinned to the release tag and `env.example`, attached when the immutable release is created. See [release publishing notes](docs/deployment.md#release-publishing-notes).

Release builds preserve the backend Dockerfile's `MIT, GPL-3.0-or-later` licence label for bundled Stockfish. Frontend and other generated OCI labels are unchanged.

## Credits

Chess piece images (Cburnett set) by Colin M.L. Burnett, with later revisions by Wikimedia Commons contributors, from Wikimedia Commons, used under the BSD licence option; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md#chess-piece-images-cburnett-set).

Intelligence Index by [Artificial Analysis](https://artificialanalysis.ai) (via the OpenRouter model catalog). Model metadata and prices come from [OpenRouter](https://openrouter.ai).

Move quality is judged using the win-probability model Lichess uses; see the [detailed method](docs/how-it-works.md#move-quality).

## License

MIT — see [LICENSE](LICENSE). Bundled third-party components (including Stockfish, GPL-3.0-or-later) are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and [LOGO-LICENSES.md](LOGO-LICENSES.md).
