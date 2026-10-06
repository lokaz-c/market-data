# Common tasks. Each target is a thin wrapper so the commands are easy to read and run by hand.
.PHONY: up down logs test test-backend test-frontend explain loadtest coldstart backfill fixtures

up:  ## Build and start PostgreSQL + the app; synthetic demo data if no Alpaca keys are set
	docker compose up --build -d
	@echo "Chart explorer: http://localhost:8080   API docs: http://localhost:8080/docs"

down:
	docker compose down

logs:
	docker compose logs -f app

test: test-backend test-frontend

test-backend:  ## Unit tests + Testcontainers/WireMock integration tests (needs Docker)
	./mvnw -B verify

test-frontend:
	cd web && npm ci && npm run lint && npm run typecheck && npm test

explain:  ## EXPLAIN (ANALYZE, BUFFERS) before/after indexing on 500 synthetic symbols -> docs/explain.md
	python3 scripts/explain.py

loadtest:  ## k6 against the compose stack with 500 synthetic symbols -> docs/loadtest.md
	scripts/loadtest.sh

coldstart:  ## Start-up time at 0.1 CPU / 512 MB with and without the AOT cache -> docs/coldstart.md
	scripts/coldstart.sh

# make backfill FROM=2016-01-01 [TO=2026-10-02] [SYMBOLS=AAPL,MSFT]  (needs Alpaca keys in .env)
backfill:
	docker compose run --rm app --spring.profiles.active=backfill --from=$(FROM) \
		$(if $(TO),--to=$(TO)) $(if $(SYMBOLS),--symbols=$(SYMBOLS))

fixtures:  ## Record real Alpaca responses for SplitAdjustedViewIT (needs keys)
	scripts/record-fixtures.sh
