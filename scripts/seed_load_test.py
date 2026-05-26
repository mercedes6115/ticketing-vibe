#!/usr/bin/env python3
import argparse
import json
import os
import sys
from datetime import datetime

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
EMAIL_PREFIX = "loadtest_"
EMAIL_DOMAIN = "@test.com"
EVENT_TITLE = "Load Test Concert 2026"
SEAT_PRICE = 50000
HOLD_TTL_SECONDS = 7200
SEATS_PER_ROW = 10


def hash_password(password: str) -> str:
    return bcrypt.hashpw(password.encode(), bcrypt.gensalt(rounds=10)).decode()


def clean_previous_data(cur, conn, r: redis.Redis) -> None:
    cur.execute("SELECT id FROM events WHERE title = %s", (EVENT_TITLE,))
    event_rows = cur.fetchall()
    if event_rows:
        event_id = event_rows[0][0]
        cur.execute("SELECT id FROM seats WHERE event_id = %s", (event_id,))
        seat_ids = [row[0] for row in cur.fetchall()]
        if seat_ids:
            pipe = r.pipeline()
            for seat_id in seat_ids:
                pipe.delete(f"seat:hold:{seat_id}")
            pipe.execute()
            print(f"Deleted Redis hold keys: {len(seat_ids)}")

        cur.execute(
            "DELETE p FROM payments p JOIN bookings b ON p.booking_id = b.id WHERE b.event_id = %s",
            (event_id,),
        )
        cur.execute("DELETE FROM bookings WHERE event_id = %s", (event_id,))
        cur.execute("DELETE FROM seats WHERE event_id = %s", (event_id,))
        cur.execute("DELETE FROM events WHERE id = %s", (event_id,))
        conn.commit()
        print(f"Deleted previous event and related rows: event_id={event_id}")

    cur.execute("DELETE FROM users WHERE email LIKE %s", (f"{EMAIL_PREFIX}%",))
    conn.commit()
    print("Deleted previous load test users")


def seed(n_users: int, clean: bool) -> None:
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
        print("[1/5] Cleaning previous test data")
        clean_previous_data(cur, conn, r)
    else:
        print("[1/5] Skipping cleanup")

    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")

    print(f"[2/5] Creating users: {n_users}")
    password_hash = hash_password(TEST_PASSWORD)
    user_values = [
        (f"{EMAIL_PREFIX}{i}{EMAIL_DOMAIN}", password_hash, f"loadtest{i}", "USER", now, now)
        for i in range(1, n_users + 1)
    ]
    cur.executemany(
        "INSERT INTO users (email, password, nickname, role, created_at, updated_at) "
        "VALUES (%s, %s, %s, %s, %s, %s)",
        user_values,
    )
    conn.commit()

    emails = [f"{EMAIL_PREFIX}{i}{EMAIL_DOMAIN}" for i in range(1, n_users + 1)]
    placeholders = ",".join(["%s"] * len(emails))
    cur.execute(f"SELECT id, email FROM users WHERE email IN ({placeholders})", emails)
    email_to_id = {email: user_id for user_id, email in cur.fetchall()}
    user_ids = [email_to_id[f"{EMAIL_PREFIX}{i}{EMAIL_DOMAIN}"] for i in range(1, n_users + 1)]

    print("[3/5] Creating event")
    cur.execute(
        """
        INSERT INTO events
            (title, description, venue, start_at, open_at, status, total_seats, available_seats,
             created_at, updated_at)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
        """,
        (
            EVENT_TITLE,
            "k6 booking load test event",
            "Load Test Venue",
            "2026-12-31 19:00:00",
            "2026-04-19 00:00:00",
            "OPEN",
            n_users,
            n_users,
            now,
            now,
        ),
    )
    conn.commit()
    event_id = cur.lastrowid

    print(f"[4/5] Creating held seats: {n_users}")
    seat_values = [
        (
            event_id,
            "LOAD",
            str((i - 1) // SEATS_PER_ROW + 1),
            (i - 1) % SEATS_PER_ROW + 1,
            SEAT_PRICE,
            "HOLD",
            now,
            now,
        )
        for i in range(1, n_users + 1)
    ]
    cur.executemany(
        "INSERT INTO seats (event_id, section, seat_row, seat_number, price, status, created_at, updated_at) "
        "VALUES (%s, %s, %s, %s, %s, %s, %s, %s)",
        seat_values,
    )
    conn.commit()

    cur.execute("SELECT id FROM seats WHERE event_id = %s ORDER BY id", (event_id,))
    seat_ids = [row[0] for row in cur.fetchall()]

    print("[5/5] Creating Redis hold keys")
    pipe = r.pipeline()
    for user_id, seat_id in zip(user_ids, seat_ids):
        pipe.set(f"seat:hold:{seat_id}", user_id, ex=HOLD_TTL_SECONDS)
    pipe.execute()

    output_path = "scripts/load_test_data.json"
    data = [
        {
            "userId": user_id,
            "seatId": seat_id,
            "email": f"{EMAIL_PREFIX}{i + 1}{EMAIL_DOMAIN}",
            "password": TEST_PASSWORD,
        }
        for i, (user_id, seat_id) in enumerate(zip(user_ids, seat_ids))
    ]
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)

    print("Seed complete")
    print(f"  users: {n_users}")
    print(f"  event_id: {event_id}")
    print(f"  seats: {len(seat_ids)}")
    print(f"  output: {output_path}")

    cur.close()
    conn.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Create seed data for k6 booking load tests")
    parser.add_argument("--users", type=int, default=1000, help="Number of users/seats to create")
    parser.add_argument("--clean", action="store_true", help="Clean previous test data before seeding")
    args = parser.parse_args()

    if args.users < 1 or args.users > 10000:
        print("--users must be between 1 and 10000")
        sys.exit(1)

    seed(args.users, args.clean)
