# Makefile - LogisticaPortuaria CR
# Entry point for every developer command. Compose file lives in infra/.

COMPOSE ?= docker compose
COMPOSE_FILE ?= infra/docker-compose.yml
COMPOSE_ENV ?= infra/.env

.DEFAULT_GOAL := help
.PHONY: help env up down restart logs ps build test test-backend test-frontend test-ocr \
        edge-dataset edge-eval seed smoke demo migrations clean status

help: ## Show this help
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'

env: ## Create infra/.env from the example (idempotent)
	@[ -f $(COMPOSE_ENV) ] || cp infra/.env.example $(COMPOSE_ENV)
	@echo "Using $(COMPOSE_ENV)"

up: env ## Start the whole stack (postgres, mosquitto, edge-ocr, backend, frontend)
	$(COMPOSE) -f $(COMPOSE_FILE) up -d --build

down: ## Stop the stack (keeps volumes)
	$(COMPOSE) -f $(COMPOSE_FILE) down

restart: down up ## Restart the stack

ps: ## Show service status
	$(COMPOSE) -f $(COMPOSE_FILE) ps

status: ps ## Alias of ps

logs: ## Tail logs of all services
	$(COMPOSE) -f $(COMPOSE_FILE) logs -f --tail=100

build: ## Rebuild images
	$(COMPOSE) -f $(COMPOSE_FILE) build

seed: ## Apply Flyway migrations and load the demo data
	$(COMPOSE) -f $(COMPOSE_FILE) exec backend ./mvnw -q flyway:info

migrations: ## Show applied Flyway migrations
	$(COMPOSE) -f $(COMPOSE_FILE) exec backend ./mvnw -q flyway:info

test: test-backend test-frontend test-ocr ## Run all unit test suites

test-backend: ## Backend unit tests (JaCoCo >= 70% on gate packages)
	cd backend && ./mvnw -q verify

test-frontend: ## Frontend unit tests (Vitest)
	cd frontend && npm run test

test-ocr: ## Edge OCR unit tests (pytest)
	cd edge-ocr && python -m pytest -q

edge-dataset: ## Generate the synthetic OCR dataset (240 images, fixed seed)
	cd edge-ocr && python tools/make_synthetic_dataset.py --output data/synthetic --count 240 --seed 20260222

edge-eval: edge-dataset ## Evaluate the edge OCR (MODE=corrupt|live|preprocess)
	cd edge-ocr && python tools/evaluate.py --dataset data/synthetic --mode $(or $(MODE),corrupt) \
		--out ../docs/experiments/EXP-002-ocr-evaluation.json

smoke: ## Fast smoke test of the running stack
	bash tests/e2e/smoke.sh

demo: up ## Start the stack and run the simulated operating day
	bash simulators/run_demo.sh

clean: ## Remove containers, networks and volumes
	$(COMPOSE) -f $(COMPOSE_FILE) down -v --remove-orphans
