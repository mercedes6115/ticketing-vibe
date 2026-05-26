# 시퀀스 다이어그램

사용자 시점의 전체 예매 흐름을 단계별로 정리한 다이어그램.

---

## 1. 회원가입 / 로그인

```mermaid
sequenceDiagram
    actor User
    participant FE as Frontend
    participant API as Spring Boot
    participant DB as MySQL
    participant Redis

    User->>FE: 회원가입 정보 입력
    FE->>API: POST /api/auth/signup
    API->>DB: 이메일 중복 확인 + 사용자 저장 (BCrypt)
    DB-->>API: User 저장 완료
    API-->>FE: 201 { accessToken, refreshToken, userId, role }

    User->>FE: 이메일 / 비밀번호 입력
    FE->>API: POST /api/auth/login
    API->>DB: 사용자 조회 + 비밀번호 검증
    API->>Redis: auth:refresh:{userId} = refreshToken (TTL 7일)
    API-->>FE: 200 { accessToken, refreshToken, userId, role }
```

---

## 2. 대기열 진입 및 입장 토큰 발급 ([SSE](https://www.notion.so/SSE-36c05755fb8780b78318f699da2c1628?source=copy_link))

```mermaid
sequenceDiagram
    actor User
    participant FE as Frontend
    participant API as Spring Boot
    participant Redis

    User->>FE: 이벤트 예매 페이지 진입
    FE->>API: POST /api/queue/enter { eventId } + JWT
    API->>Redis: ZADD queue:event:{eventId} timestamp userId
    API-->>FE: 200 { position, totalWaiting, canEnter }

    FE->>API: GET /api/queue/stream?eventId=&token=ACCESS_TOKEN (SSE 연결)
    API-->>FE: SSE 스트림 수립

    loop 2초마다 순번 전송
        API-->>FE: SSE queue-status { position, totalWaiting, canEnter }
    end

    loop 서버 5초마다 processQueue 실행
        API->>Redis: ZRANGE queue:event:{eventId} 0~99 (상위 100명 조회)
        API->>Redis: SET NX queue:token:{userId}:{eventId} = token (TTL 10분)
        Redis-->>API: 토큰 저장 성공
        API->>Redis: ZREM queue:event:{eventId} userId
        API-->>FE: SSE token-issued 이벤트
    end

    FE->>FE: 좌석 선택 페이지로 이동
```

---

## 3. 좌석 선점 ([WebSocket / STOMP](https://www.notion.so/WebSocket-STOMP-36c05755fb8780c09420f763293a2d65?source=copy_link))

```mermaid
sequenceDiagram
    actor User
    participant FE as Frontend
    participant API as Spring Boot
    participant Redis
    participant DB as MySQL

    User->>FE: 좌석 클릭
    FE->>API: POST /api/seats/{seatId}/hold (JWT)
    API->>Redis: queue:token:{userId}:{eventId} 존재 확인
    Note right of API: 토큰 없으면 즉시 거부

    API->>Redis: Redisson RLock 획득 (대기 5초 / 임대 10초)
    Note right of API: 다른 요청 대기 또는 409 반환

    API->>DB: SELECT seat FOR UPDATE (상태 재확인)

    alt 좌석 AVAILABLE
        API->>DB: UPDATE seat SET status = HOLD
        API->>Redis: SET seat:hold:{seatId} = userId (TTL 5분)
        API->>Redis: RLock 해제
        Note right of API: 트랜잭션 커밋 후 WebSocket 전송
        API-->>FE: WebSocket /topic/events/{eventId}/seats (HOLD)
        API-->>FE: 200 SeatResponse
    else 좌석 HOLD / SOLD
        API->>Redis: RLock 해제
        API-->>FE: 409 이미 선점된 좌석
    end
```

---

## 4. 예매 요청 (비동기)

```mermaid
sequenceDiagram
    actor User
    participant FE as Frontend
    participant API as Spring Boot
    participant Redis
    participant Kafka
    participant Consumer as BookingRequestConsumer
    participant DB as MySQL

    User->>FE: 결제 수단 선택 후 예매 확정
    FE->>API: POST /api/bookings { seatId, paymentMethod } (JWT)

    API->>Redis: seat:hold:{seatId} == userId 검증
    API->>API: bookingNo 생성 (BK + 날짜 + UUID)
    API->>Redis: booking:status:{bookingNo} = PROCESSING (TTL 10분)
    API->>Kafka: booking-requests 발행 ACK 대기 (key=userId)
    API->>Redis: seat:hold:{seatId} 삭제
    API-->>FE: 202 Accepted { bookingNo, status: PROCESSING }

    Note over FE, DB: 비동기 처리 (Consumer)
    Kafka->>Consumer: 메시지 수신 (at-least-once)
    Consumer->>DB: bookingNo 존재 확인 (idempotency)
    Consumer->>DB: SELECT seat FOR UPDATE
    Consumer->>DB: seat SOLD + Booking 저장 + Payment 저장
    Consumer->>Redis: booking:status:{bookingNo} = CONFIRMED
    Consumer->>Kafka: booking-events 발행

    Note over FE, API: 프론트엔드 폴링
    loop CONFIRMED 또는 FAILED 까지
        FE->>API: GET /api/bookings/status/{bookingNo}
        API->>Redis: booking:status 조회
        API-->>FE: { bookingNo, status }
    end

    FE->>API: GET /api/bookings/no/{bookingNo}
    API->>DB: 예매 상세 조회
    API-->>FE: BookingResponse (예매 상세)
```

---

## 5. 예매 취소

```mermaid
sequenceDiagram
    actor User
    participant FE as Frontend
    participant API as Spring Boot
    participant DB as MySQL
    participant Kafka

    User->>FE: 예매 취소 버튼 클릭
    FE->>API: POST /api/bookings/{bookingId}/cancel (JWT)

    API->>DB: Booking 조회 + userId 소유자 확인
    API->>DB: booking.cancel() + payment.refund()
    API->>DB: seat.release()
    API-->>FE: 200 BookingResponse (CANCELLED)
    Note right of API: 커밋 후 WebSocket + Kafka 발행
    API-->>FE: WebSocket /topic/events/{eventId}/seats (AVAILABLE)
```

---

## 6. 좌석 선점 자동 해제 (TTL 만료)

```mermaid
sequenceDiagram
    participant Redis
    participant Listener as RedisKeyExpirationListener
    participant DB as MySQL
    participant WS as WebSocket

    Note over Redis: seat:hold:{seatId} TTL 5분 만료
    Redis->>Listener: keyevent expired 알림
    Listener->>DB: UPDATE seat SET status=AVAILABLE WHERE status=HOLD (원자적)
    DB-->>Listener: 1 row updated
    Listener->>DB: SELECT seat JOIN FETCH event (eventId 조회)
    Listener->>WS: /topic/events/{eventId}/seats (AVAILABLE 브로드캐스트)
```
