# 서비스 플로우

핵심 기능별 내부 처리 흐름 정리.

---

## 1. 대기열 시스템

Redis Sorted Set 기반의 가상 대기열. 동시 접속자를 제한해 좌석 선점 페이지의 과부하를 방지한다.

```mermaid
flowchart TD
    A[POST /api/queue/enter] --> B[Redis ZADD\nqueue:event:{eventId}\nscore = System.currentTimeMillis]
    B --> C[Redis Set에 eventId 등록\nqueue:active:events]
    C --> D[QueueStatusResponse 반환\nposition / totalWaiting / canEnter]
    D --> E[GET /api/queue/stream\nSSE 연결]

    subgraph SCHED["서버 스케줄러 (5초마다)"]
        F[processAllActiveQueues] --> G[활성 이벤트 목록 조회\nqueue:active:events]
        G --> H[이벤트별 ZRANGE 0~99\n상위 100명 조회]
        H --> I{토큰 이미\n발급됨?}
        I -->|Yes| J[건너뜀]
        I -->|No| K["Redis SET NX\nqueue:token:{userId}:{eventId}\n= UUID token, TTL 10분"]
        K --> L[ZREM 대기열에서 제거]
        L --> M[QueueEmitterManager\nSSE token-issued 전송]
    end

    E -.->|2초마다 순번 push| E
    M --> N[프론트엔드 SSE 수신\n좌석 선택 페이지로 이동]
```

**핵심 설계 포인트**
- `SET NX` (setIfAbsent): 다중 서버 인스턴스 환경에서 토큰 중복 발급 방지
- `SSE` + `QueueEmitterManager`: 서버 Push로 클라이언트 폴링 부하 제거
- 대기열 키에 TTL 없음 → 이벤트 종료 시 관리자가 `clearQueue()` 호출

---

## 2. 좌석 선점 (분산락)

Redisson 분산락으로 동시 요청을 직렬화. 선점 정보는 Redis에 TTL 5분으로 저장해 자동 해제를 지원한다.

```mermaid
flowchart TD
    A[POST /api/seats/{seatId}/hold\nJWT 인증] --> B{입장 토큰\n존재 여부\nqueue:token 키 확인}
    B -->|없음| C[400 토큰 없음\n대기열 필요]
    B -->|있음| D[Redisson RLock 획득 시도\nseat:lock:{seatId}\n대기 3초 / 임대 10초]
    D -->|타임아웃| E[409 다른 사용자 선점 중]
    D -->|획득 성공| F[DB findByIdWithEvent\nSELECT seat JOIN FETCH event]
    F --> G{seat.status}
    G -->|HOLD or SOLD| H[409 이미 선점 또는 판매됨]
    G -->|AVAILABLE| I[seat.hold\nDB UPDATE status = HOLD]
    I --> J[Redis SET\nseat:hold:{seatId} = userId\nTTL 5분]
    J --> K[RLock 해제]
    K --> L[트랜잭션 커밋]
    L --> M[afterCommit callback\nWebSocket 브로드캐스트\n/topic/events/{eventId}/seats]
    M --> N[200 SeatResponse 반환]

    subgraph TTL["TTL 만료 자동 해제"]
        O[seat:hold 키 TTL 만료] --> P[Redis keyevent 알림]
        P --> Q[releaseHoldSeat\nUPDATE WHERE status=HOLD\n원자적 조건부 실행]
        Q --> R[findByIdWithEvent\neventId 조회]
        R --> S[WebSocket AVAILABLE 브로드캐스트]
    end
```

**핵심 설계 포인트**
- 락 획득 후 DB 재조회: 락 대기 중 다른 요청이 상태를 변경했을 수 있으므로 재확인 필수
- `afterCommit` WebSocket 전송: 트랜잭션 롤백 시 클라이언트에 유령 HOLD 전달 방지
- `releaseHoldSeat` 원자적 UPDATE: TTL 만료와 예매 처리 동시 발생 시 double-release 방지

---

## 3. 예매 요청 (비동기 — Kafka)

DB 쓰기를 크리티컬 패스에서 제거해 높은 처리량을 달성한다. 요청 수락은 Redis만으로 처리하고 실제 저장은 Consumer가 담당한다.

```mermaid
flowchart TD
    subgraph CRITICAL["크리티컬 패스 (동기 — Redis만 접근)"]
        A[POST /api/bookings\nseatId + paymentMethod + JWT] --> B{seat:hold:{seatId}\n== userId?}
        B -->|불일치| C[400 본인 선점 좌석 아님]
        B -->|일치| D[bookingNo 생성\nBK + yyyyMMdd + UUID 10자]
        D --> E[Redis SET\nbooking:status:{bookingNo} = PROCESSING\nTTL 10분]
        E --> F[Kafka 발행\nbooking-requests\nkey = userId]
        F -->|실패| G[상태 키 삭제\n500 반환\nhold 키 보존]
        F -->|성공| H[Redis DEL\nseat:hold:{seatId}]
        H --> I[202 Accepted\nbookingNo 반환]
    end

    subgraph ASYNC["비동기 처리 (BookingRequestConsumer)"]
        J[Kafka 메시지 수신] --> K{bookingNo\nDB 존재?}
        K -->|있음| L[idempotent skip\n이미 처리된 요청]
        K -->|없음| M[findByIdWithEventForUpdate\nSELECT FOR UPDATE]
        M --> N{seat.status\n== HOLD?}
        N -->|아님| O[예외 throw\n→ 재시도 → DLQ]
        N -->|HOLD| P[seat.sell\navailableSeats 원자적 감소\nUPDATE events SET available = available - 1]
        P --> Q[Booking CONFIRMED 저장\nPayment SUCCESS 저장]
        Q --> R[Redis SET\nbooking:status = CONFIRMED]
        R --> S[booking-events 발행]
    end

    subgraph POLL["프론트엔드 폴링"]
        T[GET /api/bookings/status/{bookingNo}] --> U{Redis 키\n존재?}
        U -->|있음| V[status 반환\nPROCESSING or CONFIRMED or FAILED]
        U -->|만료| W[DB fallback\nbookingNo 존재 여부 확인]
        W --> X[CONFIRMED or UNKNOWN]
    end

    I -.->|폴링| T
    ASYNC -.->|상태 업데이트| POLL
```

**핵심 설계 포인트**
- `userId` 파티션 키: 같은 사용자의 요청이 같은 파티션으로 → 순서 보장
- hold 키 삭제를 Kafka 발행 성공 후로 지연: 발행 전 크래시 시 영구 HOLD 잔류 방지
- `DefaultErrorHandler`: 1초 간격 3회 재시도 후 `.DLQ` 격리
- `available_seats` 원자적 감소: `UPDATE SET count = count - 1` → 동시 Consumer 처리 시 lost-update 방지

---

## 4. 예매 취소 (동기)

취소는 경합이 없으므로 비동기화 없이 `@Transactional` 하나로 처리한다.

```mermaid
flowchart TD
    A[POST /api/bookings/{bookingId}/cancel\nJWT 인증] --> B[findByIdWithEventAndSeat\nBooking 조회]
    B --> C{booking.userId\n== JWT userId?}
    C -->|불일치| D[400 본인 예매 아님]
    C -->|일치| E{이미\n취소됨?}
    E -->|Yes| F[400 이미 취소된 예매]
    E -->|No| G[booking.cancel\nstatus = CANCELLED]
    G --> H{payment\n존재 & SUCCESS?}
    H -->|Yes| I[payment.refund\nstatus = REFUNDED]
    H -->|No| J[스킵]
    I --> K[seat.release\nstatus = AVAILABLE]
    J --> K
    K --> L[availableSeats 증가\nUPDATE events SET available = available + 1]
    L --> M[트랜잭션 커밋]
    M --> N[booking-events 발행\nstatus: CANCELLED]
    N --> O[WebSocket 브로드캐스트\n좌석 AVAILABLE]
    O --> P[200 BookingResponse 반환]
```

---

## 5. JWT 인증 흐름

```mermaid
flowchart TD
    A[HTTP 요청\nAuthorization: Bearer token] --> B[JwtAuthenticationFilter]
    B --> C{토큰 추출\n및 유효성 검증}
    C -->|유효하지 않음| D[401 Unauthorized]
    C -->|유효함| E[getAuthentication\nuserId를 principal로 설정]
    E --> F[SecurityContextHolder에 저장]
    F --> G[컨트롤러\n@AuthenticationPrincipal Long userId]
    G --> H[서비스 호출]

    subgraph REISSUE["토큰 재발급"]
        I[POST /api/auth/reissue\n{ refreshToken }] --> J{Redis\nrefresh:{userId} 일치?}
        J -->|불일치| K[401 유효하지 않은 토큰]
        J -->|일치| L[기존 토큰 삭제]
        L --> M[새 accessToken + refreshToken 발급]
        M --> N[Redis 갱신\nrefresh:{userId} = 새 refreshToken]
    end
```
