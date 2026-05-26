# Database ERD

```mermaid
erDiagram
    users {
        BIGINT id PK
        VARCHAR_100 email "NOT NULL, UNIQUE"
        VARCHAR password "NOT NULL"
        VARCHAR_50 nickname "NOT NULL"
        ENUM role "USER | ADMIN, NOT NULL"
        DATETIME created_at "NOT NULL"
        DATETIME updated_at "NOT NULL"
    }

    events {
        BIGINT id PK
        VARCHAR_200 title "NOT NULL"
        TEXT description
        VARCHAR_200 venue "NOT NULL"
        VARCHAR_500 image_url
        DATETIME start_at "NOT NULL"
        DATETIME open_at "NOT NULL"
        ENUM status "SCHEDULED | OPEN | CLOSED | CANCELLED, NOT NULL"
        INT total_seats "NOT NULL"
        INT available_seats "NOT NULL"
        DATETIME created_at "NOT NULL"
        DATETIME updated_at "NOT NULL"
    }

    seats {
        BIGINT id PK
        BIGINT event_id FK "NOT NULL"
        VARCHAR_50 section "NOT NULL"
        VARCHAR_10 seat_row "NOT NULL"
        INT seat_number "NOT NULL"
        INT price "NOT NULL, DEFAULT 0"
        ENUM status "AVAILABLE | HOLD | SOLD, NOT NULL"
        DATETIME created_at "NOT NULL"
        DATETIME updated_at "NOT NULL"
    }

    bookings {
        BIGINT id PK
        BIGINT user_id FK "NOT NULL"
        BIGINT event_id FK "NOT NULL"
        BIGINT seat_id FK "NOT NULL"
        VARCHAR_20 booking_no "NOT NULL, UNIQUE"
        ENUM status "PENDING | CONFIRMED | CANCELLED, NOT NULL"
        DATETIME created_at "NOT NULL"
        DATETIME updated_at "NOT NULL"
    }

    payments {
        BIGINT id PK
        BIGINT booking_id FK "NOT NULL, UNIQUE"
        INT amount "NOT NULL, DEFAULT 0"
        ENUM method "CARD | BANK_TRANSFER | VIRTUAL_ACCOUNT, NOT NULL"
        ENUM status "PENDING | SUCCESS | FAILED | REFUNDED, NOT NULL"
        VARCHAR_36 idempotency_key "NOT NULL, UNIQUE"
        DATETIME paid_at
        DATETIME created_at "NOT NULL"
        DATETIME updated_at "NOT NULL"
    }

    users ||--o{ bookings : "1:N"
    events ||--o{ seats : "1:N (cascade)"
    events ||--o{ bookings : "1:N"
    bookings }o--|| seats : "N:1"
    bookings ||--|| payments : "1:1 (cascade)"
```

## 제약 조건 정리

| 테이블 | 제약 | 설명 |
|--------|------|------|
| `seats` | UNIQUE(event_id, section, seat_row, seat_number) | 동일 이벤트 내 좌석 중복 방지 |
| `bookings` | UNIQUE(booking_no) | 예매번호 전역 유일 |
| `payments` | UNIQUE(booking_id) | 예매당 결제 1건 보장 (1:1) |
| `payments` | UNIQUE(idempotency_key) | 결제 중복 방지 (멱등성 키) |
| `users` | UNIQUE(email) | 이메일 중복 가입 방지 |

## 관계 요약

| 관계 | 카디널리티 | 비고 |
|------|-----------|------|
| users → bookings | 1:N | 한 유저가 여러 예매 보유 |
| events → seats | 1:N | 한 이벤트에 여러 좌석, cascade ALL |
| events → bookings | 1:N | 한 이벤트에 여러 예매 |
| seats → bookings | 1:N | 한 좌석이 여러 예매(취소 후 재예매) |
| bookings → payments | 1:1 | 예매당 결제 1건, cascade ALL |
