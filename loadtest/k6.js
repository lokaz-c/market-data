// k6 load test against the local docker compose stack (see scripts/loadtest.sh).
// One request per iteration, so the constant-arrival-rate scenario's rate is requests per second.
// Mix, roughly what the chart explorer does per ticker view: 40% bars, 30% indicators, 20% levels,
// 10% symbol search. Tickers are picked at random from the synthetic S001..S500.
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:18080';
const SYMBOLS = Number(__ENV.SYMBOLS || 500);
const STEADY_RATE = Number(__ENV.STEADY_RATE || 200);
const SATURATION_VUS = Number(__ENV.SATURATION_VUS || 32);
const PARAMS = (endpoint) => ({ headers: { 'Accept-Encoding': 'gzip' }, tags: { endpoint } });

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    // Lets the JIT compile hot paths and fills connection pools; excluded from the report.
    warmup: { executor: 'constant-arrival-rate', rate: 100, timeUnit: '1s', duration: '20s', preAllocatedVUs: 50, maxVUs: 200 },
    // Open model: a fixed request rate, to read latency at a known load.
    steady: { executor: 'constant-arrival-rate', rate: STEADY_RATE, timeUnit: '1s', duration: '60s', startTime: '25s', preAllocatedVUs: 100, maxVUs: 400 },
    // Closed model: N clients with no think time, to find throughput when the service is the bottleneck.
    saturation: { executor: 'constant-vus', vus: SATURATION_VUS, duration: '60s', startTime: '90s' },
  },
  // Thresholds double as a way to make k6 keep per-scenario and per-endpoint numbers in the summary.
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{scenario:steady}': ['p(95)<1000'],
    'http_req_duration{scenario:saturation}': ['p(95)<5000'],
    'http_reqs{scenario:steady}': ['count>0'],
    'http_reqs{scenario:saturation}': ['count>0'],
    'http_req_failed{scenario:steady}': ['rate<0.01'],
    'http_req_failed{scenario:saturation}': ['rate<0.01'],
    'dropped_iterations{scenario:steady}': ['count>=0'],
    'http_req_duration{scenario:steady,endpoint:bars}': ['p(95)<1000'],
    'http_req_duration{scenario:steady,endpoint:indicators}': ['p(95)<1000'],
    'http_req_duration{scenario:steady,endpoint:levels}': ['p(95)<1000'],
    'http_req_duration{scenario:steady,endpoint:symbols}': ['p(95)<1000'],
    'http_req_duration{scenario:saturation,endpoint:bars}': ['p(95)<5000'],
    'http_req_duration{scenario:saturation,endpoint:indicators}': ['p(95)<5000'],
    'http_req_duration{scenario:saturation,endpoint:levels}': ['p(95)<5000'],
    'http_req_duration{scenario:saturation,endpoint:symbols}': ['p(95)<5000'],
  },
};

export function setup() {
  const res = http.get(`${BASE}/v1/symbols?limit=1`);
  const lastBar = res.json('symbols.0.lastBar');
  const to = new Date(`${lastBar}T00:00:00Z`);
  const from = new Date(to);
  from.setUTCFullYear(to.getUTCFullYear() - 1);
  return { from: from.toISOString().slice(0, 10), to: lastBar };
}

export default function (range) {
  const ticker = `S${String(1 + Math.floor(Math.random() * SYMBOLS)).padStart(3, '0')}`;
  const window = `from=${range.from}&to=${range.to}&limit=1000`;
  const r = Math.random();
  let res;
  if (r < 0.4) {
    res = http.get(`${BASE}/v1/bars/${ticker}?${window}`, PARAMS('bars'));
  } else if (r < 0.7) {
    res = http.get(`${BASE}/v1/indicators/${ticker}?${window}`, PARAMS('indicators'));
  } else if (r < 0.9) {
    res = http.get(`${BASE}/v1/levels/${ticker}`, PARAMS('levels'));
  } else {
    res = http.get(`${BASE}/v1/symbols?q=${ticker.slice(0, 3)}&limit=50`, PARAMS('symbols'));
  }
  check(res, { 'status 200': (x) => x.status === 200 });
}

export function handleSummary(data) {
  return { [__ENV.SUMMARY_PATH || 'loadtest/results/summary.json']: JSON.stringify(data, null, 2) };
}
