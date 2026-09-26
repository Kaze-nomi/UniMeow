#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
project="unimeow-outbox-it-${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-1}-$$"
cleanup() { docker compose -p "$project" -f outbox/compose.test.yaml down; }
trap cleanup EXIT
docker compose -p "$project" -f outbox/compose.test.yaml up -d --wait
export OUTBOX_IT_JDBC_URL=jdbc:postgresql://localhost:25432/outbox_test
export OUTBOX_IT_KAFKA=localhost:29092
./gradlew :outbox:integrationTest --no-daemon --console=plain
