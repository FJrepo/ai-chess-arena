.PHONY: help setup install-stockfish secrets-install secrets-scan up up-attached down restart ps logs logs-backend logs-frontend build doctor test test-backend test-frontend

DOCKER_COMPOSE := docker compose

help:
	@echo "AI Chess Arena - common commands"
	@echo ""
	@echo "Setup:"
	@echo "  make setup         Create .env from .env.example if missing"
	@echo "  make install-stockfish  Install Stockfish 18 for local (non-Docker) backend runs"
	@echo "  make secrets-install    Install git-secrets hooks + OpenRouter key pattern"
	@echo "  make secrets-scan       Scan tracked, untracked, and history for secrets"
	@echo ""
	@echo "Run:"
	@echo "  make up            Build + start stack in detached mode"
	@echo "  make up-attached   Build + start stack with attached logs"
	@echo "  make down          Stop stack"
	@echo "  make restart       Restart stack in detached mode"
	@echo "  make ps            Show service status"
	@echo "  make logs          Tail backend + frontend logs"
	@echo "  make logs-backend  Tail backend logs"
	@echo "  make logs-frontend Tail frontend logs"
	@echo ""
	@echo "Quality:"
	@echo "  make doctor        Check local prerequisites"
	@echo "  make test          Run backend + frontend tests"
	@echo "  make test-backend  Run backend tests only"
	@echo "  make test-frontend Run frontend tests only"

setup:
	@if [ -f .env ]; then \
		echo ".env already exists (keeping current file)"; \
	else \
		cp .env.example .env; \
		echo "Created .env from .env.example"; \
		echo "Set OPENROUTER_API_KEY in .env before running the stack"; \
	fi

install-stockfish:
	@./scripts/install-stockfish.sh

secrets-install:
	@./scripts/setup-git-secrets.sh

secrets-scan:
	@./scripts/scan-git-secrets.sh

up: setup
	$(DOCKER_COMPOSE) up --build -d

up-attached: setup
	$(DOCKER_COMPOSE) up --build

down:
	$(DOCKER_COMPOSE) down

restart: down up

ps:
	$(DOCKER_COMPOSE) ps

logs:
	$(DOCKER_COMPOSE) logs -f backend frontend

logs-backend:
	$(DOCKER_COMPOSE) logs -f backend

logs-frontend:
	$(DOCKER_COMPOSE) logs -f frontend

build:
	$(DOCKER_COMPOSE) build

doctor:
	@command -v docker >/dev/null 2>&1 || { echo "docker not found"; exit 1; }
	@docker --version
	@$(DOCKER_COMPOSE) version
	@if [ -f .env ]; then echo ".env present"; else echo ".env missing (run: make setup)"; fi
	@if command -v git-secrets >/dev/null 2>&1; then \
		echo "git-secrets found: $$(command -v git-secrets)"; \
	else \
		echo "git-secrets missing (install and run: make secrets-install)"; \
	fi
	@if command -v stockfish >/dev/null 2>&1; then \
		echo "stockfish found: $$(command -v stockfish)"; \
	else \
		echo "stockfish missing (run: make install-stockfish for local non-Docker runs)"; \
	fi
	@echo "doctor checks completed"

test: test-backend test-frontend

test-backend:
	cd backend && mvn -B test -DskipITs

test-frontend:
	cd frontend && npm test
