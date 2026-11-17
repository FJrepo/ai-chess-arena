# Deployment Guide

## Overview
Run released images using the [README quick start](../README.md#quick-start) or the detailed [5-minute start](#5-minute-start) below. Download `docker-compose.yml` and `env.example` from [GitHub Releases](https://github.com/FJrepo/ai-chess-arena/releases); no source checkout, Make, Java or Node.js is needed.

The **downloaded** `docker-compose.yml` is generated from the repository's `docker-compose.prod.yml`, changing only the two `IMAGE_TAG` expressions to default to that release's exact `vX.Y.Z` tag. Both application images stay pinned even when you downloaded the files through the `latest` release URL. Explicit `IMAGE_TAG` and `IMAGE_NAMESPACE` settings still override those defaults.

In a **source checkout**, `docker-compose.yml` builds the development stack from source; use `docker-compose.prod.yml` to run released images. That repository file continues to require an explicit `IMAGE_TAG`.

## Image Platforms
Both backend and frontend release images are multi-arch OCI indexes for `linux/amd64` and `linux/arm64`, including Apple Silicon and ARM servers. Older release tags retain their original platforms. Docker selects the host's native platform automatically; Stockfish 19 uses the matching checksum-pinned universal binary.

To force a platform for a pull, use `docker pull --platform linux/arm64 ghcr.io/<owner>/chess-backend:<tag>` (likewise for `chess-frontend`). To force the whole Compose stack, set `DOCKER_DEFAULT_PLATFORM=linux/arm64` for both the `docker compose -f docker-compose.prod.yml pull` and `up -d` commands. Use `linux/amd64` to force x86-64 instead. Running a foreign platform requires QEMU/binfmt support, which Docker Desktop includes; native deployment needs no emulation.

The Maven and Node build stages run on the builder's native platform. Only runtime stages target each architecture. This distribution change requires pulling the new release and recreating containers; it adds no migration or backfill and leaves existing persisted data unchanged.

## Prerequisites
- Docker Desktop on macOS or Windows (including Apple Silicon), or Docker Engine on Linux with Docker Compose support; the included Engine must be 28.0+
- An OpenRouter account for model play; connecting or pasting a key can wait until after startup
- Access to public GHCR images for this repository

Make is optional and used only by source-checkout helpers such as `make setup` and `make doctor`.

Docker Engine releases older than 28.0.0 can allow hosts on the same layer-2 network to reach ports published to `127.0.0.1`. Upgrade the engine before relying on this project's localhost-only boundary; otherwise, enforce equivalent host-firewall isolation before starting the stack.

## Bundled Engine Note
- The released backend image bundles Stockfish 19 for move evaluation.
- The backend Dockerfile selects the official universal x86-64 or ARM64 asset using `TARGETARCH`. The release gate checks Stockfish license files and labels on both platforms and runs the ARM64 binary's UCI handshake under emulation; the full stack smoke test runs on amd64.
- Stockfish source/license details are documented in `THIRD_PARTY_NOTICES.md`.
- The backend image includes Stockfish notice/license files under `/usr/share/licenses/stockfish/`.
- If Stockfish is unavailable, the backend stays up and the frontend disables the eval bar. After process exit or EOF, the failed evaluation has no score and the next queued evaluation gets one restart attempt. Requests arriving during the replacement handshake wait in the queue and run after recovery. A failed restart requires restarting the backend to restore evaluation. See [evaluation behavior](how-it-works.md#move-quality).
- Engine readiness requires a completed UCI handshake. Configure `stockfish.handshake-timeout` / `STOCKFISH_HANDSHAKE_TIMEOUT` (default `PT10S`) and `stockfish.evaluation-timeout` / `STOCKFISH_EVALUATION_TIMEOUT` (default `PT30S`) as positive durations. Either timeout stops the engine, drains current and queued requests with no score, and requires backend restart to restore evaluation. Shutdown cancels stalled I/O. Cleanup forcibly terminates owned descendants before the engine, allowing up to one second for the engine to reap them; stream closing runs on a daemon thread without blocking shutdown or evaluation callers. No migration or backfill is needed; stored evaluations remain unchanged.
- For native development, `make install-stockfish` installs checksum-verified Stockfish 19 for Linux (x86-64 or ARM64) or macOS (universal). An explicit `--version sf_18` selection remains supported on the previously supported platforms.
- Rebuild/recreate the backend to use the upgraded binary. No migration or backfill is needed; existing evaluations remain unchanged.

## Prompt caching configuration

Prompt caching is enabled by default. The backend uses an append-only history per side, retained across human-turn waits. Anthropic and Google models (including latest aliases) receive explicit breakpoints only with a valid cache-write price in the cached catalog. A cache-control rejection retries once with plain messages and disables explicit caching for that model ID until restart. See [window limits, provider behavior and pricing trade-offs](how-it-works.md#prompt-caching).

Configure the backend with `OPENROUTER_PROMPT_CACHING` (default `true`), `OPENROUTER_PROMPT_CACHE_MAX_PRIOR_MESSAGES` (default `12`) and `OPENROUTER_PROMPT_CACHE_RETAINED_MESSAGES` (default `4`). The limits count prior messages only, excluding system/current-turn/retry messages. Both must be non-negative and retained must not exceed maximum. Disabling caching restores the legacy six-prior-message sliding window in both requests and retained human-game history, with plain messages; it does not disable a provider's automatic caching.

If a plain retry after a cache-control rejection exhausts the original deadline, the game pauses for a provider problem without a timeout loss. Committed tournament deletion and terminal game transitions stop loops and discard their retained conversations; rolled-back changes keep them intact. Rebuild/recreate the backend to apply these fixes. No migration or backfill is needed; existing persisted history is unchanged.

Deletion flags the owning loop before eviction and prevents a concurrent human-turn handoff from restoring its history. New retention checks reject missing/inactive games and terminal tournaments. Every ten seconds, the existing scheduler also sweeps retained conversations against committed state, including when auto-run is off; an empty cache needs no database query. Rebuild/recreate the backend to apply this cleanup fix; persisted data stays unchanged.

For native runs, export these variables in the backend process environment. For containers, pass them through an operator-owned Compose override under `services.backend.environment`; adding them only to the root `.env` does not forward them to the backend. Deploy this change by rebuilding/recreating the backend; configuration changes require recreating it with the new environment. No frontend rebuild, migration, backfill or bundled-software update is needed. Existing persisted moves, attempts, counters and spend stay unchanged. Restarts/resumes and prompt-affecting settings changes start a fresh cache prefix; model minimum lengths, expiry and routing still determine actual hits.

## Creation-time brackets migration (V22)

Back up before upgrading. Rebuild and recreate backend and frontend together. Flyway `V22__creation_bracket_intent.sql` adds nullable `tournaments.creation_draw_mode`, preserving the original create intent so a retry with the same `clientRequestId` can finish generation after an interrupted request. Existing tournaments receive null and keep their current draft/bracket state; no generation backfill runs. Games, seeds, results and spend remain unchanged. Restore the pre-upgrade database backup to roll back. See [Bracket](how-it-works.md#bracket) for explicit generation opt-in, draft defaults and recovery.

## Banter level migration (V21)

Back up before upgrading. Flyway `V21__banter_level.sql` adds nullable, checked `tournaments.banter_level` on backend startup. Existing tournaments keep Original (`null`), preserving their prompt texts, versions and hashes. Existing moves, games, results and spend remain unchanged; no backfill is needed.

Rebuild/recreate both backend and frontend to deploy the setting, prompt variants and controls. Backend restart discards in-memory conversations as usual. Restore the pre-upgrade backup to roll back. No bundled third-party software changes. See [Chat mode and banter](how-it-works.md#chat-mode) for live-change and legacy-template behavior.

## Auto-run migration (V20)

Back up before upgrading. Flyway `V20__tournament_auto_run.sql` runs on backend startup and adds tournament run state (`OFF` for every existing tournament), concurrency (default 2), optional budget (null/no cap), and pause reason. Existing game positions, attempts, results and spend remain unchanged; no historical backfill is needed. No bundled third-party software changes.

Rebuild/recreate both backend and frontend to deploy the scheduler, API and bracket controls. Previously running auto-runs pause on every backend restart with **Backend restarted**; interrupted games are paused by GameRecovery. Resume the run from the tournament page, then resume its paused games explicitly. A 10-second safety tick recovers missed scheduling notifications. This remains the single-backend deployment model: row locks serialize starts, but game-loop ownership and startup recovery belong to that backend JVM.

Budget checks use reported tournament spend before each model request and after each recorded attempt. Terminal answers complete and advance before their attempt pauses unfinished games at the cap, including resignation and eligible stored-evaluation adjudication; cancellation remains final. Deploy this fix by rebuilding/recreating both application services. No additional migration or backfill is needed; existing persisted games remain unchanged, including any terminal positions paused by an older version, which still need manual resume after raising/removing the cap. In-flight answers can still add spend after the cap pauses a run. See [Auto-run](how-it-works.md#auto-run) for manual-play boundaries, human games, concurrency reductions and the API contract. Restore the pre-upgrade database backup when rolling back; older versions do not enforce run controls.

## Opening variety migration (V19)

Back up before upgrading. Flyway V19 adds `tournaments.opening_mode` (Standard by default), its optional Fixed `opening_id`, game opening metadata and `moves.is_book` (false for all existing rows). Existing game positions, moves, results and spend are unchanged; no backfill or external opening-data download is required. The authored book is bundled in the backend resources.

Rebuild/recreate the backend and frontend when deploying this feature. Opening settings affect newly created games only, including bracket placeholders; a running or waiting game retains its original history. See [opening variety and series fairness](how-it-works.md#opening-variety). Restore the pre-upgrade backup to roll back: older versions cannot exclude book rows from model metrics. No container image dependency or third-party software is added by this feature.

## First-Time Setup

### 5-minute start

You need Docker Desktop on macOS or Windows (including Apple Silicon), or Docker Engine on Linux with Compose, and an OpenRouter account for model play. The Docker Engine must be 28.0+ for localhost isolation.

On macOS/Linux, download the two assets into a new directory and create the credential file with mode `0600` before editing:

```bash
mkdir chess-arena
cd chess-arena
curl -fL -o docker-compose.yml https://github.com/FJrepo/ai-chess-arena/releases/latest/download/docker-compose.yml
curl -fL -o env.example https://github.com/FJrepo/ai-chess-arena/releases/latest/download/env.example
install -m 600 env.example .env
```

#### Windows (PowerShell)

Keep `.env` in your user profile; don't share it. In PowerShell:

```powershell
Set-Location $env:USERPROFILE
mkdir chess-arena
cd chess-arena
curl.exe -fL -o docker-compose.yml https://github.com/FJrepo/ai-chess-arena/releases/latest/download/docker-compose.yml
curl.exe -fL -o env.example https://github.com/FJrepo/ai-chess-arena/releases/latest/download/env.example
Copy-Item env.example .env
```

#### Configure and start

Leave OpenRouter credentials empty and connect in **System → OpenRouter** after opening the app. The [manual environment or file route](#secrets) remains available. New installs need no database password: it is generated on first start. **Upgrades must keep their existing `DB_PASSWORD` unchanged.**

All other production variables are optional with release assets:

| Variable | Default / behavior |
|---|---|
| `IMAGE_NAMESPACE` | `fjrepo`; change for images published by a fork |
| `IMAGE_TAG` | Leave empty to use the downloaded release's tag; set an exact version to override it |
| `DB_PASSWORD` | Optional for new installs; existing installs must retain their stored password |
| `DB_NAME` | `chess_tournament`; keep unchanged for existing volumes |
| `DB_USER` | `chess`; keep unchanged for existing volumes |
| `FRONTEND_PORT` | `4200`; change if that host port is already in use |
| `APP_ORIGIN` | Optional HTTP origin for the logged setup link; does not enable TLS proxy support |
| `ALLOWED_HOSTS` | Empty; localhost names are always allowed, see [host names](#host-names-allowed_hosts) |

Start the stack; Docker pulls any missing images automatically:

```bash
docker compose up -d --wait
docker compose ps
docker compose logs --tail=120 backend frontend
```

Open **http://localhost:4200** (or your `FRONTEND_PORT`), connect OpenRouter in System, then create a tournament and start the first game. The API is under `/api`; only the frontend is exposed on loopback.

Pin a version: replace `latest/download` with `download/vX.Y.Z` in both download URLs. Do not overwrite an existing `.env` during upgrades.

### Run from a source checkout

Contributors can run released images from a source checkout with Git, Make and the [prerequisites](#prerequisites) above:

```bash
git clone https://github.com/FJrepo/ai-chess-arena.git
cd ai-chess-arena
make setup
```

`make setup` creates `.env` from `.env.example` with mode `0600`, or preserves its contents and tightens permissions. `make doctor` checks local prerequisites. Edit `.env`:

```bash
IMAGE_NAMESPACE=fjrepo         # published images; use your own namespace for a fork
IMAGE_TAG=v1.6.0               # choose an exact tag from GitHub Releases
OPENROUTER_API_KEY=sk-or-...
DB_PASSWORD=replace-with-a-long-random-password  # required by v1.6.0; keep existing passwords unchanged
```

Generated secrets require **v1.7.0+ images**. With v1.6.0 or older images, set `DB_PASSWORD` explicitly even for a new install, as above. For a new install using v1.7.0 or newer, you may omit it. Existing installs must always keep their original `DB_PASSWORD` unchanged.

Start the released images using the production Compose file:

```bash
docker compose -f docker-compose.prod.yml pull
docker compose -f docker-compose.prod.yml up -d --wait
docker compose -f docker-compose.prod.yml ps
```

Open **http://localhost:4200**. To build the application from source instead, see [Development](../README.md#development).

### Operating commands

The operating commands below use the source checkout's `-f docker-compose.prod.yml`. **With downloaded release assets, substitute `-f docker-compose.yml`** (or omit `-f` if no other Compose files are present). Keep the same installation directory and Compose project name when upgrading so the stack continues to use its existing database volume.

## Default Endpoints
- Frontend: `http://localhost:${FRONTEND_PORT:-4200}`
- Backend API: `http://localhost:${FRONTEND_PORT:-4200}/api` (proxied by the frontend)

The backend and database publish no host ports in either released-image file. On the supported Docker Engine 28.0+ baseline, the frontend port binds to `127.0.0.1` and is reachable only from the Docker host. Setting `ALLOWED_HOSTS` does not change that binding. Set `FRONTEND_PORT` in `.env` and recreate the frontend to use a different host port.

### Direct backend access (debugging)
Create `docker-compose.debug.yml`:

```yaml
services:
  backend:
    ports:
      - "127.0.0.1:8081:8080"
```

and pass both files explicitly (an override file is only picked up automatically without `-f`):

```bash
docker compose -f docker-compose.prod.yml -f docker-compose.debug.yml up -d backend
curl http://127.0.0.1:8081/q/health/ready
```

The frontend image proxies `/api` and `/ws` to the backend service on the internal Compose network, so no separate frontend API URL configuration is required in the default deployment layout.

The source-build Compose file also binds PostgreSQL, backend, and frontend ports to loopback. Private-network or public access is not a supported default. LAN key management has a browser-claim gate, but it does not secure the other application routes. Keep the supported loopback boundary. TLS-terminating reverse proxies are not supported: the backend sees HTTP and rejects HTTPS browser origins with 403; `APP_ORIGIN` does not change CORS or enable forwarding trust.

### Host names (`ALLOWED_HOSTS`)
The frontend answers only to the host names `localhost`, `127.0.0.1` and `[::1]`, on any port. Requests for any other name (static files, `/api` and `/ws` alike) get `421 Misdirected Request`. This blocks DNS rebinding: a web page whose domain is made to resolve to `127.0.0.1` cannot use the app as a same-origin client, for example to start games that spend OpenRouter credits.

If you have widened the frontend's bind address yourself (still unsupported, see above) and open the UI as `http://chess-server:4200` or `http://192.168.1.20:4200`, add those names to `.env`:

```bash
ALLOWED_HOSTS="chess-server 192.168.1.20"
```

Entries are space-separated, without scheme or port: host names with non-empty dot-separated labels of letters, digits, hyphens or underscores (for example `chess_server`) and IPv4 addresses, bracketed IPv6 addresses in hex-group notation (`[fd00::1]`), or a wildcard at the start or end of a name (`*.lan`, `chess.*`). The frontend container refuses to start on anything else, including `*` on its own, a wildcard in the middle (`foo.*.lan`) and nginx's `.example.lan` shorthand. A request whose `Host` header names a different host than its request line (an absolute-form request such as `GET http://localhost/api/... HTTP/1.1` with a foreign `Host`) also gets 421. Loopback names stay allowed. Recreate the frontend afterwards (`docker compose -f docker-compose.prod.yml up -d`). The source-build `docker-compose.yml` reads the same variable.

nginx enforces this allowlist for every route. The backend also checks it for credential endpoints and validates their mutation Origin against the request authority, without trusting forwarded headers. Keep the backend port private: other API routes still rely on the frontend/network boundary. Recreate both services after changing the allowlist. Native development must set the same `ALLOWED_HOSTS` for its backend.

## Instance claim and threat model

The default is **loopback only**, for one operator, with no user accounts. On first app visit the backend sets a random HttpOnly, SameSite=Lax browser cookie and persists only its SHA-256 hash in `instance_claim`; no secret is stored in that claim file. In local-only mode, loopback browsers are trusted to manage the instance. The host allowlist blocks browser DNS rebinding, and credential mutations also require a matching Origin. This does not protect against untrusted processes or users on the same machine who can read local files or forge HTTP requests.

Adding any non-loopback entry to `ALLOWED_HOSTS` enables the LAN claim requirement, even when visiting localhost. On an unclaimed installation the backend prints **Claim this instance: `http://<allowed-host>:<port>/setup?token=…`**. It uses the first concrete non-loopback allowed host and `FRONTEND_PORT` (otherwise localhost); optionally set `APP_ORIGIN=http://chess-server:4200` to override the logged HTTP host/port. This only changes the printed link: its host must still be allowed and the address must serve the app. It does not configure CORS or support TLS-terminating proxies. Open the ready-made link in the browser that should manage credentials. The frontend removes the token from history and posts it to claim that browser. The token is random, stored privately in `setup_token` until redeemed, and survives unclaimed restarts. Successful claiming deletes it and persists the browser hash. Replays fail. Previously claimed local browsers remain authorized when LAN hosts are added; other browsers need the operator reset procedure below.

Treat the setup link like a password. Access to backend logs permits claiming an unclaimed instance. Keep logs private and keep the app on loopback; do not expose the claim flow on untrusted networks. nginx suppresses access logs for `/setup` and `/connect/callback`; the frontend removes their query strings immediately and sends no referrer. Forwarded headers are deliberately not trusted for callback origins or authorization. TLS-terminating reverse proxies are not supported.

LAN browsers without the claim can read masked status but cannot connect, paste, delete or test keys. This gate **does not authorize other tournament/game API actions**; anyone with network access can still view tournament data and invoke those APIs. A claimed cookie is a browser credential, not an account or a general login. The supported deployment remains one operator on loopback; shared-network and public internet exposure remain unsupported.

If the claiming browser loses its cookie, an operator must stop only their installation, remove `instance_claim` (and any stale `setup_token`) from its secrets volume, and restart the backend to obtain a new setup link. Preserve `db_password`, `instance_key`, `openrouter_key` and the database volume. Resetting the claim does not revoke an existing OpenRouter key or change stored games. In source development the same filenames live in `SECRETS_DIR` / `instance_config`. Clearing cookies on a LAN installation without this reset cannot grant access.

## Secrets

Production Compose runs `secrets-init` (`busybox:1.37.0`) before the database and backend. It generates `db_password` and `instance_key` in the named `secrets_prod` volume, each from 32 random bytes encoded as 64 hexadecimal characters. Atomic publication never overwrites existing files; empty files, directories or symlinks fail initialization. Restarts and container recreation retain both secrets. The directory is mode `0700`; files are `0600`, owned by backend UID/GID `10001:10001`. The PostgreSQL Alpine entrypoint starts as root, reads the password, then switches to postgres UID/GID `70:70`; do not override its container user. The database mounts the volume read-only; the backend mounts it writable to atomically publish UI credentials and claim state. `INSTANCE_KEY_FILE=/run/secrets/instance_key` reserves the key for a later release; it is not used for encryption or signing yet.

Database precedence is **non-empty explicit env value > `*_FILE` > generated default**. OpenRouter uses Quarkus config precedence: **system property (`-Dopenrouter.api-key=…`) > non-empty `OPENROUTER_API_KEY` > `OPENROUTER_API_KEY_FILE` > UI-managed `openrouter_key` > none**. Direct property values retain the `env` status source label. A losing file source is never read, even if its path is invalid. `DB_PASSWORD` and `OPENROUTER_API_KEY` continue to work unchanged; empty env values permit file fallback. File contents are UTF-8 and trimmed; an explicitly configured unreadable or empty file fails startup with a sanitized error. When `DB_PASSWORD_FILE` is unset, Compose selects the generated default `/run/secrets/db_password`. There is no generated OpenRouter key. Native backend runs can also set either file variable; they must provide their own files/DB credentials. Direct Quarkus property overrides remain supported. Environment changes require recreating the backend. Winning OpenRouter file content and UI key changes are read dynamically on the next request; the database password still requires the existing rotation procedure.

After a backend restart or effective-key change, validation returns to unknown; an existing env/file/UI key still displays as connected, with no warning until an explicit test reports an invalid key. **Test key** updates the last validation without running a game. A provider outage leaves validity unknown. Rebuild/recreate the frontend to apply this display correction; passive backend DTOs retain their validation-only legacy booleans. No migration or backfill is required, and persisted data is unchanged.

For PostgreSQL, Compose selects the same path via `POSTGRES_PASSWORD_FILE`. If `DB_PASSWORD` is non-empty, the wrapper removes `POSTGRES_PASSWORD_FILE` before invoking the official entrypoint, so the explicit password wins for both services. **Do not remove `DB_PASSWORD` from an existing `.env`.** PostgreSQL never changes the stored role password merely because a file or environment value changes. The newly generated file is unused for DB authentication on such upgrades. Retain the existing password, project name, DB name/user and database volume. To rotate credentials, use the existing [password rotation steps](#upgrading-volumes-created-before-db_password-was-required); do not delete the secrets volume to rotate them.

To keep the OpenRouter key out of container environment configuration, create a private UTF-8 key file readable by UID 10001 (mode `0600` with that owner on Linux), leave `OPENROUTER_API_KEY` empty, set `OPENROUTER_API_KEY_FILE=/run/openrouter/api_key` in `.env`, and add an operator-owned override:

```yaml
services:
  backend:
    volumes:
      - ./openrouter.key:/run/openrouter/api_key:ro
```

Use `docker compose -f docker-compose.prod.yml -f docker-compose.secrets.yml up -d --wait` for that override (use `docker-compose.yml` for downloaded release assets). Docker bind mounts preserve Linux ownership; Docker Desktop operators should verify readability by the backend user. For a custom DB file, set `DB_PASSWORD_FILE` to its container path in `.env` and mount the file read-only at that path in both db and backend. Use a single-line DB password without surrounding whitespace (a final newline is fine), because the official PostgreSQL image does not trim spaces. Never commit secret files. The backend does not log or export resolved secrets. The official PostgreSQL entrypoint internally uses the password during initialization; `*_FILE` keeps it out of Docker's configured environment.

OpenRouter is optional: Compose and the backend start with no key, and no OpenRouter call is required to initialize the database. Connect or paste a key in System after startup. UI keys are validated before atomic replacement, stored mode `0600` as `/run/secrets/openrouter_key`, and never exported, logged or returned by the API. An operator-managed key disables UI replacement/deletion with HTTP 409. Native development uses `SECRETS_DIR` (default `.local/secrets` relative to the backend working directory); source-build Compose persists `/app/.local/secrets` in `instance_config`. Keep these private and back them up. Release assets include this same init service and default their application images to the release tag; repository Compose still requires `IMAGE_TAG`.

Back up `secrets_prod` securely alongside the database, preserving numeric ownership and modes. Never publish its contents or include them in diagnostics. Losing the generated DB password while retaining `pgdata_prod` requires restoring the secret backup or deliberately resetting the role password; a newly generated password cannot unlock the old database. `docker compose down` preserves both volumes; `down -v` destroys both the database and secrets. No schema migration or backfill is introduced. Existing persisted games and credentials remain unchanged when upgrading correctly.

## Upgrading Volumes Created Before `DB_PASSWORD` Was Required

Older production volumes may have been initialized with the former fallback database password `chess`. PostgreSQL applies `POSTGRES_PASSWORD` only when it initializes an empty data directory, so changing `.env` alone does not change the password stored in an existing volume.

Rotate the role password once before selecting a new `DB_PASSWORD`. If the new Compose file is already checked out, first set `DB_PASSWORD=chess` temporarily in `.env` only for a volume known to have used that former fallback, so Compose can address the running database during the rotation.

1. While the old database container is running, open `psql` as the configured database user:

```bash
docker compose -f docker-compose.prod.yml exec db \
  sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
```

2. At the `psql` prompt, run `\password`. Enter a new strong password twice, then run `\q`. This avoids placing the password in shell history.
3. Set `DB_PASSWORD` in `.env` to exactly that new password. If it contains `$`, single-quote the value so Compose does not interpolate it; escape any embedded single quote as `\'`.
4. Pull the intended images and recreate the stack using the normal upgrade steps below. Recreating the containers does not remove `pgdata_prod`.

If the backend is already failing to connect, start only the database with the temporary compatibility value, perform the rotation above, and immediately replace that value before recreating the stack. Never use the former fallback for a new deployment.

## Upgrading To A New Release
0. Back up the database (see [Backups and rollback](#backups-and-rollback)).
1. Update `IMAGE_TAG` in `.env`
   - recommended: move from one pinned tag to another pinned tag
   - release-asset installs can instead download the new release's `docker-compose.yml` into the same directory and leave `IMAGE_TAG` empty; an existing non-empty override takes precedence
   - keep your `.env`, database password and Compose project name; do not replace them with the new template
2. Pull the new images:

```bash
docker compose -f docker-compose.prod.yml pull
```

3. Recreate the services:

```bash
docker compose -f docker-compose.prod.yml up -d
```

4. Confirm startup and migration success:

```bash
docker compose -f docker-compose.prod.yml logs --tail=120 backend
```

Flyway migrations run automatically on backend startup.

## Backups and rollback
Migrations only run forward: there are no down-migrations, and an older backend is not guaranteed to keep a migrated database consistent. To roll back to an older release, restore the database backup taken before the upgrade; the data written since then is lost. Since 1.4 (migration V12, chat modes) this is required: an older backend still starts, but it only writes `trash_talk_enabled` and stores moves without a chat mode, which leaves the data inconsistent when you upgrade again.

The adjudication upgrade runs Flyway `V18__adjudication.sql` on backend startup, adding `tournaments.adjudication_enabled` with default false for new and existing tournaments. Rebuild/recreate the backend and frontend to apply the option, result panel and analytics. Existing results and evaluations remain unchanged; missed-mate and conversion metrics are derived from stored evaluations without backfill. Games with a human never adjudicate.

The prompt caching metrics upgrade adds nullable `move_attempts.cached_prompt_tokens` and `cache_write_tokens` via Flyway V17 on backend startup. Existing rows keep null cache counters and their reported costs; there is no historical cache-usage backfill. Only newly reported provider counters populate the metrics.

Analytics comparisons read one cached catalog price snapshot without waiting for OpenRouter. Empty or expired catalogs trigger a single background refresh; failed catalog refreshes back off for 60 seconds, retaining stale prices when available. After a restart, savings may show n/a until prices are cached and the 15-second comparison cache expires. This catalog handling fix adds no migration or backfill and leaves persisted usage and costs unchanged; rebuild and recreate the backend to apply it.

Back up before every upgrade (the stack keeps running):

```bash
docker compose -f docker-compose.prod.yml exec -T db sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > chess-backup-$(date +%F).dump
```

Roll back: set `IMAGE_TAG` in `.env` back to the previous release, then restore the backup with the backend stopped and start everything again:

```bash
docker compose -f docker-compose.prod.yml stop backend
docker compose -f docker-compose.prod.yml exec -T db sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists' < chess-backup-YYYY-MM-DD.dump
docker compose -f docker-compose.prod.yml pull
docker compose -f docker-compose.prod.yml up -d
```

## Persistence
- Postgres data is stored in the named Docker volume `pgdata_prod`.
- Generated secrets are stored in `secrets_prod`; keep and back up both volumes together.
- Recreating containers does not delete either volume.
- To remove all persisted data intentionally:

```bash
docker compose -f docker-compose.prod.yml down -v
```

Use that only when you explicitly want to destroy the database and its generated secrets.

## Health Checks
Useful commands:

```bash
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml exec backend wget -qO- http://localhost:8080/q/health/live
docker compose -f docker-compose.prod.yml exec backend wget -qO- http://localhost:8080/q/health/ready
```

## Release Publishing Notes
Release metadata explicitly preserves the backend Dockerfile's `org.opencontainers.image.licenses=MIT, GPL-3.0-or-later` label for bundled Stockfish; the repository's MIT licence must not override it. The frontend has no explicit Dockerfile licence label and retains its generated repository metadata. All other generated OCI labels are retained for both images. This applies to newly built release images only; existing images and persisted data are unchanged, with no migration or backfill.

GitHub Release notes come from the tagged version's section in [CHANGELOG.md](../CHANGELOG.md), extracted by `python3 scripts/release_notes.py vX.Y.Z CHANGELOG.md --repository OWNER/REPO`. The workflow supplies the release tag and `GITHUB_REPOSITORY`. The introduction becomes Highlights, Upgrade notes comes first, and other sections retain changelog order. The workflow appends Verification with both image index digests, an Assets subsection linking the two setup files, and a Full Changelog comparison to the closest reachable previous `vX.Y.Z` tag (or the tagged commit history for the first release); it does not generate a PR list. A missing version entry fails tag validation before CI, builds or promotion.

`python3 scripts/release_assets.py vX.Y.Z` writes `release-assets/docker-compose.yml` with both application images defaulting to the validated release tag, plus `release-assets/env.example`, an exact copy of the repository's `.env.example`. Every other Compose byte is preserved. The template is generated and uploaded as `env.example`; release notes link only that canonical filename. Both files are supplied to the same action step that creates the GitHub Release, with missing files treated as errors: release immutability prevents uploading them afterwards. This adds no application migration or backfill and leaves existing installations and stored data unchanged. Setup assets are available from v1.6.0; older releases are unchanged.

The reusable CI workflow includes a Python 3.12 job that installs `scripts/requirements-test.txt` and runs `python -m unittest discover -s scripts -p 'test_*.py'`. Script regressions gate pull requests and releases alongside backend and frontend checks.

Repository-relative Markdown links resolve from the repository root to `https://github.com/OWNER/REPO/blob/vX.Y.Z/…`, preserving query strings and fragments; images use the corresponding `raw` URL. Absolute URLs, anchors and mail links are unchanged. Used reference definitions are collected from the entire changelog and appended to the notes, including definitions within an entry or after older releases. Only the next release heading (`## [`) ends an entry. Fenced code examples are preserved without interpreting their headings, references or links. These changes apply to future release generation; existing releases and persisted application data are unchanged.

- The release workflow runs in this order, and any failure stops it before user-facing tags change:
  1. validates the tag (exact `vX.Y.Z`, commit reachable from `main`, matching changelog entry)
  2. runs the full CI suite on the tagged commit
  3. builds both images for `linux/amd64,linux/arm64` and pushes OCI indexes only as `sha-<12-character-commit>` candidates
  4. verifies both platforms in both indexes, checks backend license files and labels per platform, runs the ARM64 Stockfish UCI handshake under QEMU, and starts the amd64 `docker-compose.prod.yml` stack to check health, the frontend, and the proxied API
  5. copies both verified full indexes to `<tag>` and `latest`, then creates the GitHub Release with changelog notes, index digest lines and both setup assets
- Published tags:
  - `ghcr.io/<owner>/chess-backend:<tag>` and `:latest`
  - `ghcr.io/<owner>/chess-frontend:<tag>` and `:latest`
  - `sha-<12-character-commit>` candidate tags remain as build artifacts; do not deploy them directly
- Public package visibility is recommended for easy third-party pulls.
- After first publish, confirm the package visibility in GitHub:
  - repository/package settings -> set packages to public if you want anonymous pulls

## Maintainer Release Flow
Recommended release sequence:

1. Confirm `main` is in the state you want to ship.
2. Update `CHANGELOG.md`.
3. Commit the changelog and any final release notes adjustments.
4. Create and push a version tag:

```bash
git tag v1.0.0
git push origin v1.0.0
```

5. Watch the `Release` GitHub Actions workflow until all stages succeed: tag validation, CI, candidate builds, smoke test, promotion, and GitHub Release creation.
   If any stage fails, no `<tag>` or `latest` image is published; fix the problem on `main` and release a new patch version.

6. After the first publish for each package, confirm package visibility is public if you want anonymous pulls from GHCR.
7. Verify the published deployment path with:

```bash
docker compose -f docker-compose.prod.yml pull
docker compose -f docker-compose.prod.yml up -d
```

Recommended tagging policy:
- use pinned tags such as `v1.0.0` for real deployments
- keep `latest` as a convenience tag only

## Release Policy
- Release tags use `vX.Y.Z`.
- `main` should stay in a releasable state.
- From `v1.0.0` onward:
  - breaking changes require a major bump
  - backward-compatible features require a minor bump
  - fixes, docs, hardening, and release-pipeline changes generally require a patch bump
- A release is only considered complete when the following are all present and verified:
  - the git tag
  - the GitHub Release page
  - the published GHCR backend image
  - the published GHCR frontend image
- Pinned tags are the recommended deployment target.
- `latest` is published for convenience, not as the primary supported upgrade target.

## Troubleshooting

### Migration V8 reports "Skipping uq_moves_game_ply"
Older versions could store two moves for the same ply when moves were submitted concurrently. Migration V8 then still succeeds but skips the unique index and logs a notice. Current versions prevent new duplicates with a row lock either way. Adjudication, move quality (including missed mates), Conversion and talkativeness count one canonical move per `(game_id, move_number, color)`: the earliest `created_at`, with `id` breaking ties. Later duplicates cannot fill a missing canonical evaluation, create winning positions or extend the ten-ply adjudication window. Move API rows remain available, with null quality on later duplicates; live quality updates use the canonical move and the next canonical ply.

Rebuild and recreate the backend to apply this fix. No migration or backfill is needed; duplicate rows, stored evaluations and completed results remain unchanged. Previously adjudicated results and series advancement are not repaired automatically. To add the index afterwards, list the affected plies, delete the unwanted duplicate rows by `id` (keeping the first by `created_at`, then `id`), then create the index:

```bash
docker compose -f docker-compose.prod.yml exec db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
```

```sql
SELECT game_id, move_number, color, array_agg(id ORDER BY created_at, id) AS move_ids
FROM moves GROUP BY game_id, move_number, color HAVING COUNT(*) > 1;
-- after removing the unwanted rows:
CREATE UNIQUE INDEX IF NOT EXISTS uq_moves_game_ply ON moves (game_id, move_number, color);
```

### Games show "Paused because the server restarted"
Game loops do not survive a backend restart. Games that were in progress are paused at startup with this reason; resume them from the game view.


### GHCR pull fails
Possible causes:
- the package is still private
- `IMAGE_NAMESPACE` is wrong
- `IMAGE_TAG` does not exist

Check:

```bash
docker compose -f docker-compose.prod.yml pull
```

### Backend is unhealthy
Inspect logs:

```bash
docker compose -f docker-compose.prod.yml logs --tail=200 backend
```

Common causes:
- invalid `OPENROUTER_API_KEY`
- database credentials mismatch
- failed Flyway migration
- image tag mismatch across services

### Frontend loads but API calls fail
Inspect backend state first:

```bash
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs --tail=200 backend frontend
```

The default frontend image expects the backend service to be reachable as `backend:8080` within the same Compose project.

### Ports are already in use
Change `FRONTEND_PORT` in `.env` (the released-image stack publishes no other host ports). No CORS change is needed: the frontend proxy forwards the browser's `Host` header including the port, so the backend treats requests from the new address as same-origin (host names other than loopback still need [`ALLOWED_HOSTS`](#host-names-allowed_hosts)). Versions up to 1.3.1 dropped the port, which made every POST/PUT/DELETE and the live-update WebSocket fail with 403 on any port other than 4200.

Then recreate the stack:

```bash
docker compose -f docker-compose.prod.yml up -d
```
