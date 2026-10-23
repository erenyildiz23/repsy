#!/usr/bin/env sh
# Random (incompressible) bodies for the load scripts; not committed.
set -eu
cd "$(dirname "$0")"
mkdir -p fixtures
[ -f fixtures/1kb.bin ] || head -c 1024 /dev/urandom > fixtures/1kb.bin
[ -f fixtures/1mb.bin ] || head -c 1048576 /dev/urandom > fixtures/1mb.bin
[ -f fixtures/50mb.bin ] || head -c 52428800 /dev/urandom > fixtures/50mb.bin

# npm publish bodies for metadata.js; __NAME__ is replaced by the package name at setup.
for i in $(seq 0 49); do
  [ -f "fixtures/npm-$i.json" ] || python3 ../chaos/npm_payload.py __NAME__ "1.0.$i" 2000 > "fixtures/npm-$i.json"
done
