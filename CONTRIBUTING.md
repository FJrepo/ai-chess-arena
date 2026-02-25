# Contributing to AI Chess Arena

Thanks for contributing. This file defines the minimum workflow for changes.

## Prerequisites
- Java 21+
- Maven 3.9+
- Node.js 22+ and npm
- Docker + Docker Compose
- OpenRouter API key

## Local Setup
### Docker (recommended)
```bash
cp .env.example .env
# set OPENROUTER_API_KEY in .env

docker compose up --build
```

### Run without Docker
```bash
# backend
cd backend
mvn quarkus:dev

# frontend (new terminal)
cd frontend
npm ci
npm start
```

## Test and Build Commands
```bash
# backend tests
cd backend
mvn test -DskipITs

# frontend build (CI-aligned)
cd frontend
npm run build -- --configuration development
```

## Commit and PR Guidelines
- Use clear, scoped commit messages.
- Keep changes focused; avoid mixing unrelated refactors.
- Add/update docs when behavior changes.
- Do not commit secrets (`.env`, tokens, keys).

### Pull Requests
1. Create a branch from `main` (or your default branch).
2. Ensure backend tests pass and frontend build succeeds.
3. Fill out the PR template completely.
4. Link related issues where possible.

## Issue Reporting
- Use the Bug Report template for defects.
- Use the Feature Request template for enhancements.
- Include repro steps, expected behavior, and environment details.

## Security and Secrets
`git-secrets` is used as a local guard once git is initialized:
```bash
make secrets-install
make secrets-scan
```
