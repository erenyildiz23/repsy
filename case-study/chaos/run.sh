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

# Fault matrix (REFERENCE §10, stage 4): each scenario runs on a fresh stack of the given
# backend, starts uploads, injects the fault while they are in flight, restores, waits for Repsy
# and records what the client saw and what check.py finds afterwards, in
# results/raw/chaos/<backend>-<scenario>.{txt,json}.
# Usage: chaos/run.sh fs|s3 <scenario>...   (scenarios: see the case statement; "all" = every
# scenario that applies to the backend)
set -uo pipefail
cd "$(dirname "$0")/.."

BACKEND=$1; shift
OUT=results/raw/chaos
mkdir -p "$OUT"
load/fixtures.sh > /dev/null
AUTH="admin:$(grep '^REPSY_ADMIN_PASSWORD=' .env | cut -d= -f2)"
API=http://localhost:9090
TOXI=http://localhost:8474
LOG=/dev/null

fresh_stack() {
  docker compose down -v > /dev/null 2>&1
  local storage=file-system; [ "$BACKEND" = s3 ] && storage=s3
  STORAGE_BACKEND=$storage docker compose up -d --wait > /dev/null 2>&1
}

log() { echo "$(date -u +%T.%3N) $*" | tee -a "$LOG"; }

# PUT a Maven file in the background; its status and time go to the log when it ends.
maven_put() { # tag version file name
  local url="$API/maven/com/example/chaos/chaos/$2/$4"
  ( r=$(curl -s -o /dev/null -w '%{http_code} %{time_total}s' -m 120 -u "$AUTH" -T "$3" "$url")
    log "client $1 PUT $4 -> $r" ) &
}

npm_publish() { # tag name version size
  local body; body=$(mktemp)
  python3 chaos/npm_payload.py "$2" "$3" "$4" > "$body"
  ( r=$(curl -s -o /dev/null -w '%{http_code} %{time_total}s' -m 120 -u "$AUTH" -X PUT \
        -H 'Content-Type: application/json' --data-binary @"$body" "$API/npm/$2")
    log "client $1 npm publish $2@$3 -> $r"; rm -f "$body" ) &
}

pom() { # version -> path of a POM for chaos:chaos:<version>
  local f; f=$(mktemp --suffix=.pom)
  printf '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><groupId>com.example.chaos</groupId><artifactId>chaos</artifactId><version>%s</version></project>' "$1" > "$f"
  echo "$f"
}

deploy() { # version jar-size: jar then POM, as mvn deploy sends them
  maven_put "$1" "$1" "load/fixtures/$2.bin" "chaos-$1.jar"; wait
  maven_put "$1" "$1" "$(pom "$1")" "chaos-$1.pom"
}

toxic() { curl -s -X POST "$TOXI/proxies/$1/toxics" -d "$2" > /dev/null; }
reset_toxics() { curl -s -X POST "$TOXI/reset" > /dev/null; }
wait_repsy() { docker compose up -d --wait repsy > /dev/null 2>&1; }

run_scenario() {
  local s=$1
  LOG="$OUT/$BACKEND-$s.txt"; : > "$LOG"
  log "== $BACKEND $s: fresh stack"
  fresh_stack
  deploy base 1mb; wait   # one healthy version, so a scenario never starts from empty tables
  log "baseline deployed"
  case $s in
    s3-kill)      # the object store dies under a 50 MB upload
      maven_put a v1 load/fixtures/50mb.bin chaos-v1.jar; sleep 0.3
      log "fault: SIGKILL s3"; docker compose kill -s SIGKILL s3 > /dev/null
      wait; docker compose start s3 > /dev/null; docker compose up -d --wait s3 > /dev/null 2>&1
      log "s3 restarted"; deploy v2 1mb; wait ;;
    pg-kill)      # the database dies under a 50 MB jar and its POM
      maven_put a v1 load/fixtures/50mb.bin chaos-v1.jar; sleep 0.3
      log "fault: SIGKILL postgres"; docker compose kill -s SIGKILL postgres > /dev/null
      wait; maven_put b v1 "$(pom v1)" chaos-v1.pom; npm_publish c pgkill 1.0.0 1000; wait
      docker compose start postgres > /dev/null; docker compose up -d --wait postgres > /dev/null 2>&1
      log "postgres restarted"; sleep 5; deploy v2 1mb; wait ;;
    pg-pause)     # the database freezes for 15 s under a deploy and a publish
      log "fault: pause postgres"; docker compose pause postgres > /dev/null
      maven_put a v1 load/fixtures/1mb.bin chaos-v1.jar
      maven_put b v1 "$(pom v1)" chaos-v1.pom
      npm_publish c pgpause 1.0.0 1000
      sleep 15; docker compose unpause postgres > /dev/null; log "postgres unpaused"; wait ;;
    pg-latency)   # every database answer 1 s late
      log "fault: +1000 ms on pg"
      toxic pg '{"name":"lat","type":"latency","stream":"downstream","attributes":{"latency":1000,"jitter":100}}'
      deploy v1 1mb; npm_publish c pglat 1.0.0 1000; wait; reset_toxics ;;
    pg-reset)     # database connections reset (RST) while a deploy and a publish run
      toxic pg '{"name":"rst","type":"reset_peer","attributes":{"timeout":500}}'
      log "fault: reset_peer on pg"
      maven_put a v1 load/fixtures/1mb.bin chaos-v1.jar
      maven_put b v1 "$(pom v1)" chaos-v1.pom
      npm_publish c pgrst 1.0.0 1000; wait; reset_toxics; sleep 3
      deploy v2 1mb; wait ;;
    s3-cut)       # the object store connection drops after 5 MB of a 50 MB upload
      toxic s3 '{"name":"cut","type":"limit_data","stream":"upstream","attributes":{"bytes":5242880}}'
      log "fault: limit_data 5 MB on s3"
      maven_put a v1 load/fixtures/50mb.bin chaos-v1.jar; wait; reset_toxics
      deploy v2 1mb; wait ;;
    s3-hang)      # the object store accepts the connection and never answers
      toxic s3 '{"name":"hang","type":"timeout","attributes":{"timeout":0}}'
      log "fault: timeout(0) on s3"
      maven_put a v1 load/fixtures/1mb.bin chaos-v1.jar; wait; reset_toxics
      deploy v2 1mb; wait ;;
    repsy-kill)   # Repsy itself dies under a 50 MB upload and a burst of publishes
      maven_put a v1 load/fixtures/50mb.bin chaos-v1.jar
      for i in $(seq 1 10); do npm_publish "n$i" "kill$i" 1.0.0 200000; done
      for i in $(seq 1 10); do maven_put "p$i" "k$i" "$(pom "k$i")" "chaos-k$i.pom"; done
      sleep 0.4; log "fault: SIGKILL repsy"; docker compose kill -s SIGKILL repsy > /dev/null
      wait; wait_repsy; log "repsy restarted"; deploy v2 1mb; wait ;;
    disk-full)    # the storage volume fills up (file system only, see compose.disk-full.yml)
      export COMPOSE_FILE=compose.yml:chaos/compose.disk-full.yml CHECK_STORAGE_DIR=/mnt/small
      docker compose up -d --wait repsy > /dev/null 2>&1
      log "storage on a 60 MiB tmpfs"; deploy base 1mb; wait
      maven_put a v1 load/fixtures/50mb.bin chaos-v1.jar; wait
      maven_put b v2 load/fixtures/50mb.bin chaos-v2.jar; wait
      deploy v3 1mb; wait ;;
    *) log "unknown scenario $s"; return ;;
  esac
  wait_repsy; sleep 2
  python3 chaos/check.py "$BACKEND" > "$OUT/$BACKEND-$s.json" 2>> "$LOG"
  log "check: $(python3 -c "import json;d=json.load(open('$OUT/$BACKEND-$s.json'));m=d['maven'];n=d['npm'];print('pom_without_row=%d row_without_pom=%d jar_without_pom=%d tgz_without_row=%d row_without_tgz=%d usage_drift=%s leftovers=%d'%(len(m['pom_without_row']),len(m['row_without_pom']),len(m['jar_without_pom']),len(n['tgz_without_row']),len(n['row_without_tgz']),d['usage_drift'],d['leftovers']))")"
  docker compose logs repsy 2>&1 | grep -E ' (ERROR|WARN) ' | grep -vE 'SpringDoc|started successfully' | tail -15 >> "$LOG"
  unset COMPOSE_FILE CHECK_STORAGE_DIR
}

SCENARIOS=("$@")
if [ "${SCENARIOS[0]:-}" = all ]; then
  if [ "$BACKEND" = s3 ]; then
    SCENARIOS=(s3-kill pg-kill pg-pause pg-latency pg-reset s3-cut s3-hang repsy-kill)
  else
    SCENARIOS=(pg-kill pg-pause pg-latency pg-reset repsy-kill disk-full)
  fi
fi
for s in "${SCENARIOS[@]}"; do run_scenario "$s"; done
