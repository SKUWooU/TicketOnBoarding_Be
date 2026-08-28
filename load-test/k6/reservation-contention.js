import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:18080';
const TEST_SCENARIO = __ENV.TEST_SCENARIO || 'distributed';
const RATE = Number(__ENV.RATE || 5);
const DURATION = __ENV.DURATION || '10s';
const PRE_ALLOCATED_VUS = Number(__ENV.PRE_ALLOCATED_VUS || 20);
const MAX_VUS = Number(__ENV.MAX_VUS || 100);
const TOKEN_COUNT = Number(__ENV.TOKEN_COUNT || Math.min(MAX_VUS, 100));
const UNIT_PRICE = 30000;

const reservationSuccess = new Counter('reservation_success');
const nonSuccessfulReservation = new Counter('reservation_non_2xx');
const unexpectedFailure = new Rate('reservation_unexpected_failure');
const reservationDuration = new Trend('reservation_duration', true);

const strictSuccessScenario = TEST_SCENARIO === 'distributed'
  || TEST_SCENARIO === 'idempotent-retry';

export const options = {
  scenarios: {
    reservation_contention: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: PRE_ALLOCATED_VUS,
      maxVUs: MAX_VUS,
    },
  },
  thresholds: {
    reservation_duration: ['p(95)<2000'],
    ...(strictSuccessScenario
      ? { reservation_unexpected_failure: ['rate<0.05'] }
      : {}),
  },
};

export function setup() {
  const runId = (__ENV.RUN_ID || `run-${Date.now()}`).trim();
  if (!/^[A-Za-z0-9-]{1,32}$/.test(runId)) {
    throw new Error('RUN_ID must contain 1-32 letters, numbers, or hyphens.');
  }

  const fixtureResponse = http.post(
    `${BASE_URL}/loadtest/runs?runId=${encodeURIComponent(runId)}`,
    null,
  );
  const tokenResponse = http.get(
    `${BASE_URL}/loadtest/tokens?runId=${encodeURIComponent(runId)}&count=${TOKEN_COUNT}`,
  );

  check(fixtureResponse, {
    'fixture is available': (response) => response.status === 200,
  });
  check(tokenResponse, {
    'loadtest tokens are available': (response) => response.status === 200,
  });

  if (fixtureResponse.status !== 200 || tokenResponse.status !== 200) {
    throw new Error(`Failed to initialize loadtest run ${runId}.`);
  }

  const fixture = fixtureResponse.json();
  const tokens = tokenResponse.json();
  if (fixture.totalSeats !== 2000 || tokens.length === 0) {
    throw new Error('Expected the default 2,000-seat fixture and at least one loadtest token.');
  }
  if (TEST_SCENARIO === 'idempotent-retry' && MAX_VUS > fixture.totalSeats) {
    throw new Error('idempotent-retry requires max VUs not to exceed total seats.');
  }
  return { fixture, tokens, runId };
}

export default function (data) {
  const token = data.tokens[(__VU - 1) % data.tokens.length];
  const iteration = exec.scenario.iterationInTest;
  const seatNumber = selectSeat(data.fixture, iteration);
  const stableRetry = TEST_SCENARIO === 'idempotent-retry';
  const requestIdentity = stableRetry ? `vu-${__VU}` : `${__VU}-${iteration}`;
  const idempotencyKey = `lt-${data.runId}.${TEST_SCENARIO}-${requestIdentity}`;
  const paymentId = `LT:${token.username}:${UNIT_PRICE}:${TEST_SCENARIO}-${requestIdentity}`;

  const response = http.post(
    `${BASE_URL}/main/detail/${data.fixture.concertId}/verified-reservation`,
    JSON.stringify({
      paymentId,
      concertTimeId: data.fixture.concertTimeId,
      seatNumberList: [seatNumber],
    }),
    {
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': idempotencyKey,
        Cookie: `accessToken=${token.accessToken}`,
      },
      tags: { test_scenario: TEST_SCENARIO },
    },
  );
  reservationDuration.add(response.timings.duration);

  if (response.status === 200) {
    reservationSuccess.add(1);
    unexpectedFailure.add(false);
    return;
  }

  nonSuccessfulReservation.add(1);
  unexpectedFailure.add(true);
}

export function teardown(data) {
  const snapshot = http.get(
    `${BASE_URL}/loadtest/snapshot?runId=${encodeURIComponent(data.runId)}`,
  );
  console.log(`LOADTEST_FINAL_SNAPSHOT ${snapshot.body}`);
  if (snapshot.status !== 200 || snapshot.json().invariantSatisfied !== true) {
    throw new Error(`Loadtest run ${data.runId} finished with a broken inventory invariant.`);
  }
}

function selectSeat(fixture, iteration) {
  if (TEST_SCENARIO === 'hot-seat') {
    return 'R001-S001';
  }
  if (TEST_SCENARIO === 'hot-section') {
    return seatNumber(1, (iteration % fixture.seatsPerRow) + 1);
  }
  if (TEST_SCENARIO === 'idempotent-retry') {
    const seatIndex = (__VU - 1) % fixture.totalSeats;
    const row = Math.floor(seatIndex / fixture.seatsPerRow) + 1;
    const number = (seatIndex % fixture.seatsPerRow) + 1;
    return seatNumber(row, number);
  }

  const seatIndex = iteration % fixture.totalSeats;
  const row = Math.floor(seatIndex / fixture.seatsPerRow) + 1;
  const number = (seatIndex % fixture.seatsPerRow) + 1;
  return seatNumber(row, number);
}

function seatNumber(row, number) {
  return `R${String(row).padStart(3, '0')}-S${String(number).padStart(3, '0')}`;
}
