# Ticketing System

실시간 티켓팅 시스템 - Redis 분산락, WebSocket, SSE, Kafka 기반

## 프로젝트 구조

```
C:\side_project\
├── backend/                 # Spring Boot 백엔드
│   └── src/main/java/com/ticketing/
│       ├── config/          # Redis, WebSocket, Security, Kafka 설정
│       ├── controller/      # REST API + SSE 엔드포인트
│       ├── dto/             # Request/Response DTO
│       │   └── kafka/       # Kafka 메시지 DTO
│       ├── entity/          # JPA 엔티티
│       ├── repository/      # Spring Data JPA
│       └── service/         # 비즈니스 로직 + Kafka Producer/Consumer
├── frontend/                # React + Vite 프론트엔드
│   └── src/
│       ├── api/             # Axios API 클라이언트
│       ├── components/      # 공통 컴포넌트
│       ├── pages/           # 페이지 컴포넌트
│       ├── stores/          # Zustand 상태관리
│       └── types/           # TypeScript 타입
├── scripts/                 # 운영 스크립트
├── docker-compose.yml       # 전체 스택 구성
└── CLAUDE.md
```

## 기술 스택

### Backend
- Java 17, Spring Boot 3.2
- Spring Data JPA + MySQL 8
- Redisson (분산락)
- WebSocket (STOMP) - 실시간 좌석 상태
- SSE - 대기열 상태 스트리밍
- **Kafka (KRaft mode) - 예매 요청 비동기 처리**

### Frontend
- React 18, TypeScript, Vite
- Zustand (상태관리)
- TailwindCSS
- STOMP.js + SockJS

### Infrastructure
- Docker Compose
- MySQL 8, Redis 7
- **Kafka 3.7 (bitnami, KRaft — Zookeeper 없음)**

## 실행 방법

```bash
# 전체 스택 실행
./scripts/start.sh

# 또는 직접
docker-compose up -d --build

# 종료
./scripts/stop.sh

# Redis 캐시 초기화
./scripts/flush.sh
```

## 주요 URL

- Frontend: http://localhost:5173
- Backend API: http://localhost:8080
- WebSocket: ws://localhost:8080/ws

## 핵심 아키텍처

### 1. 예매 요청 (비동기 — 고처리량 핵심)

DB 쓰기를 크리티컬 패스에서 제거해 처리량을 극대화한다.

```
POST /api/bookings  (동기 구간, Redis만 접근)
  ├── Redis 좌석 홀드 검증 (seat:hold:{seatId} == userId)
  ├── Redis 홀드 키 삭제 (자동 해제 방지)
  ├── bookingNo 생성
  ├── Redis booking:status:{bookingNo} = PROCESSING (TTL 10분)
  ├── Kafka booking-requests 발행 (key=userId, 순서 보장)
  └── 202 Accepted + { bookingNo, status: "PROCESSING" }

[BookingRequestConsumer — 비동기, 아이디엠포턴트]
  ├── bookingNo DB 존재 여부 확인 (중복 처리 방지)
  ├── Seat 로드 (DB 상태 = HOLD 확인)
  ├── @Transactional:
  │     seat.sell() → event.decreaseAvailableSeats()
  │     Booking 생성 (CONFIRMED) + Payment 생성 (SUCCESS)
  ├── Redis booking:status:{bookingNo} = CONFIRMED
  └── Kafka booking-events 발행 (알림, 통계 등 다운스트림)

GET /api/bookings/status/{bookingNo}  (프론트엔드 폴링)
  └── Redis booking:status:{bookingNo} 조회
      → { bookingNo, status: PROCESSING | CONFIRMED | FAILED }
```

### 2. 예매 취소 (동기 — 경합 없음)

취소는 동시성 경합이 없으므로 기존 @Transactional 방식 유지.

```
POST /api/bookings/{id}/cancel
  ├── @Transactional: booking.cancel() + payment.refund()
  ├── seat.release() + event.increaseAvailableSeats()
  └── Kafka booking-events 발행 (CANCELLED)
```

### 3. 좌석 선점 (분산락)

```
SeatService.holdSeat()
├── 입장 토큰 검증 (Redis)
├── Redisson RLock 획득 (5초 대기, 10초 임대)
├── 좌석 상태 변경 (HELD)
├── Redis seat:hold:{seatId} = userId (TTL 5분)
├── WebSocket 브로드캐스트
└── 락 해제
```

### 4. 대기열 시스템

```
QueueService
├── enterQueue(): ZADD로 대기열 진입 (timestamp 기반)
├── getQueueStatus(): ZRANK로 순위 조회
├── processQueue(): 상위 N명에게 토큰 발급
└── SSE로 실시간 순위 스트리밍 (2초 간격)
```

### 5. 자동 해제 메커니즘

- Redis Keyspace Notification 활용
- `seat:hold:{seatId}` 키 만료 시 좌석 자동 해제
- `RedisKeyExpirationListener`에서 처리
- 예매 요청 수락 시 홀드 키를 즉시 삭제해 자동 해제 방지

## Kafka 토픽 설계

| 토픽 | 파티션 | 파티션 키 | 역할 |
|------|--------|-----------|------|
| `booking-requests` | 10 | userId | 예매 요청 → Consumer가 DB 쓰기 |
| `booking-events` | 5 | bookingId | 예매 결과 → 알림/통계 다운스트림 |

- **userId 키**: 같은 사용자의 요청이 항상 같은 파티션 → 순서 보장
- **bookingId 키**: 같은 예매의 이벤트가 같은 파티션 → 상태 순서 보장
- Consumer Group: `booking-requests-group` (DB 쓰기), `ticketing-group` (알림)

## API 엔드포인트

### Events
- `GET /api/events` - 이벤트 목록
- `GET /api/events/{id}` - 이벤트 상세

### Queue
- `POST /api/queue/enter` - 대기열 진입
- `GET /api/queue/status` - 대기열 상태
- `GET /api/queue/stream` - SSE 스트림

### Seats
- `GET /api/seats/event/{eventId}` - 좌석 목록
- `POST /api/seats/{id}/hold` - 좌석 선점
- `POST /api/seats/{id}/release` - 좌석 해제

### Bookings
- `POST /api/bookings` - 예매 요청 → **202 Accepted** + `{ bookingNo, status }`
- `GET /api/bookings/status/{bookingNo}` - **예매 상태 폴링** (프론트엔드용)
- `GET /api/bookings/no/{bookingNo}` - 예매 상세 조회 (완료 후)
- `GET /api/bookings/users/{userId}` - 예매 내역
- `POST /api/bookings/{id}/cancel` - 예매 취소 (동기)

## Redis 키 구조

| 키 패턴 | 용도 | TTL |
|---------|------|-----|
| `queue:{eventId}` | 대기열 Sorted Set | - |
| `queue:token:{userId}:{eventId}` | 입장 토큰 | 10분 |
| `seat:hold:{seatId}` | 좌석 홀드 | 5분 |
| `seat:lock:{seatId}` | 분산락 | 10초 |
| `booking:status:{bookingNo}` | 예매 처리 상태 | 10분 |

## WebSocket 토픽

- `/topic/events/{eventId}/seats` - 좌석 상태 변경 브로드캐스트

## 알려진 제약 및 미구현 사항

- **Kafka replicas=1**: 브로커 1대 환경 제약. 프로덕션은 `replicas ≥ 2`, `min.insync.replicas=2` 설정 필요
- **BookingEventConsumer**: `booking-events` 토픽 소비자는 로그 출력만 하는 stub. 실제 알림(이메일/SMS) 및 통계 처리는 미구현

## 설계 결정

1. **Redisson vs Lettuce**: Redisson 선택 - 내장 RLock 지원
2. **Saga vs Transaction**: 단순 @Transactional 선택 - Mock 결제로 보상 트랜잭션 불필요
3. **실시간 통신**: WebSocket(좌석) + SSE(대기열) 분리 - 용도별 최적화
4. **Kafka 비동기 예매**: DB 쓰기를 크리티컬 패스에서 제거 → 고처리량 달성
   - 크리티컬 패스: Redis만 접근 (수μs) → 만 TPS 가능
   - Consumer: DB 쓰기 담당, 아이디엠포턴트 (bookingNo 중복 체크)
5. **취소는 동기 유지**: 취소는 경합이 없고 복잡도 증가 대비 이점이 적음
6. **Consumer 아이디엠포턴시**: Kafka 재처리 시 bookingNo DB 존재 확인 → 중복 예매 방지
7. **홀드 키 삭제 순서**: Kafka 발행 성공 후 seat:hold 키 삭제. 발행 전 삭제 시 크래시로 좌석 영구 HOLD 잔류 방지
8. **available_seats 원자적 감소**: `UPDATE events SET available_seats = available_seats - 1` — 동시 Consumer 처리 시 lost-update 방지
9. **Kafka DLQ**: `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` — 1초 간격 3회 재시도 후 `.DLQ` 토픽으로 격리, poison-message 루프 방지
10. **processQueue 원자성**: `SET NX`(`setIfAbsent`)로 다중 인스턴스 중복 토큰 발급 방지
