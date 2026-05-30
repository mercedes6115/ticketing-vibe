# Class Diagram — Ticketing System

> 도메인 모델과 서비스/리포지토리 계층의 책임 분리를 정리한 문서입니다. 클래스 간 관계를 빠르게 파악할 때 사용합니다.

| 빠른 정보 | 내용 |
|-----------|------|
| 목적 | Entity 모델과 서비스 계층의 책임을 시각적으로 요약 |
| 먼저 볼 것 | `Entity Model` → `Service · Repository Layer` |
| 함께 읽을 문서 | [ERD](../ERD.md), [시스템 아키텍처](architecture.md), [트랜잭션 흐름](transaction-flows.md) |

## 문서 구성

- [Entity Model](#1-entity-model)
- [Service · Repository Layer](#2-service--repository-layer)

## 1. Entity Model

```mermaid
classDiagram
    direction TB

    class BaseEntity {
        <<abstract>>
        #LocalDateTime createdAt
        #LocalDateTime updatedAt
    }

    class User {
        -Long id
        -String email
        -String nickname
        -UserRole role
        +updateNickname(String)
        +updatePassword(String)
        +updateRole(UserRole)
    }

    class Event {
        -Long id
        -String title
        -String venue
        -LocalDateTime startAt
        -LocalDateTime openAt
        -EventStatus status
        -Integer totalSeats
        -Integer availableSeats
        +isOpen() bool
        +canBook() bool
        +updateStatus(EventStatus)
    }

    class Seat {
        -Long id
        -String section
        -String seatRow
        -Integer seatNumber
        -Integer price
        -SeatStatus status
        +hold()
        +release()
        +sell()
        +isAvailable() bool
        +isHold() bool
    }

    class Booking {
        -Long id
        -String bookingNo
        -BookingStatus status
        +confirm()
        +cancel()
        +isCancelled() bool
    }

    class Payment {
        -Long id
        -Integer amount
        -PaymentMethod method
        -PaymentStatus status
        -String idempotencyKey
        -LocalDateTime paidAt
        +success()
        +refund()
        +isSuccess() bool
    }

    class UserRole {
        <<enumeration>>
        USER
        ADMIN
    }

    class EventStatus {
        <<enumeration>>
        SCHEDULED
        OPEN
        CLOSED
        CANCELLED
    }

    class SeatStatus {
        <<enumeration>>
        AVAILABLE
        HOLD
        SOLD
    }

    class BookingStatus {
        <<enumeration>>
        PENDING
        CONFIRMED
        CANCELLED
    }

    class PaymentStatus {
        <<enumeration>>
        PENDING
        SUCCESS
        FAILED
        REFUNDED
    }

    %% 상속
    BaseEntity <|-- User
    BaseEntity <|-- Event
    BaseEntity <|-- Seat
    BaseEntity <|-- Booking
    BaseEntity <|-- Payment

    %% 연관
    User      "1" o-- "*" Booking  : has
    Event     "1" o-- "*" Seat     : contains
    Event     "1" o-- "*" Booking  : tracks
    Booking   "*" --> "1" Seat     : occupies
    Booking   "1" *-- "1" Payment  : owns

    %% 열거형
    User    --> UserRole
    Event   --> EventStatus
    Seat    --> SeatStatus
    Booking --> BookingStatus
    Payment --> PaymentStatus
```

## 2. Service · Repository Layer

```mermaid
classDiagram
    direction LR

    %% ── Repositories ──────────────────────────────────────────────

    class UserRepository {
        <<interface>>
        +findByEmail(String) Optional~User~
        +existsByEmail(String) bool
    }

    class EventRepository {
        <<interface>>
        +findByStatus(EventStatus) Page
        +findByStatusIn(List~EventStatus~, Pageable) Page
        +findEventsToOpen(LocalDateTime) List
        +findEventsToClose(LocalDateTime) List
        +findByIdWithLock(Long) Optional~Event~
        +decreaseAvailableSeats(Long) int
        +increaseAvailableSeats(Long) int
    }

    class SeatRepository {
        <<interface>>
        +findByIdWithEvent(Long) Optional~Seat~
        +findByIdWithEventForUpdate(Long) Optional~Seat~
        +releaseHoldSeat(Long) int
    }

    class BookingRepository {
        <<interface>>
        +findByBookingNo(String) Optional~Booking~
        +findByIdWithEventAndSeat(Long) Optional~Booking~
        +existsBySeatIdAndStatusIn(Long, List) bool
    }

    class PaymentRepository {
        <<interface>>
    }

    %% ── Services ──────────────────────────────────────────────────

    class AuthService {
        -RedisTemplate redisTemplate
        -JwtTokenProvider jwtTokenProvider
        +signup(SignupRequest) TokenResponse
        +login(LoginRequest) TokenResponse
        +reissue(ReissueRequest) TokenResponse
        +logout(Long)
    }

    class EventService {
        +create(EventCreateRequest) EventResponse
        +getById(Long) EventResponse
        +update(Long, EventUpdateRequest) EventResponse
        +updateStatus(Long, EventStatus) EventResponse
        +uploadImage(Long, MultipartFile) EventResponse
        +delete(Long)
    }

    class SeatService {
        -RedissonClient redissonClient
        -RedisTemplate redisTemplate
        -SimpMessagingTemplate messagingTemplate
        +getSeatsByEventId(Long) List
        +holdSeat(Long seatId, Long userId) SeatResponse
        +releaseSeat(Long seatId, Long userId) SeatResponse
        +isHeldByUser(Long, Long) bool
    }

    class BookingService {
        -RedisTemplate redisTemplate
        -KafkaTemplate kafkaTemplate
        -BookingEventProducer eventProducer
        +createBooking(BookingCreateRequest, Long) BookingAcceptedResponse
        +persistBookingRequest(BookingRequestEvent) BookingEvent
        +getBookingStatus(String) BookingStatusResponse
        +cancelBooking(Long, Long) BookingResponse
    }

    class QueueService {
        -RedisTemplate redisTemplate
        -QueueEmitterManager emitterManager
        +enter(Long, Long) QueueStatusResponse
        +getStatus(Long, Long) QueueStatusResponse
        +issueToken(Long, Long) QueueTokenResponse
        +processQueue(Long) List~Long~
        +processAllActiveQueues()
    }

    %% 서비스 → 레포지토리 의존
    AuthService    --> UserRepository
    EventService   --> EventRepository
    SeatService    --> SeatRepository
    SeatService    --> EventRepository
    BookingService --> BookingRepository
    BookingService --> SeatRepository
    BookingService --> EventRepository
    BookingService --> UserRepository
```

---

## 3. Controller · Infrastructure Layer

```mermaid
flowchart TB
    subgraph Controllers
        AC["AuthController<br/>POST /api/auth/signup -> 201<br/>POST /api/auth/login -> 200<br/>POST /api/auth/reissue -> 200<br/>POST /api/auth/logout -> 200"]
        EC["EventController<br/>GET /api/events -> 200<br/>GET /api/events/:id -> 200<br/>POST /api/events -> 201<br/>PUT /api/events/:id -> 200<br/>PATCH /api/events/:id/status -> 200"]
        SC["SeatController<br/>GET /api/events/:eventId/seats -> 200<br/>GET /api/seats/:seatId -> 200<br/>POST /api/seats/:seatId/hold -> 200<br/>DELETE /api/seats/:seatId/hold -> 200"]
        BC["BookingController<br/>POST /api/bookings -> 202<br/>GET /api/bookings/status/:bookingNo -> 200<br/>GET /api/bookings/no/:bookingNo -> 200<br/>POST /api/bookings/:id/cancel -> 200"]
        QC["QueueController<br/>POST /api/queue/enter -> 200<br/>GET /api/queue/status -> 200<br/>GET /api/queue/stream -> SSE<br/>POST /api/queue/token -> 200<br/>POST /api/queue/exit -> 200"]
    end

    subgraph Services
        AS["AuthService"]
        ES["EventService"]
        SS["SeatService"]
        BS["BookingService"]
        QS["QueueService"]
    end

    subgraph Kafka
        BRC["BookingRequestConsumer<br/>@KafkaListener booking-requests"]
        BEP["BookingEventProducer<br/>booking-events producer"]
        KC["KafkaConfig<br/>10 req partitions / 5 event partitions<br/>DLQ + DefaultErrorHandler"]
    end

    subgraph RedisInfra
        RKL["RedisKeyExpirationListener<br/>seat hold TTL expiry"]
        JTP["JwtTokenProvider"]
    end

    AC --> AS
    EC --> ES
    SC --> SS
    BC --> BS
    QC --> QS

    BS -->|publish booking-requests| BRC
    BRC -->|persistBookingRequest| BS
    BRC -->|publish booking-events| BEP
    BS -->|cancel event publish| BEP
    BRC -.-> KC

    RKL -->|auto release expired holds| SS
    JTP --> AC
```

---

## Redis Key Map

| Key Pattern | 보유 서비스 | TTL |
|---|---|---|
| `auth:refresh:{userId}` | AuthService | 7일 |
| `queue:event:{eventId}` | QueueService | - |
| `queue:token:{userId}:{eventId}` | QueueService | 10분 |
| `seat:hold:{seatId}` | SeatService | 5분 |
| `seat:lock:{seatId}` | SeatService (Redisson) | 10초 |
| `booking:status:{bookingNo}` | BookingService | 10분 |
