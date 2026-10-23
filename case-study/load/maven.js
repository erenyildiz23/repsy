// Copyright 2026 the original author or authors.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// Maven upload or download load against Repsy's wire protocol (PUT/GET on port 9090, raw body,
// no multipart). Open model (constant-arrival-rate) so a slow server does not lower the offered
// load (REFERENCE §9.1); the warmup scenario is tagged apart and excluded from the measured
// metrics (§9.3). Env: OP=upload|download, SIZE=1kb|1mb|50mb, RATE (req/s), WARMUP, MEASURE,
// RUN (unique id, keeps versions unique across runs).
import http from 'k6/http';
import encoding from 'k6/encoding';
import { check } from 'k6';
import exec from 'k6/execution';

const OP = __ENV.OP || 'upload';
const SIZE = __ENV.SIZE || '1mb';
const RATE = Number(__ENV.RATE || 10);
const WARMUP = __ENV.WARMUP || '30s';
const MEASURE = __ENV.MEASURE || '90s';
const RUN = __ENV.RUN || `${Date.now()}`;
const BASE = __ENV.BASE_URL || 'http://localhost:9090';
const DOWNLOAD_SET = 20;
const VUS = SIZE === '50mb' ? 4 : 50;

// One copy per VU (k6 #2311): VUS stays low for 50mb.
const bin = open(`./fixtures/${SIZE}.bin`, 'b');

const headers = {
  Authorization: `Basic ${encoding.b64encode(__ENV.REPSY_AUTH || 'admin:admin')}`,
  'Content-Type': 'application/java-archive',
};

const scenario = (startTime, duration, phase) => ({
  executor: 'constant-arrival-rate',
  rate: RATE,
  timeUnit: '1s',
  duration,
  startTime,
  preAllocatedVUs: VUS,
  maxVUs: VUS * 2,
  tags: { phase },
});

export const options = {
  discardResponseBodies: true,
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    warmup: scenario('0s', WARMUP, 'warmup'),
    measure: scenario(WARMUP, MEASURE, 'measure'),
  },
  thresholds: {
    // Sub-metrics of the measured phase, so the summary export carries them.
    'http_req_duration{phase:measure}': ['max>=0'],
    'http_reqs{phase:measure}': ['count>=0'],
    'http_req_failed{phase:measure}': ['rate<0.01'],
    dropped_iterations: ['count==0'],
  },
};

const jarPath = (version) =>
  `${BASE}/maven/com/example/load/load/${version}/load-${version}.jar`;

export function setup() {
  if (OP !== 'download') {
    return {};
  }
  for (let i = 0; i < DOWNLOAD_SET; i++) {
    const res = http.put(jarPath(`dl.${RUN}.${i}`), bin, { headers, tags: { phase: 'setup' } });
    if (res.status !== 200) {
      throw new Error(`setup upload ${i} answered ${res.status}`);
    }
  }
  return {};
}

export default function () {
  if (OP === 'download') {
    const i = Math.floor(Math.random() * DOWNLOAD_SET);
    const res = http.get(jarPath(`dl.${RUN}.${i}`), { headers, tags: { op: 'download', size: SIZE } });
    check(res, { 'status 200': (r) => r.status === 200 });
    return;
  }
  const version = `${RUN}.${exec.scenario.name}.${__VU}.${__ITER}`;
  const res = http.put(jarPath(version), bin, { headers, tags: { op: 'upload', size: SIZE } });
  check(res, { 'status 200': (r) => r.status === 200 });
}

