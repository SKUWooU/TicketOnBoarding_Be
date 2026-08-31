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
const TOKEN_COUNT = Number(__ENV.TOKEN_COUNT || 500);
const FIXTURE_RUN_ID = (__ENV.FIXTURE_RUN_ID || '').trim();
const ENFORCE_THRESHOLDS = (__ENV.ENFORCE_THRESHOLDS || 'true').toLowerCase() === 'true';

const holdSuccess = new Counter('seat_hold_success');
const nonSuccessfulHold = new Counter('seat_hold_non_2xx');
const expectedContention = new Counter('seat_hold_expected_contention');
const unexpectedNonSuccessfulHold = new Counter('seat_hold_unexpected_non_2xx');
const unexpectedFailure = new Rate('seat_hold_unexpected_failure');
const holdDuration = new Trend('seat_hold_duration', true);

const expectedContentionScenario = TEST_SCENARIO === 'hot-seat'
  || TEST_SCENARIO === 'hot-section';

http.setResponseCallback(
  expectedContentionScenario
    ? http.expectedStatuses(200, 409)
    : http.expectedStatuses(200),
);

export const options = {
  scenarios: {
    seat_hold_contention: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: PRE_ALLOCATED_VUS,
      maxVUs: MAX_VUS,
    },
  },
  thresholds: ENFORCE_THRESHOLDS
    ? {
      seat_hold_duration: ['p(95)<2000'],
      seat_hold_unexpected_failure: ['rate<0.05'],
    }
    : {},
};

export function setup() {
  if (!/^[A-Za-z0-9-]{1,32}$/.test(FIXTURE_RUN_ID)) {
    throw new Error('FIXTURE_RUN_ID must contain 1-32 letters, numbers, or hyphens.');
  }

  const fixtureResponse = http.get(
    `${BASE_URL}/loadtest/fixture?runId=${encodeURIComponent(FIXTURE_RUN_ID)}`,
  );
  const tokenResponse = http.get(
    `${BASE_URL}/loadtest/tokens?runId=${encodeURIComponent(FIXTURE_RUN_ID)}&count=${TOKEN_COUNT}`,
  );

  check(fixtureResponse, {
    'fixture is available': (response) => response.status === 200,
  });
  check(tokenResponse, {
    'loadtest tokens are available': (response) => response.status === 200,
  });

  if (fixtureResponse.status !== 200 || tokenResponse.status !== 200) {
    throw new Error(`Failed to initialize seat-hold fixture ${FIXTURE_RUN_ID}.`);
  }

  const fixture = fixtureResponse.json();
  const tokens = tokenResponse.json();
  if (fixture.totalSeats !== 2000 || tokens.length === 0) {
    throw new Error('Expected the default 2,000-seat fixture and at least one loadtest token.');
  }
  return { fixture, tokens };
}

export default function (data) {
  const iteration = exec.scenario.iterationInTest;
  const tokenIndex = expectedContentionScenario
    ? iteration % data.tokens.length
    : (__VU - 1) % data.tokens.length;
  const token = data.tokens[tokenIndex];
  const seatNumber = selectSeat(data.fixture, iteration);
  const response = http.post(
    `${BASE_URL}/main/detail/${data.fixture.concertId}/seat-holds`,
    JSON.stringify({
      concertTimeId: data.fixture.concertTimeId,
      seatNumberList: [seatNumber],
    }),
    {
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        Cookie: `accessToken=${token.accessToken}`,
      },
      tags: { test_scenario: TEST_SCENARIO },
    },
  );
  holdDuration.add(response.timings.duration);

  if (response.status === 200) {
    holdSuccess.add(1);
    unexpectedFailure.add(false);
    return;
  }

  nonSuccessfulHold.add(1);
  if (response.status === 409 && expectedContentionScenario) {
    expectedContention.add(1);
    unexpectedFailure.add(false);
    return;
  }

  unexpectedNonSuccessfulHold.add(1);
  unexpectedFailure.add(true);
}

export function teardown() {
  const snapshot = http.get(
    `${BASE_URL}/loadtest/seat-holds/snapshot?runId=${encodeURIComponent(FIXTURE_RUN_ID)}`,
  );
  console.log(`SEAT_HOLD_FINAL_SNAPSHOT ${snapshot.body}`);
  if (snapshot.status !== 200 || snapshot.json().invariantSatisfied !== true) {
    throw new Error(`Seat-hold fixture ${FIXTURE_RUN_ID} finished with a broken state invariant.`);
  }
}

export function handleSummary(data) {
  const result = {
    schemaVersion: 1,
    scenario: TEST_SCENARIO,
    targetRatePerSecond: RATE,
    duration: DURATION,
    thresholdsEnforced: ENFORCE_THRESHOLDS,
    iterations: counterValue(data, 'iterations'),
    droppedIterations: counterValue(data, 'dropped_iterations'),
    holdSuccess: counterValue(data, 'seat_hold_success'),
    expectedContention: counterValue(data, 'seat_hold_expected_contention'),
    unexpectedNonSuccessful: counterValue(data, 'seat_hold_unexpected_non_2xx'),
    unexpectedFailureRate: rateValue(data, 'seat_hold_unexpected_failure'),
    holdDurationMs: trendValues(data, 'seat_hold_duration'),
    maxObservedVus: gaugeMaximum(data, 'vus'),
    maxAllocatedVus: gaugeMaximum(data, 'vus_max'),
    preAllocatedVus: PRE_ALLOCATED_VUS,
    configuredMaxVus: MAX_VUS,
  };

  return {
    stdout: `SEAT_HOLD_RESULT ${JSON.stringify(result)}\n`,
  };
}

function selectSeat(fixture, iteration) {
  if (TEST_SCENARIO === 'hot-seat') {
    return 'R001-S001';
  }
  if (TEST_SCENARIO === 'hot-section') {
    return seatNumber(1, (iteration % fixture.seatsPerRow) + 1);
  }

  const seatIndex = iteration % fixture.totalSeats;
  const row = Math.floor(seatIndex / fixture.seatsPerRow) + 1;
  const number = (seatIndex % fixture.seatsPerRow) + 1;
  return seatNumber(row, number);
}

function seatNumber(row, number) {
  return `R${String(row).padStart(3, '0')}-S${String(number).padStart(3, '0')}`;
}

function metricValues(data, name) {
  return data.metrics[name] ? data.metrics[name].values : {};
}

function counterValue(data, name) {
  return Number(metricValues(data, name).count || 0);
}

function rateValue(data, name) {
  return Number(metricValues(data, name).rate || 0);
}

function gaugeMaximum(data, name) {
  return Number(metricValues(data, name).max || 0);
}

function trendValues(data, name) {
  const values = metricValues(data, name);
  return {
    average: Number(values.avg || 0),
    median: Number(values.med || 0),
    p95: Number(values['p(95)'] || 0),
    maximum: Number(values.max || 0),
  };
}
