import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend, Rate } from 'k6/metrics';
import exec from 'k6/execution';

const TEST_DATA = JSON.parse(open('./full_flow_test_data.json'));

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const VUS = Number(__ENV.VUS || 100);
const ITERATIONS = Number(__ENV.ITERATIONS || TEST_DATA.users.length);

const QUEUE_POLL_INTERVAL_SEC = Number(__ENV.QUEUE_POLL_INTERVAL_SEC || 2);
const QUEUE_TIMEOUT_MS = Number(__ENV.QUEUE_TIMEOUT_MS || 180000);
const HOLD_MAX_RETRIES = Number(__ENV.HOLD_MAX_RETRIES || 5);
const BOOKING_POLL_INTERVAL_SEC = Number(__ENV.BOOKING_POLL_INTERVAL_SEC || 0.5);
const BOOKING_POLL_TIMEOUT_MS = Number(__ENV.BOOKING_POLL_TIMEOUT_MS || 30000);

const queueWaitTime = new Trend('queue_wait_ms', true);
const holdAttempts = new Counter('hold_attempts');
const holdSuccess = new Counter('hold_success');
const holdConflict = new Counter('hold_conflict');
const seatExhausted = new Counter('seat_exhausted');
const bookingConfirmed = new Counter('booking_confirmed');
const bookingFailed = new Counter('booking_failed');
const bookingE2E = new Trend('booking_e2e_ms', true);
const confirmRate = new Rate('confirm_rate');
const pollRounds = new Trend('poll_rounds');

export const options = {
  setupTimeout: '5m',
  scenarios: {
    full_flow: {
      executor: 'shared-iterations',
      vus: VUS,
      iterations: ITERATIONS,
      maxDuration: '600s',
    },
  },
  thresholds: {
    booking_e2e_ms: ['p(95)<60000'],
  },
};

export function setup() {
  console.log(`[setup] eventId=${TEST_DATA.eventId}, users=${TEST_DATA.users.length}`);

  const users = TEST_DATA.users.map((u) => {
    const res = http.post(
      `${BASE_URL}/api/auth/login`,
      JSON.stringify({ email: u.email, password: u.password }),
      { headers: { 'Content-Type': 'application/json' } },
    );

    if (res.status !== 200) {
      console.error(`[setup] login failed: ${u.email}, status=${res.status}, body=${res.body}`);
      return null;
    }

    const body = JSON.parse(res.body);
    return { userId: u.userId, token: body.accessToken };
  }).filter((x) => x !== null);

  if (users.length === 0) {
    throw new Error('No users logged in successfully.');
  }

  console.log(`[setup] login complete: ${users.length}/${TEST_DATA.users.length}`);
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

  if (!check(enterRes, { 'queue enter 200': (r) => r.status === 200 })) {
    bookingFailed.add(1);
    confirmRate.add(false);
    console.warn(`[iter ${idx}] queue enter failed: ${enterRes.status} ${enterRes.body}`);
    return;
  }

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

    const target = available[Math.floor(Math.random() * available.length)];
    holdAttempts.add(1);

    const holdRes = http.post(`${BASE_URL}/api/seats/${target.id}/hold`, null, { headers });

    if (holdRes.status === 200) {
      heldSeatId = target.id;
      holdSuccess.add(1);
      break;
    }

    holdConflict.add(1);
    sleep(0.3);
  }

  if (heldSeatId === null) {
    seatExhausted.add(1);
    return;
  }

  const bookRes = http.post(
    `${BASE_URL}/api/bookings`,
    JSON.stringify({ seatId: heldSeatId, paymentMethod: 'CARD' }),
    { headers },
  );

  if (!check(bookRes, { 'booking accepted 202': (r) => r.status === 202 })) {
    bookingFailed.add(1);
    confirmRate.add(false);
    console.warn(`[iter ${idx}] booking failed: ${bookRes.status} ${bookRes.body}`);
    return;
  }

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
