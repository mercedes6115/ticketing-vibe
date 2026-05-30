# 성능 테스트 보고서

> 이 문서는 부하 테스트를 통해 무엇을 검증했고, 어떤 병목을 발견했고, 어떤 변경이 실제로 효과가 있었는지를 수치로 남기기 위한 문서입니다.

| 빠른 정보 | 내용 |
|-----------|------|
| 대표 시나리오 | `scripts/load-test-full-flow.js` |
| 보조 시나리오 | `scripts/load-test-booking.js` |
| 핵심 지표 | `confirm_rate`, `booking_e2e_ms p95`, `queue_wait_ms p95`, `http_req_failed` |
| 함께 읽을 문서 | [시스템 아키텍처](architecture.md), [설계 트레이드오프](tradeoffs.md), [README](../README.md) |

## 실험 스냅샷

| 구간 | 핵심 결과 |
|------|-----------|
| 기준선 | `confirm_rate 82.78%`, `booking_e2e_ms p95 15.26s` |
| 2차 비교 | `confirm_rate 96.01%`, `booking_e2e_ms p95 11.37s` |
| 효과 있었던 변경 후 | `confirm_rate 99.64%`, `queue_wait_ms p95 3.14s` |

## 문서 구성

- [테스트 목적](#1-테스트-목적)
- [테스트 환경](#2-테스트-환경)
- [목표 지표](#3-목표-지표)
- [테스트 시나리오](#4-테스트-시나리오)
- [실행 방법](#5-실행-방법)
- [수집 메트릭](#6-수집-메트릭)
- [결과 요약](#7-결과-요약)

## 1. 테스트 목적

이 프로젝트에서 성능 테스트로 확인하려는 항목은 다음과 같다.

- 대기열 진입 요청이 몰려도 순번 부여와 토큰 발급이 안정적으로 동작하는가
- 같은 좌석에 대한 경합 요청이 중복 예매 없이 처리되는가
- 예매 API를 Kafka 비동기 처리로 분리했을 때 핫 구간 응답 경로가 짧아지는가
- Redis, DB, Kafka, HTTP 레벨 중 어디에서 병목이 생기는지 관찰 가능한가
- 예매 완료까지의 end-to-end 시간이 사용자 관점에서 허용 가능한 수준인지 확인할 수 있는가

## 2. 테스트 환경

### 애플리케이션 구성

- Frontend: `http://localhost:5173`
- Backend API: `http://localhost:8080`
- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3000`

### 인프라 구성

- Spring Boot 3.2 / Java 17
- MySQL 8
- Redis 7 + Sentinel
- Kafka 3.7 (KRaft)
- Prometheus + Grafana
- Docker Compose 기반 로컬 환경

참고 문서:

- [시스템 아키텍처](architecture.md)
- [접속 정보](access.md)
- [Grafana 대시보드 JSON](../monitoring/grafana/dashboard-json/ticketing.json)

### 환경 조건 기록

실제 측정 때 아래 값을 함께 채운다.

| 항목 | 1차 결과 | 2차 결과 |
|------|-----------|-----------|
| 테스트 일시 | 2026-05-26 | 2026-05-26 |
| 테스트 환경 | local | local |
| CPU / Memory |  |  |
| Docker Desktop / Engine 버전 |  |  |
| JVM 옵션 |  |  |
| Backend commit / 버전 |  |  |
| 데이터셋 크기 | 이벤트 1개 / 좌석 200개 / 사용자 1000명 | 이벤트 1개 / 좌석 500개 / 사용자 1000명 |

## 3. 목표 지표

저장소에 포함된 k6 스크립트 기준으로 우선 아래 목표를 사용한다.

| 지표 | 목표 |
|------|------|
| `booking_success_rate` | 95% 이상 |
| `booking_e2e_ms p95` | 10초 이하 |
| `http_req_failed` | 5% 미만 |
| 전체 API 응답시간 p95 | 500ms 이하 목표 |
| 예매 상태 확정까지 polling round | 과도한 증가 여부 확인 |
| Kafka consumer lag | 필요 시 보조 관찰 |

주의:

- `booking_e2e_ms`는 "예매 요청 시작부터 `CONFIRMED` 확인까지"의 end-to-end 시간이다.
- API 응답시간과 예매 완료시간은 다른 지표다. 둘을 분리해서 봐야 한다.

## 4. 테스트 시나리오

### 시나리오 A. 예매 비동기 처리 파이프라인 부분 테스트

스크립트: [scripts/load-test-booking.js](../scripts/load-test-booking.js)

검증 의도:

- `/api/bookings`가 `202 Accepted`를 빠르게 반환하는지 확인
- Consumer 처리 지연이 전체 완료 시간에 어떤 영향을 주는지 확인
- 예매 성공률과 polling 횟수를 함께 기록

전제 조건:

- 이 시나리오는 실제 사용자 전체 흐름을 재현하지 않는다.
- 시드 스크립트가 좌석을 미리 `HOLD` 상태로 만들고 Redis `seat:hold:{seatId}` 키도 준비해야 한다.
- 따라서 이 테스트는 대기열, 좌석 경합, TTL 만료 복구가 아니라 "예매 요청 이후 비동기 확정 경로"만 분리 측정하는 용도다.

기본 설정:

- VUs: 200
- Iterations: 1000
- Max Duration: 120초
- Poll interval: 0.5초
- Poll timeout: 30초

기본 threshold:

- `booking_success_rate > 0.95`
- `booking_e2e_ms p95 < 10000`
- `http_req_failed rate < 0.05`

### 시나리오 B. 대기열 포함 대표 사용자 시나리오 테스트

스크립트: [scripts/load-test-full-flow.js](../scripts/load-test-full-flow.js)

검증 의도:

- 대기열 진입부터 토큰 발급, 좌석 조회, 좌석 홀드, 예매 완료까지 전체 흐름 검증
- 대기열 구간과 예매 구간 중 어디가 병목인지 분리 관찰
- 좌석 경합 및 좌석 소진 상황을 함께 확인

주의:

- 현재 이 시나리오는 SSE 스트림 자체를 검증하지 않고, 토큰 발급 API를 polling하는 방식으로 진행한다.
- 대기열 시나리오로 보려면 테스트 사용자 수가 `MAX_ENTER_COUNT`보다 충분히 커야 한다.
- 기본 샘플 데이터가 100명 수준이면 대기열 지연은 거의 발생하지 않을 수 있다.

관찰 지표:

- `queue_wait_ms`
- `hold_attempts`
- `hold_success`
- `hold_conflict`
- `seat_exhausted`
- `booking_confirmed`
- `booking_failed`
- `booking_e2e_ms`
- `confirm_rate`
- `poll_rounds`

기본 threshold:

- `booking_e2e_ms p95 < 60000`

## 5. 실행 방법

### 1. 인프라 및 애플리케이션 실행

```bash
docker compose up -d
```

### 2. 테스트 데이터 준비

1차 기준선:

```bash
py -3 scripts/seed_full_flow.py --users 1000 --seats 200 --clean
```

2차 비교 실험:

```bash
py -3 scripts/seed_full_flow.py --users 1000 --seats 500 --clean
```

### 3. 대표 시나리오 실행

```bash
k6 run -e VUS=150 -e ITERATIONS=1000 scripts/load-test-full-flow.js
```

### 4. 보조 시나리오 실행

```bash
k6 run scripts/load-test-booking.js
```

## 6. 수집 메트릭

### 핵심 기록 항목

- `confirm_rate`
- `booking_e2e_ms p95`
- `queue_wait_ms p95`
- `http_req_failed`
- `hold_success`
- `hold_conflict`
- `seat_exhausted`
- `poll_rounds avg`

### 선택 관찰 항목

- HTTP TPS
- HTTP 응답시간 p50 / p95 / p99
- Kafka consumer lag
- HikariCP active / idle / pending
- Tomcat busy threads
- JVM heap / CPU

대시보드 참조:

- [Ticketing Load Test Dashboard](../monitoring/grafana/dashboard-json/ticketing.json)

## 7. 결과 요약

### 1차 기준선

| 항목 | 결과 | 목표 충족 여부 |
|------|------|----------------|
| 예매 성공률 | 82.78% | 아니오 |
| 예매 완료시간 p95 | 15.26s | 아니오 |
| HTTP 실패율 | 0.63% | 예 |
| 전체 응답시간 p95 | 571.68ms | 아니오 |
| Kafka lag 최대값 | 선택 관찰 항목 | 선택 |
| HikariCP pending 최대값 | 선택 관찰 항목 | 선택 |

### 2차 결과

| 항목 | 결과 | 목표 충족 여부 |
|------|------|----------------|
| 예매 성공률 | 96.01% | 예 |
| 예매 완료시간 p95 | 11.37s | 아니오 |
| HTTP 실패율 | 0.22% | 예 |
| 전체 응답시간 p95 | 195.11ms | 예 |
| Kafka lag 최대값 | 선택 관찰 항목 | 선택 |
| HikariCP pending 최대값 | 선택 관찰 항목 | 선택 |

### 1차 / 2차 비교

| 지표 | 1차 | 2차 | 변화 |
|------|-----|-----|------|
| 좌석 수 | 200 | 500 | +300 |
| `confirm_rate` | 82.78% | 96.01% | 개선 |
| `booking_e2e_ms p95` | 15.26s | 11.37s | 개선 |
| `queue_wait_ms p95` | 2.07s | 2.22s | 유사 |
| `seat_exhausted` | 756 | 473 | 감소 |
| `hold_conflict` | 35 | 14 | 감소 |
| `poll_rounds avg` | 8.785714 | 4.830153 | 감소 |
| `http_req_failed` | 0.63% | 0.22% | 개선 |
| `http_req_duration p95` | 571.68ms | 195.11ms | 개선 |

## 8. 대표 시나리오 상세 결과

### 시나리오 B 1차 결과

| 지표 | 결과 |
|------|------|
| VUs | 150 |
| Iterations | 1000 |
| `queue_wait_ms p95` | 2.07s |
| `hold_success` | 239 |
| `hold_conflict` | 35 |
| `seat_exhausted` | 756 |
| `confirm_rate` | 82.78% |
| `booking_e2e_ms p95` | 15.26s |
| `poll_rounds avg` | 8.785714 |

### 시나리오 B 2차 결과

| 지표 | 결과 |
|------|------|
| VUs | 150 |
| Iterations | 1000 |
| `queue_wait_ms p95` | 2.22s |
| `hold_success` | 525 |
| `hold_conflict` | 14 |
| `seat_exhausted` | 473 |
| `confirm_rate` | 96.01% |
| `booking_e2e_ms p95` | 11.37s |
| `poll_rounds avg` | 4.830153 |

## 9. 해석

### 1차 결과 해석

- `queue_wait_ms p95`는 2.07초로, 1차 기준선에서는 대기열 구간 자체가 가장 큰 병목으로 보이지 않았다.
- `seat_exhausted 756`이 매우 높아 많은 사용자가 좌석 부족 상태를 경험했다.
- `confirm_rate 82.78%`로 목표치 95%를 만족하지 못했다.
- `booking_e2e_ms p95 15.26s`로 목표치 10초를 초과했다.
- 즉 1차 결과는 "시스템 처리 한계"와 "좌석 부족 효과"가 함께 섞인 상태였다.

### 2차 결과 해석

- 좌석 수를 200개에서 500개로 늘리자 `confirm_rate`가 `82.78% -> 96.01%`로 올라 목표치를 넘었다.
- `seat_exhausted`가 `756 -> 473`, `hold_conflict`가 `35 -> 14`로 줄었다.
- `poll_rounds avg`도 `8.79 -> 4.83`으로 줄어 예매 확정까지의 대기 횟수가 감소했다.
- `http_req_failed`와 `http_req_duration p95`도 모두 개선됐다.
- 반면 `queue_wait_ms p95`는 `2.07s -> 2.22s`로 거의 비슷해, 이번 비교에서 핵심 변수는 대기열보다 좌석 수 조건에 가까웠다.
- 따라서 1차 결과의 큰 원인은 시스템 병목만이 아니라 좌석 부족 조건이 과하게 섞여 있었던 것으로 해석할 수 있다.
- 다만 `booking_e2e_ms p95 11.37s`는 여전히 목표인 10초 이하를 약간 초과하므로, 성공률은 개선됐지만 완료시간 최적화는 다음 과제로 남는다.

### 이번 실험으로 확인한 점

- 이 프로젝트의 첫 문제는 "무조건 시스템이 느리다"가 아니었다.
- 좌석 부족 조건이 심할 때 `seat_exhausted`, `hold_conflict`, `confirm_rate`가 함께 나빠졌다.
- 좌석 수를 늘려 비교하자 성공률과 완료시간이 함께 개선됐고, 좌석 부족 효과를 어느 정도 분리할 수 있었다.
- 이제 다음 개선은 좌석 수 조건이 아니라 예매 확정 경로 자체의 완료시간 최적화에 집중하는 것이 더 타당하다.

## 10. 개선 이력 기록

| 날짜 | 변경 내용 | 기대 효과 | 결과 |
|------|-----------|-----------|------|
| 2026-05-26 | `full_flow` 1차 기준선 측정 | 현재 병목 구간 파악 | `confirm_rate 82.78%`, `booking_e2e_ms p95 15.26s` |
| 2026-05-26 | 좌석 수 `200 -> 500`으로 변경 후 `full_flow` 재실행 | 좌석 부족 영향 분리 | `confirm_rate 96.01%`, `booking_e2e_ms p95 11.37s` |
| 2026-05-26 | `persistBookingRequest()`에서 `existsBySeatIdAndStatusIn` 중복 확인 쿼리 제거 | 좌석 row 락 이후 추가 DB round trip 제거, `persistMs` 감소 기대 | `confirm_rate 99.64%`, `booking_e2e_ms p95 16.57s` |
| 2026-05-26 | `persistBookingRequest()`에서 `findByIdWithEventForUpdate -> findByIdWithLock` 변경 | 좌석 row 락 구간에서 불필요한 `JOIN FETCH event` 제거, 락 유지 시간 감소 기대 | 실패 - `LazyInitializationException`, 롤백 |
| 2026-05-26 | `persistBookingRequest()` / `cancelBooking()`에서 `availableSeats` 즉시 갱신 제거 | 이벤트 row 핫스팟 완화, `persistMs` 감소 기대. 대신 이벤트 상세의 `availableSeats`는 일시적으로 stale 가능 | `confirm_rate 90.12%`, `booking_e2e_ms p95 19.41s` |
| YYYY-MM-DD | Kafka consumer concurrency 조정 | 완료시간 단축 | |
| YYYY-MM-DD | Redis 접근 경로 최적화 | 대기 지연 감소 | |
| YYYY-MM-DD | DB 쿼리 개선 | 예매 완료시간 단축 | |

## 11. 현재 상태

저장소에는 다음 자산이 이미 포함되어 있다.

- k6 예매 부하 테스트 스크립트
- k6 전체 플로우 부하 테스트 스크립트
- Prometheus 스크랩 설정
- Grafana 대시보드 JSON

현재는 `load-test-full-flow.js` 기준으로 1차 기준선과 2차 비교 실험 결과를 모두 문서화한 상태다.

- 1차: 좌석 200개 조건에서 `confirm_rate 82.78%`, `booking_e2e_ms p95 15.26s`
- 2차: 좌석 500개 조건에서 `confirm_rate 96.01%`, `booking_e2e_ms p95 11.37s`

다음 단계는 예매 확정 경로의 완료시간을 더 줄이기 위한 코드/설정 개선을 적용하고, 같은 시나리오로 3차 결과를 비교하는 것이다.

## 12. 다음 테스트 계획

### 목표

3차 테스트의 목표는 `booking_e2e_ms p95 11.37s` 중 어떤 구간이 가장 큰 지연을 만드는지 분해하는 것이다.

현재는 end-to-end 시간만 보이기 때문에 "느리다"는 사실은 알 수 있지만, 아래 중 어디가 긴지는 아직 분리되지 않았다.

- Kafka에 메시지가 적재된 뒤 consumer가 가져오기까지
- consumer가 DB 확정 처리를 끝내기까지
- Redis 상태를 `CONFIRMED`로 반영하기까지
- 클라이언트 polling이 `CONFIRMED`를 관측하기까지

### 코드 로그 포인트

이번 단계에서 아래 로그를 추가했다.

- `BookingService.createBooking`
  - `acceptedLatencyMs`
  - `requestedAt`
- `BookingRequestConsumer.consume`
  - `queueDelayMs`
  - `persistMs`
  - `statusUpdateMs`
  - `consumerWorkMs`
  - `totalAsyncMs`

로그 해석 기준:

- `queueDelayMs`
  - API 수락 후 consumer가 메시지를 실제로 가져오기까지의 대기 시간
- `persistMs`
  - consumer가 DB 확정, 결제 저장, 좌석 상태 변경을 처리하는 시간
- `statusUpdateMs`
  - Redis에 `CONFIRMED` 상태를 기록하는 시간
- `consumerWorkMs`
  - consumer 메서드 전체 작업 시간
- `totalAsyncMs`
  - `requestedAt`부터 consumer 처리 완료까지의 총 비동기 시간

### 실행 절차

1. 백엔드 재빌드 후 실행

```bash
docker compose up -d --build
```

2. 2차와 같은 조건으로 데이터 재생성

```bash
py -3 scripts/seed_full_flow.py --users 1000 --seats 500 --clean
```

3. 대표 시나리오 재실행

```bash
k6 run -e VUS=150 -e ITERATIONS=1000 scripts/load-test-full-flow.js
```

4. 백엔드 로그에서 `bookingNo` 기준으로 아래 값을 수집

- `acceptedLatencyMs`
- `queueDelayMs`
- `persistMs`
- `statusUpdateMs`
- `totalAsyncMs`

### 판단 기준

- `queueDelayMs`가 길면 Kafka 적재 이후 소비 대기 구간이 병목일 가능성이 크다.
- `persistMs`가 길면 consumer 내부 DB 처리, 결제 저장, 좌석 상태 변경이 병목일 가능성이 크다.
- `statusUpdateMs`는 보통 짧아야 하며, 여기서 길면 Redis 접근 경로를 의심해야 한다.
- `totalAsyncMs`는 `booking_e2e_ms`보다 짧아야 정상이며, 둘의 차이가 크면 polling 관측 지연이 크다는 뜻이다.

### 3차 결과 후 예상 다음 액션

- `queueDelayMs`가 크면
  - Kafka consumer 설정, concurrency, lag 확인
- `persistMs`가 크면
  - DB 쿼리, 락 범위, 저장 횟수, 트랜잭션 경로 점검
- `totalAsyncMs`는 짧은데 `booking_e2e_ms`가 여전히 길면
  - polling interval 또는 상태 확인 방식 개선 검토

## 13. 3차 테스트 결과

### 실행 일시

- 2026-05-26 12:24 KST 전후

### k6 결과 요약

- `confirm_rate`: `65.24%`
- `booking_e2e_ms p95`: `40.13s`
- `queue_wait_ms p95`: `4.77s`
- `http_req_failed`: `0.67%`
- `poll_rounds avg`: `31.51`

2차 결과와 비교하면 명확한 성능 회귀다.

- `confirm_rate`: `96.01% -> 65.24%`
- `booking_e2e_ms p95`: `11.37s -> 40.13s`
- `queue_wait_ms p95`: `2.22s -> 4.77s`

### 로그 기준 핵심 관찰

- `failed(non-retryable)` 로그는 3차 구간에서 `57건` 확인됐다.
- `Seat already has an active booking` 같은 비즈니스 실패는 이제 `FAILED`로 기록되고 재throw되지 않았다.
- 이전처럼 같은 `bookingNo`, 같은 `partition`, 같은 `offset`이 계속 반복 재소비되는 패턴은 확인되지 않았다.
- 즉 비재시도 예외를 별도 처리한 변경은 의도대로 동작한 것으로 보인다.

### 완료 로그 관찰

완료 로그에서는 다음 패턴이 반복됐다.

- `queueDelayMs`가 자주 `20s ~ 40s`까지 증가
- `persistMs`도 일부 구간에서 `3s ~ 14s` 수준까지 상승
- `statusUpdateMs`는 상대적으로 작아 핵심 병목으로 보이지 않음

대표 예시:

- `queueDelayMs=3864`, `persistMs=12915`, `totalAsyncMs=16843`
- `queueDelayMs=7751`, `persistMs=14362`, `totalAsyncMs=22182`
- `queueDelayMs=31645`, `persistMs=3037`, `totalAsyncMs=34711`
- `queueDelayMs=40460`, `persistMs=1189`, `totalAsyncMs=41650`

### 해석

- 이번 3차 테스트에서는 비재시도 실패의 무한 재소비 문제는 줄었다.
- 그러나 전체 성능은 오히려 악화됐다.
- 즉 현재 병목은 "같은 실패 메시지의 재시도 루프" 하나로 설명되지 않는다.
- 완료 로그 기준으로 보면 `booking_e2e_ms` 악화의 주 원인은 `consumer 처리 대기(queueDelayMs)`와 `DB 영속화 구간(persistMs)`이 동시에 커진 데 있다.
- `statusUpdateMs`는 대부분 작아 Redis 상태 반영은 상대적으로 주요 병목이 아니다.

### 이번 테스트로 확인한 점

- `Seat already has an active booking`을 비재시도 예외로 분리한 수정은 효과가 있었다.
- 하지만 이 수정만으로는 `booking_e2e_ms p95 40.13s` 수준의 지연을 해결할 수 없다.
- 현재는 Kafka consumer가 메시지를 가져오기 전 대기 시간과, 가져온 뒤 DB 처리 시간이 함께 커지고 있다.
- 따라서 다음 단계의 초점은 비즈니스 실패 분기보다 `consumer 처리량`과 `persist 경로 최적화`에 맞춰야 한다.

### 다음 개선 방향

- consumer concurrency와 실제 partition 활용 상태 점검
- `persistBookingRequest()` 내부 DB 조회/락/저장 경로 점검
- `seat`, `booking`, `payment`, `event` 갱신 구간의 쿼리 수와 락 범위 확인
- `existsBySeatIdAndStatusIn`, `findByIdWithEventForUpdate`, `decreaseAvailableSeats` 주변 병목 측정
- 필요하면 DB 인덱스, 트랜잭션 범위, 저장 횟수 축소를 우선 검토

### 3차 결과 한 줄 요약

3차 테스트에서는 비재시도 실패의 반복 재소비는 줄었지만, `consumer 대기 지연 + persist 구간 지연`이 커지며 전체 성능이 2차보다 악화된 것으로 확인됐다.

## 14. 4차 테스트 결과

### 적용한 변경

- `Idempotency` 기준은 `bookingNo`로 유지한 채 `persistBookingRequest()`에서 `existsBySeatIdAndStatusIn` 중복 확인 쿼리 제거

### k6 결과 요약

- `confirm_rate`: `99.64%`
- `booking_e2e_ms p95`: `16.57s`
- `queue_wait_ms p95`: `3.14s`
- `http_req_failed`: `0.52%`
- `poll_rounds avg`: `7.64`
- `booking_failed`: `2`

### 3차 대비 변화

- `confirm_rate`: `65.24% -> 99.64%`
- `booking_e2e_ms p95`: `40.13s -> 16.57s`
- `queue_wait_ms p95`: `4.77s -> 3.14s`
- `poll_rounds avg`: `31.51 -> 7.64`
- `booking_failed`: `196 -> 2`

### 해석

- 불필요한 중복 확인 쿼리 제거만으로도 성능이 크게 개선됐다.
- `persist` 경로의 DB round trip 하나가 실제로 의미 있는 비용이었다는 점을 확인했다.
- 다만 `booking_e2e_ms p95 16.57s`는 여전히 목표인 `10초 이하`를 넘는다.
- 따라서 다음 단계는 락 구간 자체를 더 가볍게 만들어 `persistMs`와 뒤쪽 `queueDelayMs`를 함께 줄이는 데 초점을 둔다.

### 다음 개선 항목

- `findByIdWithEventForUpdate()` 대신 `findByIdWithLock()` 사용
- 좌석 락 구간에서 `event` fetch join 제거
- 이후 같은 시나리오로 재측정해 `persistMs`와 `booking_e2e_ms` 변화 확인

## 15. 5차 테스트 결과

### 적용한 변경

- `persistBookingRequest()`에서 `findByIdWithEventForUpdate()`를 `findByIdWithLock()`으로 변경

### k6 결과 요약

- `confirm_rate`: `0.00%`
- `booking_confirmed`: `0`
- `booking_failed`: `523`
- `booking_e2e_ms p95`: `0s`
- `poll_rounds avg`: `53.21`
- `queue_wait_ms p95`: `3.66s`

### 로그 기준 원인

- consumer에서 `LazyInitializationException`이 반복 발생했다.
- 대표 에러:
  - `could not initialize proxy [com.ticketing.entity.Event#16] - no Session`
- 이 예외 때문에 예매 요청이 `CONFIRMED`로 끝나지 못하고 `DefaultErrorHandler / DLQ` 경로로 처리되었다.

### 해석

- `findByIdWithLock()`로 바꾸면서 좌석의 `event` 연관 객체가 lazy proxy 상태로 남았고, 이후 `toBookingEvent()` 경로에서 세션 바깥 접근이 발생했다.
- 즉 이번 변경은 성능 최적화가 아니라 기능 회귀를 만들었다.
- 따라서 `event fetch join` 제거는 현재 구조에서는 유효하지 않은 개선으로 판단하고 롤백한다.

### 조치

- `findByIdWithEventForUpdate()` 사용으로 즉시 롤백
- 이번 시도는 "실패한 최적화"로 기록하고, 이후 병목 개선은 다른 경로에서 진행

## 16. 6차 테스트 계획

### 적용한 변경

- `persistBookingRequest()`에서 `eventRepository.decreaseAvailableSeats()` 제거
- `cancelBooking()`에서 `eventRepository.increaseAvailableSeats()` 제거

### 의도

- 단일 이벤트에 요청이 몰릴 때 `events` row의 `availableSeats` 갱신이 핫스팟이 되는지 확인
- 좌석 상태(`Seat.status`)는 그대로 유지하면서, 이벤트 카운터 갱신만 크리티컬 패스에서 제외

### 감수한 비용

- 이벤트 상세/목록 응답의 `availableSeats` 값은 실시간 좌석 상태와 잠시 어긋날 수 있다.
- 이번 단계는 성능 병목 확인을 위한 실험이며, 정확한 잔여 좌석 수는 이후 Redis 카운터나 비동기 반영 전략으로 보완 가능하다.

## 17. 6차 테스트 결과

### 적용한 변경

- `persistBookingRequest()`에서 `eventRepository.decreaseAvailableSeats()` 제거
- `cancelBooking()`에서 `eventRepository.increaseAvailableSeats()` 제거

### k6 결과 요약

- `confirm_rate`: `90.12%`
- `booking_e2e_ms p95`: `19.41s`
- `queue_wait_ms p95`: `3.58s`
- `http_req_failed`: `0.98%`
- `poll_rounds avg`: `11.5`
- `booking_failed`: `55`

### 4차 대비 변화

- `confirm_rate`: `99.64% -> 90.12%`
- `booking_e2e_ms p95`: `16.57s -> 19.41s`
- `queue_wait_ms p95`: `3.14s -> 3.58s`
- `poll_rounds avg`: `7.64 -> 11.5`
- `booking_failed`: `2 -> 55`

### 해석

- `availableSeats` 즉시 갱신 제거만으로는 기대한 개선이 나오지 않았다.
- 오히려 4차 대비 성공률과 완료시간이 모두 악화됐다.
- 따라서 현재 병목의 주원인을 `events.availableSeats` 단일 row 업데이트로 단정하기는 어렵다.
- 이 변경은 성능상 유의미한 개선책으로 보기 어렵고, 적어도 현재 구조와 시나리오에서는 우선순위가 낮다.

### 결론

- 지금까지 시도 중 가장 효과가 컸던 변경은 `existsBySeatIdAndStatusIn` 제거였다.
- 반면 `event fetch join` 제거 시도는 기능 회귀를 만들었고, `availableSeats` 즉시 갱신 제거 시도는 성능 개선으로 이어지지 않았다.
- 다음 단계는 consumer concurrency, DB 인덱스, `payment save` 분리 같은 다른 병목 후보를 보는 편이 더 타당하다.

## 18. 임영웅 20만 시도 중단 원인

### 상황 정리

- 임영웅 / 서울월드컵경기장(66,704석) 시나리오 기준으로 전용 시드와 전용 k6 스크립트를 구성했다.
- 로그인 API가 병목이 되는 것을 피하기 위해, 전용 시드에서 각 유저의 `accessToken`을 미리 생성하고 k6 `setup()`은 로그인 없이 토큰만 로드하도록 변경했다.
- 이후 `users=200000` 규모 테스트를 시도했지만, 테스트는 서버 포화 전에 **k6 메모리 부족**으로 중간 종료됐다.

### 현재 해석

- 이번 실패는 애플리케이션 서버 자체의 처리 한계라기보다 **단일 부하 발생기 한 대의 메모리 한계**가 먼저 드러난 사례로 해석한다.
- 현재 구조는 `full_flow_test_data.json` 전체를 한 번에 `open() + JSON.parse()`로 적재하고, 대규모 유저/토큰 데이터를 그대로 메모리에 유지한 채 테스트를 수행한다.
- 로그인 제거는 인증 부하와 `setup()` 시간을 줄이는 데는 효과가 있었지만, **대용량 테스트 데이터를 한 프로세스에서 전부 보관하는 구조**는 그대로 남아 있다.
- 따라서 `20만`, `50만` 유입 시나리오를 단일 k6 프로세스로 바로 실행하면, 서버보다 먼저 부하 발생기 자원이 고갈될 가능성이 높다.

### 이번 시도에서 확인한 점

- 로그인 제거 자체는 유효했다. 병목이 인증 처리에서 k6 메모리 사용량으로 이동했다.
- 단일 서버 한계를 보기 전에, **단일 부하 발생기 한계**를 먼저 분리해서 다뤄야 한다는 점이 명확해졌다.
- 이후 대규모 테스트에서는 "총 유입 규모"와 "한 프로세스가 동시에 들고 있는 테스트 데이터 크기"를 분리해서 설계해야 한다.
![그라파냐 테스트 사진 ㅠㅠ](<스크린샷 2026-05-27 234658.png>)

### 앞으로 할 사항

1. 임영웅 전용 테스트 데이터를 분할 저장하도록 변경한다.
2. k6 스크립트가 하나의 대형 JSON 대신 분할된 파일 하나만 읽도록 바꾼다.
3. `5만 users` 단위로 나눠 단일 서버 한계를 다시 측정한다.
4. 필요 시 동일 서버를 대상으로 k6 프로세스를 2~4개로 나눠 병렬 실행해, 서버는 단일로 유지하면서 부하 발생기 메모리 부담만 분산한다.
5. 그 다음 단계에서 `USER_OFFSET / USER_LIMIT` 또는 파일 분할 방식으로 다중 부하 발생기 구조를 준비한다.
6. 최종적으로는 `총 유입 20만~50만 / 동시 활성 별도 제어` 모델로 확장해 실제 이벤트성 버스트 트래픽에 더 가깝게 검증한다.

### 요약

- 현재 중단 원인은 "서비스가 20만을 못 받는다"가 아니라, "단일 k6 프로세스가 20만 규모 테스트 데이터를 메모리에 모두 들고 가는 구조가 먼저 한계에 도달했다"에 가깝다.
- 따라서 다음 개선 우선순위는 서버 튜닝보다 **부하 테스트 실행 구조의 경량화와 분할**이다.
