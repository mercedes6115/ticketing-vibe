# 시스템 아키텍처

---

## 전체 구성도

```mermaid
graph TB
    subgraph Client["클라이언트"]
        FE["React 18 + Vite\nZustand / TailwindCSS\nSTOMP.js + SockJS"]
    end

    subgraph API["Spring Boot 3.2 (API Server)"]
        direction TB
        REST["REST API"]
        WS_EP["WebSocket / STOMP /ws"]
        SSE_EP["SSE /api/queue/stream"]

        subgraph SVC["Service Layer"]
            QS["QueueService"]
            SS["SeatService"]
            BS["BookingService"]
            AS["AuthService"]
        end

        subgraph KAFKA_LAYER["Kafka Layer"]
            PROD["BookingEventProducer"]
            CON1["BookingRequestConsumer"]
            CON2["BookingEventConsumer"]
        end

        subgraph CONFIG["Config / Infra"]
            SEC["SecurityConfig JWT"]
            LOCK["Redisson RLock"]
            EXP["RedisKeyExpirationListener"]
            EMG["QueueEmitterManager SSE"]
        end
    end

    subgraph INFRA["Infrastructure (Docker Compose)"]
        REDIS["Redis 7\n대기열 · 분산락 · 홀드 · 상태"]
        MYSQL["MySQL 8\nUsers · Events · Seats · Bookings · Payments"]
        KAFKA["Kafka 3.7 KRaft\nbooking-requests / booking-events"]
    end

    FE -->|"HTTP/REST (JWT)"| REST
    FE <-->|"WebSocket / STOMP"| WS_EP
    FE <-->|"SSE"| SSE_EP

    REST --> SVC
    WS_EP --> SS
    SSE_EP --> QS

    SVC --> REDIS
    SVC --> MYSQL
    SVC --> PROD

    PROD --> KAFKA
    KAFKA --> CON1
    KAFKA --> CON2
    CON1 --> BS
    CON1 --> REDIS

    EXP --> MYSQL
    EXP --> WS_EP
```

---

## 기술 스택

### Backend

| 항목 | 기술 |
|------|------|
| 언어 / 프레임워크 | Java 17, Spring Boot 3.2 |
| ORM | Spring Data JPA + Hibernate |
| 인증 | JWT (Access 30분 / Refresh 7일) |
| 실시간 - 좌석 | [WebSocket / STOMP](https://www.notion.so/WebSocket-STOMP-36c05755fb8780c09420f763293a2d65?source=copy_link) |
| 실시간 - 대기열 | [SSE (Server-Sent Events)](https://www.notion.so/SSE-36c05755fb8780b78318f699da2c1628?source=copy_link) |
| 분산락 | Redisson RLock |
| 메시지 큐 | [Apache Kafka 3.7 (KRaft)](https://www.notion.so/Kafka-36c05755fb878076a9a1dd6328913883?source=copy_link) |
| 캐시 / 상태 | Redis 7 + Sentinel |

### Frontend

| 항목 | 기술 |
|------|------|
| 프레임워크 | React 18 + TypeScript + Vite |
| 상태관리 | Zustand |
| 스타일 | TailwindCSS |
| WebSocket | STOMP.js + SockJS |
| HTTP | Axios |

### Infrastructure

| 항목 | 기술 |
|------|------|
| 컨테이너 | Docker Compose |
| DB | MySQL 8 |
| 캐시 / 큐 | Redis 7 + Sentinel |
| 메시지 브로커 | Kafka 3.7 (KRaft, Zookeeper 없음) |

---

## Redis 키 구조

| 키 패턴 | 타입 | TTL | 용도 |
|---------|------|-----|------|
| `queue:event:{eventId}` | Sorted Set | - | 대기열 (score = 진입 timestamp) |
| `queue:active:events` | Set | - | 활성 이벤트 목록 (processQueue 대상) |
| `queue:token:{userId}:{eventId}` | String | 10분 | 입장 토큰 |
| `seat:hold:{seatId}` | String | 5분 | 좌석 홀드 (value = userId) |
| `seat:lock:{seatId}` | String | 10초 | Redisson 분산락 |
| `booking:status:{bookingNo}` | String | 10분 | 예매 처리 상태 (PROCESSING / CONFIRMED / FAILED) |
| `auth:refresh:{userId}` | String | 7일 | Refresh Token |

---

## [Kafka](https://www.notion.so/Kafka-36c05755fb878076a9a1dd6328913883?source=copy_link) 토픽 설계

| 토픽 | 파티션 | 파티션 키 | 역할 |
|------|--------|-----------|------|
| `booking-requests` | 10 | userId | 예매 요청 → Consumer가 DB 쓰기 |
| `booking-requests.DLQ` | 10 | - | 3회 재시도 실패 메시지 격리 |
| `booking-events` | 5 | bookingId | 예매 결과 → 알림 / 통계 다운스트림 |
| `booking-events.DLQ` | 5 | - | DLQ |

- `userId` 키: 같은 사용자 요청이 항상 같은 파티션 → 순서 보장
- Consumer Group: `booking-requests-group` (DB 쓰기), `ticketing-group` (알림)
- 에러 핸들링: [`DefaultErrorHandler / DLQ`](https://www.notion.so/DLQ-DefaultErrorHandler-36c05755fb8780749b62f0e778699920?source=copy_link) 1초 간격 3회 재시도 후 `.DLQ` 격리
- `NonRetryableBookingException`은 Consumer에서 `FAILED`로 마감하고 재throw하지 않는다.

---

## 데이터베이스 ERD (요약)

```mermaid
erDiagram
    USER {
        bigint id PK
        string email
        string password
        string nickname
        string role
    }
    EVENT {
        bigint id PK
        string title
        string venue
        datetime startAt
        datetime openAt
        int totalSeats
        int availableSeats
        string status
    }
    SEAT {
        bigint id PK
        bigint event_id FK
        string section
        string seatRow
        int seatNumber
        int price
        string status
    }
    BOOKING {
        bigint id PK
        bigint user_id FK
        bigint event_id FK
        bigint seat_id FK
        string bookingNo
        string status
    }
    PAYMENT {
        bigint id PK
        bigint booking_id FK
        int amount
        string method
        string status
        string idempotencyKey
    }

    USER ||--o{ BOOKING : "예매"
    EVENT ||--o{ SEAT : "구성"
    EVENT ||--o{ BOOKING : "대상"
    SEAT ||--|| BOOKING : "1:1"
    BOOKING ||--|| PAYMENT : "결제"
```

---

## 포트 구성

| 서비스 | 포트 |
|--------|------|
| Frontend (Vite) | 5173 |
| Backend (Spring Boot) | 8080 |
| MySQL | 3306 |
| Redis | 6379 |
| Kafka | 9092 |
