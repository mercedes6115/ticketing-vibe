/**
 * k6 예매 부하 테스트
 *
 * 사전 조건:
 *   1. docker-compose up -d --build  (백엔드 + 인프라 실행)
 *   2. python scripts/seed_load_test.py --clean  (테스트 데이터 시딩)
 *   3. k6 run scripts/load-test-booking.js
 *
 * 환경 변수:
 *   BASE_URL  (기본: http://localhost:8080)
 *
 * 테스트 흐름:
 *   setup()  → 전체 유저 로그인 → accessToken 배열 반환
 *   default  → 반복마다 고유 (userId, seatId, token) 사용
 *              POST /api/bookings → 202 Accepted
 *              GET  /api/bookings/status/{bookingNo} 폴링 → CONFIRMED
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend, Rate } from 'k6/metrics';
import exec from 'k6/execution';

// ── 커스텀 메트릭 ──────────────────────────────────────────────────────────────
const bookingSuccess  = new Counter('booking_success');
const bookingFailed   = new Counter('booking_failed');
const bookingDuration = new Trend('booking_e2e_ms', true);   // end-to-end (요청~CONFIRMED)
const pollRounds      = new Trend('poll_rounds');
const successRate     = new Rate('booking_success_rate');

// ── 설정 ───────────────────────────────────────────────────────────────────────
const BASE_URL          = __ENV.BASE_URL || 'http://localhost:8080';
const POLL_INTERVAL_SEC = 0.5;   // 0.5초마다 폴링
const POLL_TIMEOUT_MS   = 30000; // 30초 초과 시 실패 처리
const TEST_USERS        = JSON.parse(open('./load_test_data.json'));

export const options = {
  setupTimeout: '5m',
  scenarios: {
    booking_load: {
      executor: 'shared-iterations',
      vus: 200,          // 동시 VU 수
      iterations: 1000,  // seed_load_test.py --users 와 맞춰야 함
      maxDuration: '120s',
    },
  },
  thresholds: {
    booking_success_rate: ['rate>0.95'],          // 성공률 95% 이상
    booking_e2e_ms:       ['p(95)<10000'],        // 95th percentile 10초 이내
    http_req_failed:      ['rate<0.05'],          // HTTP 오류율 5% 미만
  },
};

// ── setup: 전체 유저 로그인 → token 배열 반환 ──────────────────────────────────
export function setup() {
  const users = TEST_USERS;

  console.log(`[setup] ${users.length}명 로그인 시작...`);

  const data = users.map((u, idx) => {
    const res = http.post(
      `${BASE_URL}/api/auth/login`,
      JSON.stringify({ email: u.email, password: u.password }),
      { headers: { 'Content-Type': 'application/json' } },
    );

    if (res.status !== 200) {
      console.error(`[setup] 로그인 실패 (${u.email}): ${res.status} ${res.body}`);
      return null;
    }

    const body = JSON.parse(res.body);
    return { userId: u.userId, seatId: u.seatId, token: body.accessToken };
  });

  const valid = data.filter(x => x !== null);
  console.log(`[setup] 로그인 완료: ${valid.length}/${users.length}`);

  if (valid.length === 0) {
    throw new Error('로그인에 성공한 유저가 없습니다. 백엔드가 실행 중인지 확인하세요.');
  }

  return valid;
}

// ── default: 반복마다 고유 유저+좌석으로 예매 ─────────────────────────────────
export default function (data) {
  const idx = exec.scenario.iterationInTest;
  if (idx >= data.length) {
    console.warn(`[iter ${idx}] 데이터 범위 초과 — 건너뜀`);
    return;
  }

  const { userId, seatId, token } = data[idx];
  const headers = {
    'Content-Type':  'application/json',
    'Authorization': `Bearer ${token}`,
  };

  const t0 = Date.now();

  // ── 1. 예매 요청 (202 Accepted) ────────────────────────────────────────────
  const bookRes = http.post(
    `${BASE_URL}/api/bookings`,
    JSON.stringify({ seatId, paymentMethod: 'CARD' }),
    { headers },
  );

  const accepted = check(bookRes, {
    '202 Accepted': (r) => r.status === 202,
  });

  if (!accepted) {
    bookingFailed.add(1);
    successRate.add(false);
    console.warn(`[iter ${idx}] 예매 요청 실패: ${bookRes.status} — ${bookRes.body}`);
    return;
  }

  const { bookingNo } = JSON.parse(bookRes.body);

  // ── 2. 상태 폴링 (CONFIRMED 대기) ──────────────────────────────────────────
  const deadline = Date.now() + POLL_TIMEOUT_MS;
  let rounds = 0;
  let finalStatus = 'TIMEOUT';

  while (Date.now() < deadline) {
    sleep(POLL_INTERVAL_SEC);
    rounds++;

    const pollRes = http.get(
      `${BASE_URL}/api/bookings/status/${bookingNo}`,
      { headers },
    );

    if (pollRes.status !== 200) continue;

    const { status } = JSON.parse(pollRes.body);
    if (status === 'CONFIRMED' || status === 'FAILED') {
      finalStatus = status;
      break;
    }
  }

  const elapsed = Date.now() - t0;
  bookingDuration.add(elapsed);
  pollRounds.add(rounds);

  const ok = finalStatus === 'CONFIRMED';
  successRate.add(ok);

  if (ok) {
    bookingSuccess.add(1);
  } else {
    bookingFailed.add(1);
    console.warn(`[iter ${idx}] 최종 상태: ${finalStatus} (bookingNo=${bookingNo}, ${elapsed}ms)`);
  }

  check({ finalStatus }, {
    'CONFIRMED': (s) => s.finalStatus === 'CONFIRMED',
  });
}
