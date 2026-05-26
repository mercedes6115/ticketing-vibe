# 서비스 플로우

핵심 기능별 내부 처리 흐름 정리.

---

## 1. 대기열 시스템

Redis Sorted Set 기반의 가상 대기열. [SSE](https://www.notion.so/SSE-36c05755fb8780b78318f699da2c1628?source=copy_link)로 순번 변화를 push해 좌석 선점 페이지의 과부하를 방지한다.

```mermaid
flowchart TD
    A["POST /api/queue/enter"] --> B["Redis ZADD<br/>queue:event:&lt;eventId&gt;<br/>score = currentTimeMillis"]
    B --> C["Redis Set 등록<br/>queue:active:events"]
    C --> D["QueueStatusResponse 반환<br/>position / totalWaiting / canEnter"]
    D --> E["GET /api/queue/stream<br/>SSE 연결"]

    subgraph SCHED["서버 스케줄러 (5초마다)"]
        F["processAllActiveQueues"] --> G["활성 이벤트 목록 조회<br/>queue:active:events"]
        G --> H["이벤트별 ZRANGE 0~99<br/>상위 100명 조회"]
        H --> I{"토큰 이미 발급됨?"}
        I -->|Yes| J["건너뜀"]
        I -->|No| K["Redis SET NX<br/>queue:token:&lt;userId&gt;:&lt;eventId&gt;<br/>TTL 10분"]
        K --> L["ZREM 대기열에서 제거"]
        L --> M["QueueEmitterManager<br/>token-issued 전송"]
    end

    E -.->|2초마다 순번 push| E
    M --> N["프론트엔드 SSE 수신<br/>좌석 선택 페이지로 이동"]
```

**핵심 설계 포인트**
- `SET NX` (setIfAbsent): 다중 서버 인스턴스 환경에서 토큰 중복 발급 방지
- [`SSE`](https://www.notion.so/SSE-36c05755fb8780b78318f699da2c1628?source=copy_link) + `QueueEmitterManager`: 서버 Push로 클라이언트 폴링 부하 제거
- 대기열 키에 TTL 없음 → 이벤트 종료 시 관리자가 `clearQueue()` 호출

---

## 2. 좌석 선점 (분산락)

[Redisson](https://www.notion.so/Redisson-36c05755fb8780b89412fae09e10d6c9?source=copy_link) 분산락과 [Pessimistic Lock](https://www.notion.so/Pesimisstic-lock-36c05755fb878098b242d34cfcb0373a?source=copy_link)을 함께 사용해 동시 요청을 직렬화한다. 선점 정보는 Redis의 [TTL / Keyspace Notification](https://www.notion.so/Redis-TTL-Keyspace-Notification-36c05755fb87800cb4cfef7e3ba08be3?source=copy_link)을 이용해 5분 후 자동 해제를 지원한다.

```mermaid
flowchart TD
    A["POST /api/seats/:seatId/hold<br/>JWT 인증"] --> B{"입장 토큰 존재?"}
    B -->|없음| C["403 유효한 입장 토큰 없음"]
    B -->|있음| D["Redisson RLock 획득 시도<br/>seat:lock:&lt;seatId&gt;<br/>대기 5초 / 임대 10초"]
    D -->|타임아웃| E["409 다른 사용자 선점 중"]
    D -->|획득 성공| F["DB findByIdWithEventForUpdate<br/>seat + event FOR UPDATE"]
    F --> G{"seat.status"}
    G -->|HOLD or SOLD| H["409 이미 선점 또는 판매됨"]
    G -->|AVAILABLE| I["seat.hold<br/>DB UPDATE status = HOLD"]
    I --> J["Redis SET<br/>seat:hold:&lt;seatId&gt; = userId<br/>TTL 5분"]
    J --> K["RLock 해제"]
    K --> L["트랜잭션 커밋"]
    L --> M["afterCommit callback<br/>WebSocket / STOMP 브로드캐스트<br/>topic/events/&lt;eventId&gt;/seats"]
    M --> N["200 SeatResponse 반환"]

    subgraph TTL["TTL 만료 자동 해제"]
        O["seat:hold 키 TTL 만료"] --> P["Redis keyevent 알림"]
        P --> Q["releaseHoldSeat<br/>UPDATE WHERE status = HOLD"]
        Q --> R["findByIdWithEvent<br/>eventId 조회"]
        R --> S["WebSocket / STOMP AVAILABLE 브로드캐스트"]
    end
```

**핵심 설계 포인트**
- 락 획득 후 DB 재조회: 락 대기 중 다른 요청이 상태를 변경했을 수 있으므로 재확인 필수
- [`afterCommit`](https://www.notion.so/afterCommit-36c05755fb87807d8ed2d7b70cf9a545?source=copy_link) [WebSocket / STOMP](https://www.notion.so/WebSocket-STOMP-36c05755fb8780c09420f763293a2d65?source=copy_link) 전송: 트랜잭션 롤백 시 클라이언트에 유령 HOLD 전달 방지
- `releaseHoldSeat` 원자적 UPDATE: TTL 만료와 예매 처리 동시 발생 시 double-release 방지

---

## 3. 예매 요청 (비동기 — [Kafka](https://www.notion.so/Kafka-36c05755fb878076a9a1dd6328913883?source=copy_link))

DB 쓰기를 크리티컬 패스에서 제거해 높은 처리량을 달성한다. 요청 수락은 Redis만으로 처리하고 실제 저장은 Consumer가 담당한다.

```mermaid
flowchart TD
    subgraph CRITICAL["크리티컬 패스 (동기 — Redis만 접근)"]
        A["POST /api/bookings<br/>seatId + paymentMethod + JWT"] --> B{"seat:hold:&lt;seatId&gt; == userId ?"}
        B -->|불일치| C["403 본인 선점 좌석 아님"]
        B -->|일치| D["bookingNo 생성<br/>BK + yyyyMMdd + UUID 10자"]
        D --> E["Redis SET<br/>booking:status:&lt;bookingNo&gt; = PROCESSING<br/>TTL 10분"]
        E --> F["Kafka 발행<br/>booking-requests<br/>key = userId"]
        F -->|실패| G["상태 키 삭제<br/>500 반환 / hold 키 보존"]
        F -->|성공| H["Redis DEL seat:hold:&lt;seatId&gt;"]
        H --> I["202 Accepted<br/>bookingNo 반환"]
    end

    subgraph ASYNC["비동기 처리 (BookingRequestConsumer)"]
        J["Kafka 메시지 수신"] --> K{"bookingNo DB 존재?"}
        K -->|있음| L["Idempotency skip<br/>이미 처리된 요청"]
        K -->|없음| M["findByIdWithEventForUpdate<br/>seat + event FOR UPDATE"]
        M --> N{"seat.status == HOLD ?"}
        N -->|아님| O["NonRetryableBookingException<br/>Redis status = FAILED"]
        N -->|HOLD| P["seat.sell"]
        P --> Q["Booking CONFIRMED 저장<br/>Payment SUCCESS 저장"]
        Q --> R["Redis SET<br/>booking:status = CONFIRMED"]
        R --> S["booking-events 발행"]
    end

    subgraph POLL["프론트엔드 폴링"]
        T["GET /api/bookings/status/:bookingNo"] --> U{"Redis 키 존재?"}
        U -->|있음| V["status 반환<br/>PROCESSING / CONFIRMED / FAILED"]
        U -->|만료| W["DB fallback<br/>bookingNo 존재 여부 확인"]
        W --> X["CONFIRMED or UNKNOWN"]
    end

    I -.->|폴링| T
    ASYNC -.->|상태 업데이트| POLL
```

**핵심 설계 포인트**
- `userId` 파티션 키: 같은 사용자의 요청이 같은 파티션으로 → 순서 보장
- hold 키 삭제를 Kafka 발행 성공 후로 지연: 발행 전 크래시 시 영구 HOLD 잔류 방지
- [`DefaultErrorHandler / DLQ`](https://www.notion.so/DLQ-DefaultErrorHandler-36c05755fb8780749b62f0e778699920?source=copy_link): 시스템 예외는 1초 간격 3회 재시도 후 `.DLQ` 격리
- 비재시도 예외(`NonRetryableBookingException`): `FAILED`로 마감하고 같은 메시지를 반복 재소비하지 않음

---

## 4. 예매 취소 (동기)

취소는 경합이 없으므로 비동기화 없이 `@Transactional` 하나로 처리한다.

```mermaid
flowchart TD
    A["POST /api/bookings/:bookingId/cancel<br/>JWT 인증"] --> B["findByIdWithEventAndSeat<br/>Booking 조회"]
    B --> C{"booking.userId == JWT userId ?"}
    C -->|불일치| D["403 본인 예매 아님"]
    C -->|일치| E{"이미 취소됨?"}
    E -->|Yes| F["409 이미 취소된 예매"]
    E -->|No| G["booking.cancel<br/>status = CANCELLED"]
    G --> H{"payment 존재 & SUCCESS ?"}
    H -->|Yes| I["payment.refund<br/>status = REFUNDED"]
    H -->|No| J["스킵"]
    I --> K["seat.release<br/>status = AVAILABLE"]
    J --> K
    K --> L["트랜잭션 커밋"]
    L --> M["booking-events 발행<br/>status = CANCELLED"]
    M --> N["WebSocket 브로드캐스트<br/>좌석 AVAILABLE"]
    N --> O["200 BookingResponse 반환"]
```

---

## 5. JWT 인증 흐름

```mermaid
flowchart TD
    A["HTTP 요청<br/>Authorization: Bearer token"] --> B["JwtAuthenticationFilter"]
    B --> C{"토큰 추출 및 유효성 검증"}
    C -->|유효하지 않음| D["401 Unauthorized"]
    C -->|유효함| E["getAuthentication<br/>userId를 principal로 설정"]
    E --> F["SecurityContextHolder 저장"]
    F --> G["컨트롤러<br/>@AuthenticationPrincipal Long userId"]
    G --> H["서비스 호출"]

    subgraph REISSUE["토큰 재발급"]
        I["POST /api/auth/reissue<br/>refreshToken"] --> J{"Redis auth:refresh:&lt;userId&gt; 일치?"}
        J -->|불일치| K["401 유효하지 않은 토큰"]
        J -->|일치| L["기존 토큰 삭제"]
        L --> M["새 accessToken + refreshToken 발급"]
        M --> N["Redis 갱신<br/>auth:refresh:&lt;userId&gt; = 새 refreshToken"]
    end
```
