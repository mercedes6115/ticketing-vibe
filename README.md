# 실시간 티켓팅 시스템

동시 접속이 몰리는 티켓팅 상황을 가정하고 대기열 진입부터 좌석 선점, 비동기 예매 처리, 실시간 좌석 반영까지 구현한 프로젝트다.

이 프로젝트의 목표는 "예매 기능을 만드는 것"보다 "경합 상태를 어떻게 제어할 것인가"에 가깝다. 단순 CRUD가 아니라 동시성 제어, 상태 정합성, 비동기 처리, 관측 가능성을 중심으로 설계했다.

## 왜 이 프로젝트를 만들었는가

티켓팅 서비스는 겉으로 보면 단순한 예매 서비스처럼 보이지만, 실제로는 일반적인 게시판이나 쇼핑 CRUD보다 훨씬 까다로운 문제를 갖고 있다.

- 같은 시간에 많은 사용자가 한 번에 진입한다.
- 같은 좌석을 여러 사용자가 동시에 클릭한다.
- 아주 짧은 시간 안에 성공과 실패를 구분해서 알려줘야 한다.
- 결제 직전 이탈이나 처리 지연이 생겨도 좌석 상태가 망가지면 안 된다.

즉 이 문제에서 중요한 것은 화면을 몇 개 만들었는가가 아니라, 경합 상태를 어떤 방식으로 분해하고 제어했는가다.

개인 프로젝트로 이 주제를 선택한 이유도 여기에 있다. 기능 구현 자체보다 아래 질문에 답할 수 있는 프로젝트를 만들고 싶었다.

- 왜 대기열이 필요한가
- 왜 좌석 선점은 즉시 처리해야 하는가
- 왜 예매 확정은 비동기로 분리하는가
- 왜 운영 중에는 메트릭과 병목 관찰이 필수인가

이 프로젝트는 그 질문들에 대해 설계, 구현, 테스트, 문서로 답하는 것을 목표로 한다.

## 문제 정의

티켓팅 시스템에서는 다음 문제가 동시에 발생한다.

- 많은 사용자가 같은 시점에 접속한다.
- 여러 사용자가 같은 좌석을 동시에 선택한다.
- 예매 요청이 몰리면 DB 쓰기 경합과 응답 지연이 커진다.
- 좌석 선점 후 결제가 완료되지 않으면 좌석은 안전하게 복구돼야 한다.
- 운영 중 병목이 API, DB, Kafka, Redis 중 어디에서 발생하는지 확인할 수 있어야 한다.

이 프로젝트는 이 문제를 다음 방식으로 나눠 해결한다.

- 대기열: Redis Sorted Set + SSE
- 좌석 선점: Redisson 분산락 + Redis TTL + WebSocket
- 예매 처리: Kafka 기반 비동기 처리
- 모니터링: Prometheus + Grafana

## 핵심 설계

### 1. 대기열과 입장 제어

사용자는 먼저 대기열에 진입하고, 서버는 Redis 기반 순번 관리 후 입장 토큰을 발급한다. 클라이언트는 SSE로 순번 변화와 토큰 발급 여부를 받는다.

- 대기열 키: `queue:event:{eventId}`
- 입장 토큰 TTL: 10분
- 상위 대기자만 좌석 선택 권한 부여

관련 문서: [서비스 플로우](docs/service-flow.md), [API 문서](docs/api.md)

### 2. 좌석 중복 선점 방지

같은 좌석에 대한 동시 요청은 Redisson 분산락으로 직렬화하고, 실제 선점 상태는 Redis TTL과 DB 상태를 함께 사용해 관리한다.

- 락 키: `seat:lock:{seatId}`
- 홀드 키: `seat:hold:{seatId}`
- 홀드 TTL: 5분
- 홀드 만료 시 Redis key expiration 이벤트로 좌석 자동 복구

이 방식으로 "동시에 같은 좌석 클릭"과 "선점 후 이탈"을 별도 문제로 분리해 처리한다.

### 3. 예매 요청의 비동기 처리

예매 요청은 API 서버에서 바로 DB에 확정하지 않고 Kafka로 발행한다. API는 빠르게 `202 Accepted`를 반환하고, 실제 확정은 Consumer가 처리한다.

- 요청 토픽: `booking-requests`
- 결과 토픽: `booking-events`
- 상태 저장: `booking:status:{bookingNo}`
- 클라이언트는 `/api/bookings/status/{bookingNo}` polling

이 구조는 핫 구간에서 API 응답 경로를 짧게 유지하고, DB 쓰기 부하를 직접적인 사용자 응답과 분리하는 데 목적이 있다.

### 4. 실시간 상태 반영

좌석 상태는 WebSocket(STOMP)으로 브로드캐스트한다.

- HOLD
- AVAILABLE
- SOLD

한 사용자의 좌석 선점, 만료, 예매 완료가 다른 사용자 화면에도 즉시 반영된다.

## 기술 스택

### Backend

- Java 17
- Spring Boot 3.2
- Spring Data JPA
- JWT 인증
- Redis / Redisson
- Apache Kafka

### Frontend

- React 18
- TypeScript
- Vite
- Zustand
- TailwindCSS
- SockJS + STOMP

### Infra / Observability

- Docker Compose
- MySQL 8
- Redis 7 + Sentinel
- Kafka 3.7 (KRaft)
- Prometheus
- Grafana

상세 구성은 [시스템 아키텍처](docs/architecture.md) 참고.

## 주요 사용자 흐름

1. 사용자가 이벤트에 진입한다.
2. 대기열 순번을 부여받고 SSE로 상태를 받는다.
3. 입장 토큰을 받으면 좌석 선택 화면으로 이동한다.
4. 좌석 홀드 요청 시 분산락으로 중복 선점을 제어한다.
5. 예매 요청은 Kafka로 비동기 발행된다.
6. Consumer가 예매와 결제를 처리하고 상태를 갱신한다.
7. 클라이언트는 polling과 WebSocket으로 결과를 확인한다.

## 검증 포인트

이 프로젝트에서 확인하려는 것은 기능 구현 자체보다 다음 항목이다.

- 많은 요청이 들어와도 대기열이 정상 동작하는가
- 같은 좌석에 대한 동시 요청이 중복 예매로 이어지지 않는가
- API 응답 경로와 실제 확정 처리를 분리했을 때 처리량과 안정성이 유지되는가
- TTL 만료, 좌석 소진, 메시지 지연 같은 예외 흐름을 관리할 수 있는가
- 운영 중 병목과 오류를 메트릭으로 관찰할 수 있는가

부하 테스트 자산은 역할별로 나뉜다.

- 대표 사용자 시나리오 테스트: [scripts/load-test-full-flow.js](scripts/load-test-full-flow.js)
- 예매 파이프라인 부분 테스트: [scripts/load-test-booking.js](scripts/load-test-booking.js)
- Grafana 대시보드: [monitoring/grafana/dashboard-json/ticketing.json](monitoring/grafana/dashboard-json/ticketing.json)
- 성능 보고서: [docs/performance-report.md](docs/performance-report.md)

`load-test-full-flow.js`는 대기열 진입, 토큰 발급, 좌석 홀드, 예매 완료까지 이어지는 메인 시나리오다. 반면 `load-test-booking.js`는 좌석 홀드가 이미 준비된 상태를 전제로 Kafka 기반 예매 확정 경로만 분리해서 보는 보조 시나리오다.

## 성능 실험 요약

대표 시나리오인 `load-test-full-flow.js` 기준으로 기준선 측정과 병목 개선 실험을 반복했다.

### 기준선

- `confirm_rate`: `82.78%`
- `booking_e2e_ms p95`: `15.26s`
- `queue_wait_ms p95`: `2.07s`
- `http_req_failed`: `0.63%`

### 2차 비교 실험

- 좌석 수를 `200 -> 500`으로 늘려 좌석 부족 효과를 분리
- `confirm_rate`: `96.01%`
- `booking_e2e_ms p95`: `11.37s`

### 3차 로그 분해

- `acceptedLatencyMs`, `queueDelayMs`, `persistMs`, `statusUpdateMs`를 추가로 측정
- 비재시도 예외 재소비 문제는 줄였지만, `consumer 대기 + persist 지연`이 병목임을 확인

### 실제로 효과가 있었던 변경

- `persistBookingRequest()`에서 `existsBySeatIdAndStatusIn` 중복 확인 쿼리 제거
- 결과:
  - `confirm_rate`: `99.64%`
  - `booking_e2e_ms p95`: `16.57s`
  - `queue_wait_ms p95`: `3.14s`

### 시도했지만 채택하지 않은 변경

- `findByIdWithEventForUpdate -> findByIdWithLock`
  - `LazyInitializationException` 발생으로 롤백
- `availableSeats` 즉시 갱신 제거
  - `confirm_rate 90.12%`, `booking_e2e_ms p95 19.41s`로 개선 효과 부족

즉 이 프로젝트는 "구현했다"에서 끝나지 않고, 병목 후보를 하나씩 분리해 실험하고, 효과가 없는 최적화는 롤백하며, 결과를 수치로 남기는 방식으로 진행했다.

상세 결과와 해석은 [성능 테스트 보고서](docs/performance-report.md) 참고.

## 실행 방법

### 1. 전체 서비스 실행

```bash
docker compose up -d
```

### 2. 접속 주소

- Frontend: `http://localhost:5173`
- Backend API: `http://localhost:8080`
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3000`

기본 Grafana 계정은 `admin / admin`이다.

추가 접속 정보: [access.md](docs/access.md)

## 문서

- [시스템 아키텍처](docs/architecture.md)
- [서비스 플로우](docs/service-flow.md)
- [시퀀스 다이어그램](docs/sequence-diagram.md)
- [클래스 다이어그램](docs/class-diagram.md)
- [API 문서](docs/api.md)
- [성능 테스트 보고서](docs/performance-report.md)
- [설계 트레이드오프](docs/tradeoffs.md)
- [AI 활용 및 검증 기록](docs/ai-usage.md)
- [ERD](ERD.md)

## 이 프로젝트에서 강조하고 싶은 점

이 저장소의 목적은 화면 하나나 CRUD 개수를 보여주는 데 있지 않다. "실시간 예매"라는 도메인에서 실제로 문제가 되는 경합 상태를 설계하고, 그것을 대기열, 좌석 선점, 비동기 예매, 상태 복구, 모니터링으로 분해했다는 점에 있다.

포트폴리오 관점에서도 다음 내용을 직접 설명할 수 있도록 만드는 것이 목표다.

- 왜 WebSocket과 SSE를 분리했는가
- 왜 좌석 선점은 동기적으로, 예매 확정은 비동기적으로 처리했는가
- 왜 Redis TTL과 DB 상태를 함께 써야 하는가
- 동시성 문제를 어떤 단위로 나눠 제어했는가
