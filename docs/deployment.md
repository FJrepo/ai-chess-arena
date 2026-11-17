# Deployment Guide

## Overview
This project supports two Docker-based run paths:

- `docker-compose.yml`
  - local development
  - builds images from source on your machine
- `docker-compose.prod.yml`
  - released deployment
  - pulls prebuilt frontend/backend images from GHCR

For normal releases, `docker-compose.prod.yml` is the supported deployment path.

## Image Platforms
Starting with the next release, both backend and frontend images are multi-arch OCI indexes for `linux/amd64` and `linux/arm64`, including Apple Silicon and ARM servers. Older release tags retain their original platforms. Docker selects the host's native platform automatically; Stockfish 19 uses the matching checksum-pinned universal binary.

To force a platform for a pull, use `docker pull --platform linux/arm64 ghcr.io/<owner>/chess-backend:<tag>` (likewise for `chess-frontend`). To force the whole Compose stack, set `DOCKER_DEFAULT_PLATFORM=linux/arm64` for both the `docker compose -f docker-compose.prod.yml pull` and `up -d` commands. Use `linux/amd64` to force x86-64 instead. Running a foreign platform requires QEMU/binfmt support, which Docker Desktop includes; native deployment needs no emulation.

The Maven and Node build stages run on the builder's native platform. Only runtime stages target each architecture. This distribution change requires pulling the new release and recreating containers; it adds no migration or backfill and leaves existing persisted data unchanged.

## Prerequisites
- Docker Engine 28.0+ with Docker Compose support, or a Docker Desktop release that includes Engine 28.0+
- Make for the `make setup` helper
- An OpenRouter API key
- Access to public GHCR images for this repository

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

## First-Time Setup
1. Create the environment file with owner-only permissions:

```bash
make setup
```

`make setup` creates `.env` with mode `0600`. If the file already exists, it keeps the contents and tightens the permissions to `0600`.

2. Edit `.env` and set:
- `IMAGE_NAMESPACE`
  - your GitHub user or org that owns the published GHCR images
- `IMAGE_TAG`
  - required; a release version such as `v1.1.0` (see GitHub Releases)
  - `latest` works but is a convenience tag; pinned versions give predictable upgrades
- `OPENROUTER_API_KEY`
- `DB_PASSWORD`
  - required; production Compose does not provide a fallback password
  - single-quote a value containing `$` (for example, `DB_PASSWORD='literal$value'`) so Compose treats it literally

3. Pull the published images:

```bash
docker compose -f docker-compose.prod.yml pull
```

4. Start the stack:

```bash
docker compose -f docker-compose.prod.yml up -d
```

5. Verify container state:

```bash
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs --tail=120 backend frontend
```

## Default Endpoints
- Frontend: `http://localhost:${FRONTEND_PORT:-4200}`
- Backend API: `http://localhost:${FRONTEND_PORT:-4200}/api` (proxied by the frontend)

The backend publishes no host port in `docker-compose.prod.yml`. On the supported Docker Engine 28.0+ baseline, the frontend port binds to `127.0.0.1` and is reachable only from the Docker host.

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
``` The frontend image proxies `/api` and `/ws` to the backend service on the internal Compose network, so no separate frontend API URL configuration is required in the default deployment layout.

The source-build Compose file also binds PostgreSQL, backend, and frontend ports to loopback. Private-network or public access is not a supported default: widening a bind address requires an operator-provided authentication boundary, TLS, authorization for mutations, and appropriate firewall/rate-limit controls.

### Host names (`ALLOWED_HOSTS`)
The frontend answers only to the host names `localhost`, `127.0.0.1` and `[::1]`, on any port. Requests for any other name (static files, `/api` and `/ws` alike) get `421 Misdirected Request`. This blocks DNS rebinding: a web page whose domain is made to resolve to `127.0.0.1` cannot use the app as a same-origin client, for example to start games that spend OpenRouter credits.

If you have widened the frontend's bind address yourself (still unsupported, see above) and open the UI as `http://chess-server:4200` or `http://192.168.1.20:4200`, add those names to `.env`:

```bash
ALLOWED_HOSTS="chess-server 192.168.1.20"
```

Entries are space-separated, without scheme or port: host names and IPv4 addresses, bracketed IPv6 addresses in hex-group notation (`[fd00::1]`), or a wildcard at the start or end of a name (`*.lan`, `chess.*`). The frontend container refuses to start on anything else, including `*` on its own, a wildcard in the middle (`foo.*.lan`) and nginx's `.example.lan` shorthand. A request whose `Host` header names a different host than its request line (an absolute-form request such as `GET http://localhost/api/... HTTP/1.1` with a foreign `Host`) also gets 421. Loopback names stay allowed. Recreate the frontend afterwards (`docker compose -f docker-compose.prod.yml up -d`). The source-build `docker-compose.yml` reads the same variable.

The allowlist lives in the frontend's nginx. The backend's own port (the source-build stack's `127.0.0.1:8081`, the debug override above, `mvn quarkus:dev`) does not check host names, so publish it only while you need it.

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
- Recreating containers does not delete the DB volume.
- To remove all persisted data intentionally:

```bash
docker compose -f docker-compose.prod.yml down -v
```

Use that only when you explicitly want to destroy the database.

## Health Checks
Useful commands:

```bash
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml exec backend wget -qO- http://localhost:8080/q/health/live
docker compose -f docker-compose.prod.yml exec backend wget -qO- http://localhost:8080/q/health/ready
```

## Release Publishing Notes
GitHub Release notes come from the tagged version's section in [CHANGELOG.md](../CHANGELOG.md), extracted by `python3 scripts/release_notes.py vX.Y.Z CHANGELOG.md`. The introduction becomes Highlights, Upgrade notes comes first, and other sections retain changelog order. The workflow appends Verification with both image index digests and a Full Changelog comparison to the closest reachable previous `vX.Y.Z` tag (or the tagged commit history for the first release); it does not generate a PR list. A missing version entry fails tag validation before CI, builds or promotion.

- The release workflow runs in this order, and any failure stops it before user-facing tags change:
  1. validates the tag (exact `vX.Y.Z`, commit reachable from `main`, matching changelog entry)
  2. runs the full CI suite on the tagged commit
  3. builds both images for `linux/amd64,linux/arm64` and pushes OCI indexes only as `sha-<12-character-commit>` candidates
  4. verifies both platforms in both indexes, checks backend license files and labels per platform, runs the ARM64 Stockfish UCI handshake under QEMU, and starts the amd64 `docker-compose.prod.yml` stack to check health, the frontend, and the proxied API
  5. copies both verified full indexes to `<tag>` and `latest`, then creates the GitHub Release with changelog notes and index digest lines
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
