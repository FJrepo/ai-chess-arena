# Deployment Guide

## Overview
This project supports two Docker-based run paths:

- `docker-compose.yml`
  - local development
  - builds images from source on your machine
- `docker-compose.prod.yml`
  - released deployment
  - pulls prebuilt frontend/backend images from GHCR

For other users, `docker-compose.prod.yml` is the recommended path.

## Prerequisites
- Docker Engine with Docker Compose support
- An OpenRouter API key
- Access to public GHCR images for this repository

## Bundled Engine Note
- The released backend image bundles Stockfish 18 for move evaluation.
- The bundled binary currently targets `amd64` / AVX2-class environments.
- Stockfish source/license details are documented in `THIRD_PARTY_NOTICES.md`.
- The backend image includes Stockfish notice/license files under `/usr/share/licenses/stockfish/`.

## First-Time Setup
1. Copy the environment template:

```bash
cp .env.example .env
```

2. Edit `.env` and set:
- `IMAGE_NAMESPACE`
  - your GitHub user or org that owns the published GHCR images
- `IMAGE_TAG`
  - recommended: a pinned version such as `v0.2.0`
- `OPENROUTER_API_KEY`
- `DB_PASSWORD`

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
- Backend API: `http://localhost:${BACKEND_PORT:-8081}`

The frontend image proxies `/api` and `/ws` to the backend service on the internal Compose network, so no separate frontend API URL configuration is required in the default deployment layout.

## Upgrading To A New Release
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
curl http://localhost:${BACKEND_PORT:-8081}/q/health/live
curl http://localhost:${BACKEND_PORT:-8081}/q/health/ready
```

## Release Publishing Notes
- The release workflow publishes:
  - `ghcr.io/<owner>/chess-backend:<tag>`
  - `ghcr.io/<owner>/chess-backend:latest`
  - `ghcr.io/<owner>/chess-frontend:<tag>`
  - `ghcr.io/<owner>/chess-frontend:latest`
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
git tag v0.2.0
git push origin v0.2.0
```

5. Watch the `Release` GitHub Actions workflow until:
- backend image push succeeds
- frontend image push succeeds
- GitHub Release creation succeeds

6. After the first publish for each package, confirm package visibility is public if you want anonymous pulls from GHCR.
7. Verify the published deployment path with:

```bash
docker compose -f docker-compose.prod.yml pull
docker compose -f docker-compose.prod.yml up -d
```

Recommended tagging policy:
- use pinned tags such as `v0.2.0` for real deployments
- keep `latest` as a convenience tag only

## Troubleshooting

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
Change these values in `.env`:
- `FRONTEND_PORT`
- `BACKEND_PORT`

Then recreate the stack:

```bash
docker compose -f docker-compose.prod.yml up -d
```
