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

# Cold-cache downloads (REFERENCE §9.6, ADR-006): the download runs of the baseline again, with the
# page cache dropped every second for the whole run (`sync; echo 3 > /proc/sys/vm/drop_caches`),
# so most reads go to the disk. The user approved touching drop_caches (2026-10-23); this VM is the
# session's own. Writes results/raw/baseline/<backend>-download_cold-<size>-run<n>.json, which
# report.py lists as op download_cold.
# Usage: case-study/load/run-cold.sh   (env: REPS=3 WARMUP=30s MEASURE=90s BACKENDS="fs s3")
set -euo pipefail
cd "$(dirname "$0")/.."

REPS=${REPS:-3}
WARMUP=${WARMUP:-30s}
MEASURE=${MEASURE:-90s}
BACKENDS=${BACKENDS:-"fs s3"}
OUT=results/raw/baseline
mkdir -p "$OUT"
load/fixtures.sh > /dev/null
declare -A RATE=([1kb]=100 [1mb]=50 [50mb]=2)

for backend in $BACKENDS; do
  storage=file-system; [ "$backend" = s3 ] && storage=s3
  echo "== $backend: fresh stack"
  docker compose down -v > /dev/null 2>&1
  STORAGE_BACKEND=$storage docker compose up -d --wait > /dev/null 2>&1
  docker compose run --rm -e OP=upload -e SIZE=1mb -e RATE=20 -e WARMUP=5s -e MEASURE=30s \
    -e RUN="warm$RANDOM" k6 run --quiet load/maven.js > /dev/null 2>&1 || true
  for size in 1kb 1mb 50mb; do
    for n in $(seq 1 "$REPS"); do
      name="$backend-download_cold-$size-run$n"
      echo "  cold download $size run $n ($(date -u +%T))"
      ( while :; do sync; echo 3 > /proc/sys/vm/drop_caches; sleep 1; done ) &
      dropper=$!
      docker compose run --rm -e OP=download -e SIZE="$size" -e RATE="${RATE[$size]}" \
        -e WARMUP="$WARMUP" -e MEASURE="$MEASURE" -e RUN="$backend$n$RANDOM" \
        k6 run --quiet --summary-export="$OUT/$name.json" load/maven.js > "$OUT/$name.log" 2>&1 \
        || echo "  (k6 exit $? - see $name.log)"
      kill "$dropper"; wait "$dropper" 2>/dev/null || true
    done
  done
done
python3 load/report.py "$OUT" > results/baseline.md
echo "== results/baseline.md rewritten with the cold rows"
