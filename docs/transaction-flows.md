# Transaction Flows — Ticketing System

> **범례**
> - 🟦 `@Transactional` 경계 (DB 커밋/롤백 단위)
> - 🟨 `afterCommit()` 훅 (커밋 성공 후 실행)
> - 🟥 에러 / 예외 경로

---

## 1. 좌석 선점 — `SeatService.holdSeat()`

분산락(Redisson) + DB 비관적 락(PESSIMISTIC_WRITE)을 조합해 이중 선점을 방지한다.
락 해제 → Spring 커밋 사이 창을 DB 행 잠금으로 닫는다.

```mermaid
sequenceDiagram
    autonumber
    participant C  as Client
    participant SS as SeatService
    participant R  as Redis
    participant RL as Redisson Lock
    participant DB as Database
    participant WS as WebSocket

    C->>SS: POST /seats/{seatId}/hold (userId)

    SS->>R: hasKey("queue:token:{userId}:{eventId}")
    alt 토큰 없음
        R-->>SS: false
        SS-->>C: 403 ForbiddenException
    end

    Note over SS,DB: 🟦 @Transactional BEGIN

    SS->>RL: tryLock(wait=5s, lease=10s)
    alt 락 획득 실패
        RL-->>SS: false
        SS-->>C: 409 이미 선점 중
    end

    SS->>DB: findByIdWithEventForUpdate (PESSIMISTIC_WRITE)
    Note over DB: DB 행 잠금 — 트랜잭션 커밋까지 유지

    alt 좌석 AVAILABLE 아님
        SS-->>C: 409 이미 선점/판매된 좌석
    end

    SS->>DB: seat.hold() → save()
    SS->>R:  SET seat:hold:{seatId} = userId (TTL 300s)
    Note over SS: afterCommit 콜백 등록 (WebSocket)

    SS->>RL: unlock() [finally]
    Note over RL: 락 해제 — 커밋 前

    Note over SS,DB: 🟦 @Transactional COMMIT
    Note over DB: PESSIMISTIC_WRITE 해제

    Note over SS,WS: 🟨 afterCommit()
    SS->>WS: /topic/events/{eventId}/seats (HOLD)

    SS-->>C: 200 SeatResponse
```

---

## 2. 좌석 해제 — `SeatService.releaseSeat()`

Redis 홀드 키 삭제를 `afterCommit()`으로 지연한다.
커밋 전 삭제 시 DB 롤백이 발생하면 키는 없고 DB는 HOLD → 좌석 영구 잠김.

```mermaid
sequenceDiagram
    autonumber
    participant C  as Client
    participant SS as SeatService
    participant R  as Redis
    participant DB as Database
    participant WS as WebSocket

    C->>SS: DELETE /seats/{seatId}/hold (userId)

    SS->>R: GET seat:hold:{seatId}
    alt 홀드 키 없음
        R-->>SS: null
        SS-->>C: 409 선점된 좌석 아님
    end
    alt 다른 사용자 소유
        SS-->>C: 403 ForbiddenException
    end

    Note over SS,DB: 🟦 @Transactional BEGIN

    SS->>DB: findByIdWithEvent(seatId)
    alt 상태 HOLD 아님
        SS-->>C: 409 선점 상태 아님
    end

    SS->>DB: seat.release() → save()
    Note over SS: afterCommit 콜백 등록 (Redis DELETE + WebSocket)

    Note over SS,DB: 🟦 @Transactional COMMIT

    Note over SS,WS: 🟨 afterCommit()
    SS->>R:  DELETE seat:hold:{seatId}
    SS->>WS: /topic/events/{eventId}/seats (AVAILABLE)

    SS-->>C: 200 SeatResponse
```

---

## 3. 예매 요청 — `BookingService.createBooking()`

DB를 건드리지 않으므로 트랜잭션 없이 실행한다 (`Propagation.NOT_SUPPORTED`).
Kafka 발행 ACK를 동기 대기 후에만 홀드 키를 삭제해 좌석 영구 HOLD를 방지한다.

```mermaid
sequenceDiagram
    autonumber
    participant C  as Client
    participant BS as BookingService
    participant R  as Redis
    participant K  as Kafka

    C->>BS: POST /api/bookings (seatId, paymentMethod)
    Note over BS: Propagation.NOT_SUPPORTED (트랜잭션 없음)

    BS->>R: GET seat:hold:{seatId}
    alt 홀드 키 없음 또는 다른 사용자
        R-->>BS: null / mismatch
        BS-->>C: 403 ForbiddenException
    end

    BS->>BS: bookingNo 생성 (BK + yyyyMMdd + 10자 UUID)
    BS->>R:  SET booking:status:{bookingNo} = PROCESSING (TTL 10분)

    BS->>K: send("booking-requests", key=userId, BookingRequestEvent).get(5s)
    alt 발행 실패 / 타임아웃
        K-->>BS: ExecutionException / TimeoutException
        BS->>R: DELETE booking:status:{bookingNo}
        BS-->>C: 500 재시도 요청
    end

    BS->>R: DELETE seat:hold:{seatId}
    Note over R: 발행 성공 확인 후 삭제 — 자동 해제 방지

    BS-->>C: 202 Accepted { bookingNo, status:"PROCESSING" }
```

---

## 4. 예매 영속화 — `BookingRequestConsumer → BookingService.persistBookingRequest()`

Consumer가 DB 쓰기를 담당한다. `bookingNo` 중복 체크로 at-least-once 재처리에 안전하다.

```mermaid
sequenceDiagram
    autonumber
    participant K   as Kafka
    participant Con as BookingRequestConsumer
    participant BS  as BookingService
    participant R   as Redis
    participant DB  as Database
    participant EP  as BookingEventProducer

    K->>Con: consume(BookingRequestEvent)

    Note over BS,DB: 🟦 @Transactional BEGIN

    BS->>DB: findByBookingNo(bookingNo) — 아이디엠포턴시 체크
    alt 이미 처리됨
        DB-->>BS: Booking exists
        BS-->>Con: BookingEvent (skip)
    end

    BS->>DB: findById(userId)
    BS->>DB: findByIdWithEventForUpdate(seatId) — PESSIMISTIC_WRITE

    alt 좌석 HOLD 아님
        Note over BS,DB: 🟦 @Transactional ROLLBACK
        BS-->>Con: NonRetryableBookingException
    end

    BS->>DB: seat.sell()

    BS->>DB: Booking 생성 → confirm()
    BS->>DB: Payment 생성 → success()

    Note over BS,DB: 🟦 @Transactional COMMIT
    Con->>R:  SET booking:status:{bookingNo} = CONFIRMED (TTL 10분)
    Con->>EP: send(BookingEvent) — fire-and-forget

    Note over EP: booking-events 발행 (알림/통계 다운스트림)
    Note over Con,R: NonRetryableBookingException이면 Consumer가 FAILED 기록 후 종료 (재throw 없음)
```

---

## 5. 예매 취소 — `BookingService.cancelBooking()`

취소는 동시성 경합이 없으므로 동기 트랜잭션으로 처리한다.
Kafka 발행을 `afterCommit()`으로 지연해 롤백 시 CANCELLED 이벤트 오발행을 막는다.

```mermaid
sequenceDiagram
    autonumber
    participant C  as Client
    participant BS as BookingService
    participant DB as Database
    participant WS as WebSocket
    participant EP as BookingEventProducer

    C->>BS: POST /api/bookings/{id}/cancel (userId)

    Note over BS,DB: 🟦 @Transactional BEGIN

    BS->>DB: findByIdWithEventAndSeat(bookingId)
    alt 소유자 불일치
        BS-->>C: 403 ForbiddenException
    end
    alt 이미 취소됨
        BS-->>C: 409 IllegalStateException
    end

    BS->>DB: booking.cancel()
    BS->>DB: payment.refund()
    BS->>DB: seat.release()
    Note over BS: afterCommit 콜백 등록 (WebSocket + Kafka)

    Note over BS,DB: 🟦 @Transactional COMMIT

    Note over BS,EP: 🟨 afterCommit()
    BS->>WS: /topic/events/{eventId}/seats (AVAILABLE)
    BS->>EP: send(BookingEvent{status:CANCELLED})

    BS-->>C: 200 BookingResponse
```

---

## 6. 회원가입 — `AuthService.signup()`

리프레시 토큰 저장을 `afterCommit()`으로 지연해 DB 롤백 시 7일 토큰 잔류를 방지한다.

```mermaid
sequenceDiagram
    autonumber
    participant C  as Client
    participant AS as AuthService
    participant DB as Database
    participant R  as Redis

    C->>AS: POST /api/auth/signup

    Note over AS,DB: 🟦 @Transactional BEGIN

    AS->>DB: existsByEmail(email)
    alt 이메일 중복
        AS-->>C: 409 이미 사용 중인 이메일
    end

    AS->>DB: User 생성 (password BCrypt 암호화) → save()
    AS->>AS: createAccessToken(userId, role)
    AS->>AS: createRefreshToken(userId)
    Note over AS: afterCommit 콜백 등록 (Redis SET)

    Note over AS,DB: 🟦 @Transactional COMMIT

    Note over AS,R: 🟨 afterCommit()
    AS->>R: SET auth:refresh:{userId} = refreshToken (TTL 7일)

    AS-->>C: 201 TokenResponse
```

---

## 7. Redis TTL 만료 자동 해제 — `RedisKeyExpirationListener`

사용자가 선점 후 5분 내 예매하지 않으면 Redis TTL 만료 이벤트로 좌석을 자동 해제한다.

```mermaid
sequenceDiagram
    autonumber
    participant R   as Redis
    participant EL  as SeatHoldExpirationHandler
    participant DB  as Database
    participant WS  as WebSocket

    R->>EL: keyevent:expired — "seat:hold:{seatId}"

    Note over EL,DB: 🟦 @Transactional BEGIN

    EL->>DB: releaseHoldSeat(seatId)
    Note over DB: UPDATE seats SET status='AVAILABLE'
    Note over DB: WHERE id=? AND status='HOLD' (원자적 조건부)

    alt updated == 0 (이미 예매됨 or 수동 해제됨)
        Note over EL: 아무것도 하지 않음
    end

    EL->>DB: findByIdWithEvent(seatId) — eventId 조회용

    Note over EL,DB: 🟦 @Transactional COMMIT

    EL->>WS: /topic/events/{eventId}/seats (AVAILABLE)
```

---

## 흐름 요약

| 기능 | 트랜잭션 | Redis 조작 타이밍 | Kafka |
|---|---|---|---|
| 좌석 선점 | `@Transactional` | SET — 커밋 **전** (TTL 자가치유) | — |
| 좌석 해제 | `@Transactional` | DELETE — `afterCommit()` | — |
| 예매 요청 | 없음 (`NOT_SUPPORTED`) | DELETE — Kafka ACK 후 | 발행 후 ACK 대기 (5s) |
| 예매 영속화 | `@Transactional` (Consumer) | SET CONFIRMED — 커밋 후 | 다운스트림 발행 |
| 예매 취소 | `@Transactional` | — | `afterCommit()` 발행 |
| 회원가입 | `@Transactional` | SET — `afterCommit()` | — |
| TTL 자동 해제 | `@Transactional` | — (만료 트리거) | — |
