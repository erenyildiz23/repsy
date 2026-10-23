#!/usr/bin/env bash
# Copyright 2026 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Stage 8 (ADR-005): npm packument reads on the S3 backend without and with the Redis cache,
# then with the cache and Redis paused for 30 s in the middle of the measured window. One run each
# (usage budget). Writes results/raw/redis/<name>.json|.log and Redis INFO stats.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=results/raw/redis
mkdir -p "$OUT"
load/fixtures.sh > /dev/null
RATE=${RATE:-200}

run() { # name cache
  docker compose --profile cache down -v > /dev/null 2>&1
  STORAGE_BACKEND=s3 STORAGE_CACHE=$2 docker compose --profile cache up -d --wait > /dev/null 2>&1
  docker compose run --rm -e RATE="$RATE" -e RUN="$1$RANDOM" k6 run --quiet \
    --summary-export="$OUT/$1.json" load/metadata.js > "$OUT/$1.log" 2>&1 || echo "  (k6 exit $?)"
  docker compose exec -T redis redis-cli INFO stats | grep -E 'keyspace_(hits|misses)' \
    > "$OUT/$1-redis-info.txt" || true
}

echo "no cache ($(date -u +%T))"; run s3-nocache none
echo "redis cache ($(date -u +%T))"; run s3-redis redis
echo "redis paused mid-run ($(date -u +%T))"
( sleep 60; docker compose pause redis > /dev/null; echo "paused $(date -u +%T)" > "$OUT/s3-redis-outage-events.txt"
  sleep 30; docker compose unpause redis > /dev/null; echo "unpaused $(date -u +%T)" >> "$OUT/s3-redis-outage-events.txt" ) &
run s3-redis-outage redis
wait
docker compose --profile cache down -v > /dev/null 2>&1
python3 load/report.py "$OUT" > /dev/null 2>&1 || true
