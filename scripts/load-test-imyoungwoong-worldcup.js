import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend, Rate } from 'k6/metrics';
import exec from 'k6/execution';

const TEST_DATA = JSON.parse(open('./full_flow_test_data.json'));

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const EVENT_NAME = __ENV.EVENT_NAME || '임영웅 서울월드컵경기장 예매';
const ASSUMED_TOTAL_DEMAND = Number(__ENV.ASSUMED_TOTAL_DEMAND || 500000);
const ASSUMED_TOTAL_SEATS = Number(__ENV.ASSUMED_TOTAL_SEATS || 66704);

const USERS_IN_DATA = TEST_DATA.users.length;
const VUS = Number(__ENV.VUS || Math.min(3000, USERS_IN_DATA));
const ITERATIONS = Number(__ENV.ITERATIONS || USERS_IN_DATA);

const QUEUE_POLL_INTERVAL_SEC = Number(__ENV.QUEUE_POLL_INTERVAL_SEC || 2);
const QUEUE_TIMEOUT_MS = Number(__ENV.QUEUE_TIMEOUT_MS || 180000);
const HOLD_MAX_RETRIES = Number(__ENV.HOLD_MAX_RETRIES || 8);
const HOLD_RETRY_SLEEP_SEC = Number(__ENV.HOLD_RETRY_SLEEP_SEC || 0.2);
const BOOKING_POLL_INTERVAL_SEC = Number(__ENV.BOOKING_POLL_INTERVAL_SEC || 0.5);
const BOOKING_POLL_TIMEOUT_MS = Number(__ENV.BOOKING_POLL_TIMEOUT_MS || 30000);

const queueEnterSuccess = new Counter('queue_enter_success');
const queueEnterFailed = new Counter('queue_enter_failed');
const queueWaitTime = new Trend('queue_wait_ms', true);
const holdAttempts = new Counter('hold_attempts');
const holdSuccess = new Counter('hold_success');
const holdConflict = new Counter('hold_conflict');
const seatExhausted = new Counter('seat_exhausted');
const bookingAccepted = new Counter('booking_accepted');
const bookingConfirmed = new Counter('booking_confirmed');
const bookingFailed = new Counter('booking_failed');
const bookingE2E = new Trend('booking_e2e_ms', true);
const confirmRate = new Rate('confirm_rate');
const queueAdmissionRate = new Rate('queue_admission_rate');
const pollRounds = new Trend('poll_rounds');

export const options = {
  setupTimeout: '10m',
  scenarios: {
    imyoungwoong_worldcup_burst: {
      executor: 'shared-iterations',
      vus: VUS,
      iterations: ITERATIONS,
      maxDuration: __ENV.MAX_DURATION || '20m',
    },
  },
  thresholds: {
    queue_admission_rate: ['rate>0.95'],
    confirm_rate: ['rate>0.85'],
    booking_e2e_ms: ['p(95)<60000'],
    http_req_failed: ['rate<0.10'],
  },
};

export function setup() {
  console.log(
    `[setup] scenario=${EVENT_NAME}, assumedDemand=${ASSUMED_TOTAL_DEMAND}, assumedSeats=${ASSUMED_TOTAL_SEATS}, ` +
    `seedUsers=${TEST_DATA.users.length}, eventId=${TEST_DATA.eventId}, vus=${VUS}, iterations=${ITERATIONS}`,
  );

  const users = TEST_DATA.users.map((u) => ({
    userId: u.userId,
    token: u.accessToken,
  })).filter((u) => typeof u.token === 'string' && u.token.length > 0);

  if (users.length !== TEST_DATA.users.length) {
    throw new Error(
      `Missing pre-generated accessToken in seed data: ${users.length}/${TEST_DATA.users.length}`,
    );
  }

  console.log(`[setup] pre-generated tokens loaded: ${users.length}/${TEST_DATA.users.length}`);
  return { users, eventId: TEST_DATA.eventId };
}

export default function (data) {
  const idx = exec.scenario.iterationInTest;
  if (idx >= data.users.length) return;

  const { token } = data.users[idx];
  const { eventId } = data;
  const headers = {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${token}`,
  };

  const startedAt = Date.now();

  const enterRes = http.post(
    `${BASE_URL}/api/queue/enter`,
    JSON.stringify({ eventId }),
    { headers },
  );

  const entered = check(enterRes, { 'queue enter 200': (r) => r.status === 200 });
  queueAdmissionRate.add(entered);
  if (!entered) {
    queueEnterFailed.add(1);
    bookingFailed.add(1);
    confirmRate.add(false);
    console.warn(`[iter ${idx}] queue enter failed: ${enterRes.status} ${enterRes.body}`);
    return;
  }
  queueEnterSuccess.add(1);

  const queueDeadline = Date.now() + QUEUE_TIMEOUT_MS;
  let tokenIssued = false;

  while (Date.now() < queueDeadline) {
    sleep(QUEUE_POLL_INTERVAL_SEC);

    const tokenRes = http.post(
      `${BASE_URL}/api/queue/token?eventId=${eventId}`,
      null,
      { headers },
    );

    if (tokenRes.status === 200) {
      tokenIssued = true;
      break;
    }
  }

  if (!tokenIssued) {
    bookingFailed.add(1);
    confirmRate.add(false);
    console.warn(`[iter ${idx}] queue token timeout`);
    return;
  }

  queueWaitTime.add(Date.now() - startedAt);

  let heldSeatId = null;

  for (let attempt = 0; attempt < HOLD_MAX_RETRIES; attempt++) {
    const seatsRes = http.get(`${BASE_URL}/api/events/${eventId}/seats`, { headers });
    if (seatsRes.status !== 200) {
      console.warn(`[iter ${idx}] seats fetch failed: ${seatsRes.status}`);
      break;
    }

    const seats = JSON.parse(seatsRes.body);
    const available = seats.filter((s) => s.status === 'AVAILABLE');

    if (available.length === 0) break;

    // Popular zone contention simulation: bias toward front portion of the remaining seats.
    const hotspotPoolSize = Math.max(1, Math.ceil(available.length * 0.1));
    const hotspotPool = available.slice(0, hotspotPoolSize);
    const target = hotspotPool[Math.floor(Math.random() * hotspotPool.length)];

    holdAttempts.add(1);
    const holdRes = http.post(`${BASE_URL}/api/seats/${target.id}/hold`, null, { headers });

    if (holdRes.status === 200) {
      heldSeatId = target.id;
      holdSuccess.add(1);
      break;
    }

    holdConflict.add(1);
    sleep(HOLD_RETRY_SLEEP_SEC);
  }

  if (heldSeatId === null) {
    seatExhausted.add(1);
    confirmRate.add(false);
    return;
  }

  const bookRes = http.post(
    `${BASE_URL}/api/bookings`,
    JSON.stringify({ seatId: heldSeatId, paymentMethod: 'CARD' }),
    { headers },
  );

  const accepted = check(bookRes, { 'booking accepted 202': (r) => r.status === 202 });
  if (!accepted) {
    bookingFailed.add(1);
    confirmRate.add(false);
    console.warn(`[iter ${idx}] booking failed: ${bookRes.status} ${bookRes.body}`);
    return;
  }
  bookingAccepted.add(1);

  const { bookingNo } = JSON.parse(bookRes.body);
  const pollDeadline = Date.now() + BOOKING_POLL_TIMEOUT_MS;
  let rounds = 0;
  let finalStatus = 'TIMEOUT';

  while (Date.now() < pollDeadline) {
    sleep(BOOKING_POLL_INTERVAL_SEC);
    rounds++;

    const pollRes = http.get(`${BASE_URL}/api/bookings/status/${bookingNo}`, { headers });
    if (pollRes.status !== 200) continue;

    const { status } = JSON.parse(pollRes.body);
    if (status === 'CONFIRMED' || status === 'FAILED') {
      finalStatus = status;
      break;
    }
  }

  pollRounds.add(rounds);
  const elapsed = Date.now() - startedAt;
  const ok = finalStatus === 'CONFIRMED';
  confirmRate.add(ok);

  if (ok) {
    bookingConfirmed.add(1);
    bookingE2E.add(elapsed);
  } else {
    bookingFailed.add(1);
    console.warn(`[iter ${idx}] final status=${finalStatus}, bookingNo=${bookingNo}, elapsed=${elapsed}ms`);
  }
}
