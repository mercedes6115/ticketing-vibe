-- =====================================================
-- Ticketing System Database Schema
-- =====================================================

-- -----------------------------------------------------
-- Table: users
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(100) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    nickname VARCHAR(50) NOT NULL,
    role ENUM('USER', 'ADMIN') NOT NULL DEFAULT 'USER',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_users_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------
-- Table: events
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    description TEXT,
    venue VARCHAR(200) NOT NULL,
    image_url VARCHAR(500),
    start_at DATETIME NOT NULL COMMENT '공연 시작 시간',
    open_at DATETIME NOT NULL COMMENT '예매 오픈 시간',
    status ENUM('SCHEDULED', 'OPEN', 'CLOSED', 'CANCELLED') NOT NULL DEFAULT 'SCHEDULED',
    total_seats INT NOT NULL DEFAULT 0,
    available_seats INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_events_status (status),
    INDEX idx_events_open_at (open_at),
    INDEX idx_events_start_at (start_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------
-- Table: seats
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS seats (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id BIGINT NOT NULL,
    section VARCHAR(50) NOT NULL COMMENT '구역 (A, B, VIP 등)',
    seat_row VARCHAR(10) NOT NULL COMMENT '열 (1, 2, A, B 등)',
    seat_number INT NOT NULL COMMENT '좌석 번호',
    price INT NOT NULL DEFAULT 0,
    status ENUM('AVAILABLE', 'HOLD', 'SOLD') NOT NULL DEFAULT 'AVAILABLE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_seats_event FOREIGN KEY (event_id) REFERENCES events(id) ON DELETE CASCADE,

    INDEX idx_seats_event_id (event_id),
    INDEX idx_seats_status (status),
    UNIQUE INDEX idx_seats_unique (event_id, section, seat_row, seat_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------
-- Table: bookings
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS bookings (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    event_id BIGINT NOT NULL,
    seat_id BIGINT NOT NULL,
    booking_no VARCHAR(20) NOT NULL UNIQUE COMMENT '예매 번호 (ex: BK20240414001)',
    status ENUM('PENDING', 'CONFIRMED', 'CANCELLED') NOT NULL DEFAULT 'PENDING',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_bookings_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_bookings_event FOREIGN KEY (event_id) REFERENCES events(id) ON DELETE CASCADE,
    CONSTRAINT fk_bookings_seat FOREIGN KEY (seat_id) REFERENCES seats(id) ON DELETE CASCADE,

    INDEX idx_bookings_user_id (user_id),
    INDEX idx_bookings_event_id (event_id),
    INDEX idx_bookings_seat_id (seat_id),
    INDEX idx_bookings_status (status),
    INDEX idx_bookings_booking_no (booking_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------
-- Table: payments
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS payments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    booking_id BIGINT NOT NULL,
    amount INT NOT NULL DEFAULT 0,
    method ENUM('CARD', 'BANK_TRANSFER', 'VIRTUAL_ACCOUNT') NOT NULL,
    status ENUM('PENDING', 'SUCCESS', 'FAILED', 'REFUNDED') NOT NULL DEFAULT 'PENDING',
    idempotency_key VARCHAR(36) NOT NULL UNIQUE COMMENT '멱등성 키 (UUID)',
    paid_at DATETIME NULL COMMENT '결제 완료 시간',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_payments_booking FOREIGN KEY (booking_id) REFERENCES bookings(id) ON DELETE CASCADE,

    INDEX idx_payments_booking_id (booking_id),
    INDEX idx_payments_status (status),
    INDEX idx_payments_idempotency_key (idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =====================================================
-- Redis Key 설계 (참고용 - DB 테이블 아님)
-- =====================================================
-- seat:hold:{seatId}         → userId (TTL 300초/5분) - 좌석 임시 선점
-- seat:status:{eventId}      → Hash (좌석 전체 상태 캐싱)
-- queue:event:{eventId}      → Sorted Set (대기열, score=timestamp)
-- queue:token:{userId}       → UUID (TTL 600초/10분) - 입장 토큰
-- auth:refresh:{userId}      → Refresh Token (TTL 7일)
-- event:cache:{eventId}      → Event 상세 정보 캐싱 (TTL 60초)
