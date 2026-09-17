import http from 'k6/http';
import exec from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:18080';
const MANAGEMENT_BASE_URL = __ENV.MANAGEMENT_BASE_URL || 'http://127.0.0.1:18081';
const TEST_SCENARIO = __ENV.TEST_SCENARIO || 'distributed';
const RATE = Number(__ENV.RATE || 5);
const DURATION = __ENV.DURATION || '10s';
const PRE_ALLOCATED_VUS = Number(__ENV.PRE_ALLOCATED_VUS || 20);
const MAX_VUS = Number(__ENV.MAX_VUS || 100);
const TOKEN_COUNT = Number(__ENV.TOKEN_COUNT || Math.min(MAX_VUS, 100));
const FIXTURE_PREPARED = (__ENV.FIXTURE_PREPARED || 'false').toLowerCase() === 'true';
const ENFORCE_THRESHOLDS = (__ENV.ENFORCE_THRESHOLDS || 'true').toLowerCase() === 'true';
const UNIT_PRICE = 30000;

const checkoutConfirmed = new Counter('checkout_confirmed');
const expectedContention = new Counter('checkout_expected_contention');
const unexpectedNonSuccessful = new Counter('checkout_unexpected_non_2xx');
const unexpectedFailure = new Rate('checkout_unexpected_failure');
const checkoutDuration = new Trend('checkout_duration', true);

export const options = {
  scenarios: {
    checkout_contention: {
      executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: DURATION,
      preAllocatedVUs: PRE_ALLOCATED_VUS, maxVUs: MAX_VUS,
    },
  },
  thresholds: ENFORCE_THRESHOLDS ? {
    checkout_unexpected_failure: ['rate<0.05'],
    checkout_duration: ['p(95)<3000'],
  } : {},
};

export function setup() {
  const runId = (__ENV.RUN_ID || `checkout-${Date.now()}`).trim();
  if (!/^[A-Za-z0-9-]{1,32}$/.test(runId)) throw new Error('RUN_ID format is invalid.');
  const fixtureUrl = `${BASE_URL}/loadtest/${FIXTURE_PREPARED ? 'fixture' : 'runs'}?runId=${encodeURIComponent(runId)}`;
  const fixtureResponse = FIXTURE_PREPARED ? http.get(fixtureUrl) : http.post(fixtureUrl, null);
  const tokenResponse = http.get(`${BASE_URL}/loadtest/tokens?runId=${encodeURIComponent(runId)}&count=${TOKEN_COUNT}`);
  const metricsResponse = http.get(`${MANAGEMENT_BASE_URL}/actuator/prometheus`);
  if (fixtureResponse.status !== 200 || tokenResponse.status !== 200 || metricsResponse.status !== 200) {
    throw new Error('Checkout loadtest fixture, token, or metric endpoint is unavailable.');
  }
  const fixture = fixtureResponse.json();
  if (fixture.totalSeats !== 2000) throw new Error('Expected default 2,000-seat fixture.');
  return { fixture, tokens: tokenResponse.json(), runId, before: transitionCounts(metricsResponse.body) };
}

export default function (data) {
  const token = data.tokens[(__VU - 1) % data.tokens.length];
  const seatNumber = selectSeat(data.fixture, exec.scenario.iterationInTest);
  const identity = `${__VU}-${exec.scenario.iterationInTest}`;
  const common = { headers: { 'Content-Type': 'application/json', Cookie: `accessToken=${token.accessToken}` } };
  const request = { concertTimeId: data.fixture.concertTimeId, seatNumberList: [seatNumber] };
  const startedAt = Date.now();

  const hold = http.post(`${BASE_URL}/main/detail/${data.fixture.concertId}/seat-holds`, JSON.stringify(request), common);
  if (hold.status === 409) return contention();
  if (hold.status !== 200) return unexpected();

  const prepare = http.post(
    `${BASE_URL}/main/detail/${data.fixture.concertId}/checkouts`, JSON.stringify(request),
    { ...common, headers: { ...common.headers, 'Idempotency-Key': `checkout-${data.runId}-${identity}` } },
  );
  if (prepare.status !== 200) return unexpected();

  const merchantUid = prepare.json('merchantUid');
  const paymentId = `LT:${token.username}:${UNIT_PRICE}:${merchantUid}`;
  const verified = http.post(
    `${BASE_URL}/main/detail/${data.fixture.concertId}/checkouts/${encodeURIComponent(merchantUid)}/verified-reservation`,
    JSON.stringify({ ...request, merchantUid, paymentId }),
    { ...common, headers: { ...common.headers, 'Idempotency-Key': `lt-${data.runId}.${identity}` } },
  );
  checkoutDuration.add(Date.now() - startedAt);
  if (verified.status === 200) {
    checkoutConfirmed.add(1); unexpectedFailure.add(false); return;
  }
  return unexpected();
}

export function teardown(data) {
  const snapshot = http.get(`${BASE_URL}/loadtest/snapshot?runId=${encodeURIComponent(data.runId)}`);
  const metrics = http.get(`${MANAGEMENT_BASE_URL}/actuator/prometheus`);
  if (snapshot.status !== 200 || metrics.status !== 200 || snapshot.json().invariantSatisfied !== true) {
    throw new Error('Final Checkout snapshot or metric endpoint is invalid.');
  }
  const after = transitionCounts(metrics.body);
  const confirmed = after.reservationConfirmed - data.before.reservationConfirmed;
  const claimed = after.verificationClaimed - data.before.verificationClaimed;
  console.log(`CHECKOUT_FINAL_SNAPSHOT ${snapshot.body}`);
  console.log(`CHECKOUT_TRANSITION_DELTA ${JSON.stringify({ reservationConfirmed: confirmed, verificationClaimed: claimed })}`);
}

export function handleSummary(data) {
  const values = (name) => (data.metrics[name] ? data.metrics[name].values : {});
  const result = {
    schemaVersion: 1, scenario: TEST_SCENARIO, targetRatePerSecond: RATE, duration: DURATION,
    thresholdsEnforced: ENFORCE_THRESHOLDS,
    iterations: Number(values('iterations').count || 0),
    droppedIterations: Number(values('dropped_iterations').count || 0),
    checkoutConfirmed: Number(values('checkout_confirmed').count || 0),
    expectedContention: Number(values('checkout_expected_contention').count || 0),
    unexpectedNonSuccessful: Number(values('checkout_unexpected_non_2xx').count || 0),
    unexpectedFailureRate: Number(values('checkout_unexpected_failure').rate || 0),
    checkoutDurationMs: {
      p95: Number(values('checkout_duration')['p(95)'] || 0),
      average: Number(values('checkout_duration').avg || 0),
    },
  };
  return { stdout: `CHECKOUT_RESULT ${JSON.stringify(result)}\n` };
}

function contention() { expectedContention.add(1); unexpectedFailure.add(false); }
function unexpected() { unexpectedNonSuccessful.add(1); unexpectedFailure.add(true); }
function selectSeat(fixture, iteration) {
  if (TEST_SCENARIO === 'hot-seat') return 'R001-S001';
  const index = iteration % fixture.totalSeats;
  return `R${String(Math.floor(index / fixture.seatsPerRow) + 1).padStart(3, '0')}-S${String((index % fixture.seatsPerRow) + 1).padStart(3, '0')}`;
}
function transitionCounts(text) {
  return {
    reservationConfirmed: counter(text, 'verify_finalize', 'reservation_confirmed'),
    verificationClaimed: counter(text, 'verify_claim', 'verification_claimed'),
  };
}
function counter(text, operation, transition) {
  const escapedOperation = operation.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const escapedTransition = transition.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const pattern = new RegExp(`^onticket_checkout_transitions_total\\{[^}]*operation="${escapedOperation}"[^}]*transition="${escapedTransition}"[^}]*\\}\\s+([0-9.eE+-]+)$`, 'm');
  const match = text.match(pattern);
  return match ? Number(match[1]) : 0;
}
