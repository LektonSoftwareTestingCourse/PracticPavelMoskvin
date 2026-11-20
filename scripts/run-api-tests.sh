#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

if [[ ! -f .env ]]; then
  cp .env.example .env
fi

# Other transaction tests use unique X-Forwarded-For values, so this low limit
# affects only the explicit 429 contract scenario.
export TRANSACTIONS_RATE_LIMIT_CAPACITY=3
export TRANSACTIONS_RATE_LIMIT_REFILL_PER_SECOND=1

compose=(docker compose -f docker-compose.yaml -f docker-compose.api-tests.yaml)
"${compose[@]}" up -d --build

ready=false
for attempt in $(seq 1 90); do
  if curl --fail --silent http://localhost:8080/health >/dev/null \
    && curl --fail --silent http://localhost:18089/__admin/mappings >/dev/null \
    && curl --fail --silent http://localhost:18080/actuator/health >/dev/null \
    && curl --fail --silent http://localhost:18083/actuator/health >/dev/null; then
    ready=true
    break
  fi
  echo "Waiting for API test stack ($attempt/90)"
  sleep 5
done

if [[ "$ready" != true ]]; then
  "${compose[@]}" ps
  echo "API test stack did not become ready in time" >&2
  exit 1
fi

runs="${API_TEST_RUNS:-1}"
for run in $(seq 1 "$runs"); do
  echo "Running API suite ($run/$runs)"
  mvn -f services/pom.xml -Papi-tests -pl api-tests -am test -DskipApiTests=false
done
