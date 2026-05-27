#!/usr/bin/env python3
import argparse
import base64
import json
import hmac
import os
import sys
import time
from datetime import datetime
from hashlib import sha256

import bcrypt
import mysql.connector
import redis

MYSQL_HOST = os.getenv("MYSQL_HOST", "localhost")
MYSQL_PORT = int(os.getenv("MYSQL_PORT", "3307"))
MYSQL_DB = os.getenv("MYSQL_DB", "ticketing")
MYSQL_USER = os.getenv("MYSQL_USER", "ticketing")
MYSQL_PASSWORD = os.getenv("MYSQL_PASSWORD", "ticketing1234")

REDIS_HOST = os.getenv("REDIS_HOST", "localhost")
REDIS_PORT = int(os.getenv("REDIS_PORT", "6379"))

TEST_PASSWORD = "LoadTest1234!"
EMAIL_PREFIX = "imyw_"
EMAIL_DOMAIN = "@test.com"
EVENT_TITLE = "임영웅 서울월드컵경기장 예매 부하테스트"
EVENT_VENUE = "서울월드컵경기장"
SEAT_PRICE = 154000
BATCH_SIZE = 5000
JWT_SECRET = os.getenv("JWT_SECRET", "local-dev-secret-change-in-production-must-be-long")
JWT_ACCESS_TOKEN_EXPIRY_MS = int(os.getenv("JWT_ACCESS_TOKEN_EXPIRY_MS", "900000"))


def hash_password(password: str) -> str:
    return bcrypt.hashpw(password.encode(), bcrypt.gensalt(rounds=10)).decode()


def chunked(items, size: int):
    for i in range(0, len(items), size):
        yield items[i:i + size]


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def create_access_token(user_id: int, role: str = "USER") -> str:
    now = int(time.time())
    header = {"alg": "HS256", "typ": "JWT"}
    payload = {
        "sub": str(user_id),
        "role": role,
        "type": "access",
        "iat": now,
        "exp": now + (JWT_ACCESS_TOKEN_EXPIRY_MS // 1000),
    }

    encoded_header = b64url(
        json.dumps(header, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    )
    encoded_payload = b64url(
        json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    )
    signing_input = f"{encoded_header}.{encoded_payload}".encode("utf-8")
    signature = hmac.new(JWT_SECRET.encode("utf-8"), signing_input, sha256).digest()
    return f"{encoded_header}.{encoded_payload}.{b64url(signature)}"


def clean_previous_data(cur, conn, r: redis.Redis) -> None:
    cur.execute("SELECT id FROM events WHERE title = %s", (EVENT_TITLE,))
    event_rows = cur.fetchall()
    if event_rows:
        event_id = event_rows[0][0]
        cur.execute(
            "DELETE p FROM payments p JOIN bookings b ON p.booking_id = b.id WHERE b.event_id = %s",
            (event_id,),
        )
        cur.execute("DELETE FROM bookings WHERE event_id = %s", (event_id,))
        cur.execute("DELETE FROM seats WHERE event_id = %s", (event_id,))
        cur.execute("DELETE FROM events WHERE id = %s", (event_id,))
        conn.commit()

        r.delete(f"queue:event:{event_id}")
        r.srem("queue:active:events", str(event_id))
        token_keys = list(r.scan_iter(f"queue:token:*:{event_id}"))
        if token_keys:
            r.delete(*token_keys)
        print(f"Deleted previous event: event_id={event_id}")

    cur.execute("DELETE FROM users WHERE email LIKE %s", (f"{EMAIL_PREFIX}%",))
    conn.commit()
    print("Deleted previous imyoungwoong load test users")


def seed(n_users: int, n_seats: int, clean: bool) -> None:
    conn = mysql.connector.connect(
        host=MYSQL_HOST,
        port=MYSQL_PORT,
        database=MYSQL_DB,
        user=MYSQL_USER,
        password=MYSQL_PASSWORD,
        charset="utf8mb4",
    )
    cur = conn.cursor()
    r = redis.Redis(host=REDIS_HOST, port=REDIS_PORT, decode_responses=True)

    if clean:
        print("[1/4] Cleaning previous test data")
        clean_previous_data(cur, conn, r)
    else:
        print("[1/4] Skipping cleanup")

    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")

    print(f"[2/4] Creating users: {n_users}")
    password_hash = hash_password(TEST_PASSWORD)
    user_values = [
        (f"{EMAIL_PREFIX}{i}{EMAIL_DOMAIN}", password_hash, f"imyw{i}", "USER", now, now)
        for i in range(1, n_users + 1)
    ]
    for batch in chunked(user_values, BATCH_SIZE):
        cur.executemany(
            "INSERT INTO users (email, password, nickname, role, created_at, updated_at) "
            "VALUES (%s, %s, %s, %s, %s, %s)",
            batch,
        )
    conn.commit()

    cur.execute("SELECT id, email FROM users WHERE email LIKE %s", (f"{EMAIL_PREFIX}%",))
    email_to_id = {email: user_id for user_id, email in cur.fetchall()}
    user_ids = [email_to_id[f"{EMAIL_PREFIX}{i}{EMAIL_DOMAIN}"] for i in range(1, n_users + 1)]

    print(f"[3/4] Creating event with seats: {n_seats}")
    cur.execute(
        """
        INSERT INTO events
            (title, description, venue, start_at, open_at, status, total_seats, available_seats,
             created_at, updated_at)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
        """,
        (
            EVENT_TITLE,
            "임영웅 서울월드컵경기장 예매 대기열/좌석 경쟁 부하테스트",
            EVENT_VENUE,
            "2026-12-31 19:00:00",
            "2026-04-19 20:00:00",
            "OPEN",
            n_seats,
            n_seats,
            now,
            now,
        ),
    )
    conn.commit()
    event_id = cur.lastrowid

    print("[4/4] Creating available seats")
    seats_per_row = 20
    seat_values = [
        (
            event_id,
            "WORLDCUP",
            str((i - 1) // seats_per_row + 1),
            (i - 1) % seats_per_row + 1,
            SEAT_PRICE,
            "AVAILABLE",
            now,
            now,
        )
        for i in range(1, n_seats + 1)
    ]
    for batch in chunked(seat_values, BATCH_SIZE):
        cur.executemany(
            "INSERT INTO seats (event_id, section, seat_row, seat_number, price, status, created_at, updated_at) "
            "VALUES (%s, %s, %s, %s, %s, %s, %s, %s)",
            batch,
        )
    conn.commit()

    output_path = "scripts/full_flow_test_data.json"
    data = {
        "eventId": event_id,
        "users": [
            {
                "userId": user_id,
                "email": f"{EMAIL_PREFIX}{i + 1}{EMAIL_DOMAIN}",
                "password": TEST_PASSWORD,
                "accessToken": create_access_token(user_id),
            }
            for i, user_id in enumerate(user_ids)
        ],
    }
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)

    print("Seed complete")
    print(f"  event title: {EVENT_TITLE}")
    print(f"  venue: {EVENT_VENUE}")
    print(f"  users: {n_users}")
    print(f"  event_id: {event_id}")
    print(f"  seats: {n_seats}")
    print(f"  output: {output_path}")

    cur.close()
    conn.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Create seed data for 임영웅 월드컵경기장 k6 full-flow tests")
    parser.add_argument("--users", type=int, default=20000, help="Number of users to create")
    parser.add_argument("--seats", type=int, default=66704, help="Number of seats to create")
    parser.add_argument("--clean", action="store_true", help="Clean previous test data before seeding")
    args = parser.parse_args()

    if args.users < 1:
        print("--users must be at least 1")
        sys.exit(1)
    if args.seats < 1 or args.seats > 66704:
        print("--seats must be between 1 and 66704")
        sys.exit(1)
    seed(args.users, args.seats, args.clean)
