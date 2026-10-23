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
"""Builds the baseline table from the k6 summaries of run-matrix.sh.

Every cell is the median of the runs with [min-max] next to it; a run with dropped iterations or
1% failures or more is invalid (REFERENCE 9.2) and shown, but kept out of the medians.
Usage: python3 load/report.py results/raw/baseline [MEASURE seconds, default 90]
"""
import glob
import json
import os
import re
import statistics
import sys

SIZES = {"1kb": 1024, "1mb": 1048576, "50mb": 52428800}


def load(path, measure_s):
    m = json.load(open(path))["metrics"]
    dur = m.get("http_req_duration{phase:measure}", {})
    count = m.get("http_reqs{phase:measure}", {}).get("count", 0)
    stats = {}
    stats_path = path.replace(".json", "-stats.txt")
    if os.path.exists(stats_path):
        for line in open(stats_path):
            name, *rest = line.split()
            fields = dict(f.split("=", 1) for f in rest if "=" in f)
            for svc in ("repsy", "s3", "postgres"):
                if re.search(rf"-{svc}-1$", name):
                    stats[svc] = fields.get("cpu", "?"), fields.get("mem", "?")
    return {
        "p50": dur.get("med", 0.0),
        "p95": dur.get("p(95)", 0.0),
        "p99": dur.get("p(99)", 0.0),
        "rps": count / measure_s,
        "fail": m.get("http_req_failed{phase:measure}", {}).get("value", 0.0),
        "dropped": m.get("dropped_iterations", {}).get("count", 0),
        "stats": stats,
        "file": os.path.basename(path),
    }


def cell(values, fmt):
    if not values:
        return "-"
    med = statistics.median(values)
    return f"{fmt.format(med)} [{fmt.format(min(values))}-{fmt.format(max(values))}]"


def main():
    directory = sys.argv[1]
    measure_s = float(sys.argv[2]) if len(sys.argv) > 2 else 90.0
    groups = {}
    for path in sorted(glob.glob(os.path.join(directory, "*-run*.json"))):
        backend, op, size, _ = os.path.basename(path).split("-", 3)
        groups.setdefault((op, size, backend), []).append(load(path, measure_s))

    print("# Başlangıç ölçümü (Aşama 3)\n")
    print(f"Üretildi: `python3 load/report.py {directory}`. Ölçüm penceresi {measure_s:.0f} sn, "
          "ısınma hariç. Hücre = geçerli koşuların medyanı [min-max]. Süreler ms. "
          "Bu makinede göreli karşılaştırmadır (`results/environment.md`).\n")
    print("| İşlem | Boyut | Strateji | Geçerli koşu | p50 | p95 | p99 | istek/sn | MB/sn | hata oranı "
          "| repsy CPU / bellek | s3 CPU | Ham dosyalar |")
    print("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |")
    for op in ("upload", "download"):
        for size in ("1kb", "1mb", "50mb"):
            for backend in ("fs", "s3"):
                runs = groups.get((op, size, backend), [])
                if not runs:
                    continue
                valid = [r for r in runs if r["dropped"] == 0 and r["fail"] < 0.01]
                invalid = [f"{r['file']} (dropped={r['dropped']}, fail={r['fail']:.3f})"
                           for r in runs if r not in valid]
                repsy = "; ".join(f"{r['stats'].get('repsy', ('?', '?'))[0]} / "
                                  f"{r['stats'].get('repsy', ('?', '?'))[1]}" for r in valid)
                s3cpu = "; ".join(r["stats"].get("s3", ("?",))[0] for r in valid)
                files = ", ".join(f"`{r['file']}`" for r in runs)
                print(f"| {op} | {size} | {backend} | {len(valid)}/{len(runs)} "
                      f"| {cell([r['p50'] for r in valid], '{:.0f}')} "
                      f"| {cell([r['p95'] for r in valid], '{:.0f}')} "
                      f"| {cell([r['p99'] for r in valid], '{:.0f}')} "
                      f"| {cell([r['rps'] for r in valid], '{:.1f}')} "
                      f"| {cell([r['rps'] * SIZES[size] / 1e6 for r in valid], '{:.2f}')} "
                      f"| {cell([r['fail'] for r in valid], '{:.3f}')} "
                      f"| {repsy or '-'} | {s3cpu or '-'} | {files} |")
                for line in invalid:
                    print(f"| | | | geçersiz: {line} | | | | | | | | | |")


if __name__ == "__main__":
    main()
