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

# Baseline matrix (REFERENCE §8-9, ADR-006): {file-system, s3} x {1kb, 1mb, 50mb} x {upload,
# download} x REPS runs on a fresh stack per backend. Each run: WARMUP excluded, MEASURE recorded.
# Writes results/raw/baseline/<backend>-<op>-<size>-run<n>.json (k6 summary) and -stats.txt
# (docker stats sampled mid-measure), then results/baseline.md (report.py).
# Usage: case-study/load/run-matrix.sh   (env: REPS=3 WARMUP=30s MEASURE=90s BACKENDS="fs s3")
set -euo pipefail
cd "$(dirname "$0")/.."

REPS=${REPS:-3}
WARMUP=${WARMUP:-30s}
MEASURE=${MEASURE:-90s}
BACKENDS=${BACKENDS:-"fs s3"}
# CELLS limits the run to some op-size pairs ("upload-50mb download-50mb"); RUNS to some run
# numbers ("3"). FRESH=1 starts every run on a fresh stack: the uploads of one run are deleted
# before the next, so a 50 MiB cell (6 GB per run) does not fill the disk (it did once, see
# results/raw/baseline-invalid-diskfull).
CELLS=${CELLS:-}
RUNS=${RUNS:-}
FRESH=${FRESH:-0}
OUT=results/raw/baseline
mkdir -p "$OUT"
load/fixtures.sh > /dev/null

# Offered load per op and size, the same for both backends (calibrated in results/raw/calib).
declare -A RATE=(
  [upload-1kb]=50 [upload-1mb]=20 [upload-50mb]=1
  [download-1kb]=100 [download-1mb]=50 [download-50mb]=2
)

seconds() { local v=$1; case $v in *m) echo $(( ${v%m} * 60 ));; *s) echo "${v%s}";; esac; }
SAMPLE_AT=$(( $(seconds "$WARMUP") + $(seconds "$MEASURE") / 2 ))

run_k6() { # backend op size run
  local name="$1-$2-$3-run$4"
  ( sleep "$SAMPLE_AT"; docker stats --no-stream --format \
      '{{.Name}} cpu={{.CPUPerc}} mem={{.MemUsage}} net={{.NetIO}} block={{.BlockIO}}' \
      > "$OUT/$name-stats.txt" 2>&1 ) &
  local sampler=$!
  docker compose run --rm -e OP="$2" -e SIZE="$3" -e RATE="${RATE[$2-$3]}" \
    -e WARMUP="$WARMUP" -e MEASURE="$MEASURE" -e RUN="$1$4$RANDOM" \
    k6 run --quiet --summary-export="$OUT/$name.json" load/maven.js > "$OUT/$name.log" 2>&1 \
    || echo "  (k6 exit $? - thresholds crossed or run failed, see $name.log)"
  wait "$sampler" || true
}

fresh_stack() { # backend
  local storage=file-system; [ "$1" = s3 ] && storage=s3
  docker compose down -v > /dev/null 2>&1
  STORAGE_BACKEND=$storage docker compose up -d --wait > /dev/null 2>&1
  # unrecorded JIT warm-up run
  docker compose run --rm -e OP=upload -e SIZE=1mb -e RATE=20 -e WARMUP=5s -e MEASURE=30s \
    -e RUN="warm$RANDOM" k6 run --quiet load/maven.js > /dev/null 2>&1 || true
}

selected() { # value list
  [ -z "$2" ] && return 0
  case " $2 " in *" $1 "*) return 0;; esac
  return 1
}

for backend in $BACKENDS; do
  echo "== $backend: fresh stack"
  fresh_stack "$backend"
  for size in 1kb 1mb 50mb; do
    for op in upload download; do
      selected "$op-$size" "$CELLS" || continue
      for n in $(seq 1 "$REPS"); do
        selected "$n" "$RUNS" || continue
        [ "$FRESH" = 1 ] && fresh_stack "$backend"
        echo "  $op $size run $n ($(date -u +%T))"
        run_k6 "$backend" "$op" "$size" "$n"
      done
    done
  done
  docker compose down -v > /dev/null 2>&1
done

python3 load/report.py "$OUT" > results/baseline.md
echo "== results/baseline.md written"
