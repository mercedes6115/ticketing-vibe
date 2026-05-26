# Class Diagram — Ticketing System

---

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

---

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
        +findEventsToOpen(LocalDateTime) List
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
        +holdSeat(Long userId) SeatResponse
        +releaseSeat(Long userId) SeatResponse
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
classDiagram
    direction TB

    %% ── Controllers ───────────────────────────────────────────────

    class AuthController {
        POST /api/auth/signup → 201
        POST /api/auth/login → 200
        POST /api/auth/reissue → 200
        POST /api/auth/logout → 200
    }

    class EventController {
        GET  /api/events → 200
        GET  /api/events/{id} → 200
        POST /api/events → 201
        PUT  /api/events/{id} → 200
        PATCH /api/events/{id}/status → 200
    }

    class SeatController {
        GET    /api/events/{id}/seats → 200
        POST   /api/seats/{id}/hold → 200
        DELETE /api/seats/{id}/hold → 200
    }

    class BookingController {
        POST /api/bookings → 202
        GET  /api/bookings/status/{no} → 200
        POST /api/bookings/{id}/cancel → 200
        GET  /api/bookings/my → 200
    }

    class QueueController {
        POST /api/queue/enter → 200
        GET  /api/queue/status → 200
        GET  /api/queue/stream → SSE
        POST /api/queue/token → 200
    }

    %% ── Kafka ─────────────────────────────────────────────────────

    class BookingRequestConsumer {
        <<@KafkaListener booking-requests>>
        +consume(BookingRequestEvent)
    }

    class BookingEventProducer {
        <<booking-events>>
        +send(BookingEvent) CompletableFuture
    }

    class KafkaConfig {
        <<@Configuration>>
        bookingRequestsTopic : partitions=10
        bookingEventsTopic   : partitions=5
        bookingRequestsDlqTopic
        bookingEventsDlqTopic
        DefaultErrorHandler  : FixedBackOff(1s × 3)
        DeadLetterPublishingRecoverer
    }

    %% ── Redis 인프라 ───────────────────────────────────────────────

    class RedisKeyExpirationListener {
        <<@Component keyevent:expired>>
        +onMessage(Message)
    }

    class JwtTokenProvider {
        +createAccessToken(Long, String) String
        +createRefreshToken(Long) String
        +validateToken(String) bool
        +getUserId(String) Long
    }

    %% 컨트롤러 → 서비스
    AuthController    --> AuthService
    EventController   --> EventService
    SeatController    --> SeatService
    BookingController --> BookingService
    QueueController   --> QueueService

    %% Kafka 흐름
    BookingService          --> BookingRequestConsumer  : publishes to\nbooking-requests
    BookingRequestConsumer  --> BookingService          : persistBookingRequest()
    BookingRequestConsumer  --> BookingEventProducer    : downstream
    BookingService          --> BookingEventProducer    : cancelBooking()
    BookingRequestConsumer  ..> KafkaConfig             : uses error handler

    %% Redis 자동 해제
    RedisKeyExpirationListener --> SeatRepository : seat:hold TTL expiry\n→ auto-release
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
