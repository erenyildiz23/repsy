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

// Metadata read load for stage 8 (ADR-005): GET of the npm packument (what every `npm install`
// asks first), with and without the Redis cache. setup() publishes PACKAGES packages from the
// fixtures/npm-<i>.json bodies (fixtures.sh); each iteration reads a random one. Same open model
// and warmup tagging as maven.js. Env: RATE, WARMUP, MEASURE, PACKAGES, RUN.
import http from 'k6/http';
import encoding from 'k6/encoding';
import { check } from 'k6';

const RATE = Number(__ENV.RATE || 200);
const WARMUP = __ENV.WARMUP || '30s';
const MEASURE = __ENV.MEASURE || '90s';
const PACKAGES = Number(__ENV.PACKAGES || 50);
const RUN = __ENV.RUN || `${Date.now()}`;
const BASE = __ENV.BASE_URL || 'http://localhost:9090';

const bodies = [];
for (let i = 0; i < PACKAGES; i++) {
  bodies.push(open(`./fixtures/npm-${i}.json`));
}

const auth = `Basic ${encoding.b64encode(__ENV.REPSY_AUTH || 'admin:admin')}`;

const scenario = (startTime, duration, phase) => ({
  executor: 'constant-arrival-rate',
  rate: RATE,
  timeUnit: '1s',
  duration,
  startTime,
  preAllocatedVUs: 50,
  maxVUs: 100,
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
    'http_req_duration{phase:measure}': ['max>=0'],
    'http_reqs{phase:measure}': ['count>=0'],
    'http_req_failed{phase:measure}': ['rate<0.01'],
    dropped_iterations: ['count==0'],
  },
};

const nameOf = (i) => `meta-${RUN}-${i}`;

export function setup() {
  for (let i = 0; i < PACKAGES; i++) {
    const body = bodies[i].split('__NAME__').join(nameOf(i));
    const res = http.put(`${BASE}/npm/${nameOf(i)}`, body, {
      headers: { Authorization: auth, 'Content-Type': 'application/json' },
      tags: { phase: 'setup' },
    });
    if (res.status !== 200 && res.status !== 201) {
      throw new Error(`setup publish ${i} answered ${res.status}`);
    }
  }
  return {};
}

export default function () {
  const i = Math.floor(Math.random() * PACKAGES);
  const res = http.get(`${BASE}/npm/${nameOf(i)}`, {
    headers: { Authorization: auth, Accept: 'application/json' },
    tags: { op: 'packument' },
  });
  check(res, { 'status 200': (r) => r.status === 200 });
}
