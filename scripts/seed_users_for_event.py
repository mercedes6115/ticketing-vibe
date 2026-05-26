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
EMAIL_PREFIX = "eventtest_"
EMAIL_DOMAIN = "@test.com"


def hash_password(password: str) -> str:
    return bcrypt.hashpw(password.encode(), bcrypt.gensalt(rounds=10)).decode()


def clean_previous_data(cur, conn, r: redis.Redis, event_id: int) -> None:
    r.delete(f"queue:event:{event_id}")
    r.srem("queue:active:events", str(event_id))

    token_keys = list(r.scan_iter(f"queue:token:*:{event_id}"))
    if token_keys:
        r.delete(*token_keys)

    cur.execute("DELETE FROM users WHERE email LIKE %s", (f"{EMAIL_PREFIX}%",))
    conn.commit()
    print(f"Cleaned previous event test users and queue keys for event_id={event_id}")


def seed(event_id: int, n_users: int, clean: bool) -> None:
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

    cur.execute(
        "SELECT id, title, status, total_seats, available_seats FROM events WHERE id = %s",
        (event_id,),
    )
    event = cur.fetchone()
    if not event:
        print(f"Event not found: {event_id}")
        sys.exit(1)

    cur.execute("SELECT status, COUNT(*) FROM seats WHERE event_id = %s GROUP BY status", (event_id,))
    seat_counts = {status: count for status, count in cur.fetchall()}

    if clean:
        clean_previous_data(cur, conn, r, event_id)

    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    password_hash = hash_password(TEST_PASSWORD)
    user_values = [
        (f"{EMAIL_PREFIX}{event_id}_{i}{EMAIL_DOMAIN}", password_hash, f"eventtest{event_id}_{i}", "USER", now, now)
        for i in range(1, n_users + 1)
    ]

    cur.executemany(
        "INSERT INTO users (email, password, nickname, role, created_at, updated_at) "
        "VALUES (%s, %s, %s, %s, %s, %s)",
        user_values,
    )
    conn.commit()

    emails = [f"{EMAIL_PREFIX}{event_id}_{i}{EMAIL_DOMAIN}" for i in range(1, n_users + 1)]
    placeholders = ",".join(["%s"] * len(emails))
    cur.execute(f"SELECT id, email FROM users WHERE email IN ({placeholders})", emails)
    email_to_id = {email: user_id for user_id, email in cur.fetchall()}

    users = [
        {
            "userId": email_to_id[f"{EMAIL_PREFIX}{event_id}_{i}{EMAIL_DOMAIN}"],
            "email": f"{EMAIL_PREFIX}{event_id}_{i}{EMAIL_DOMAIN}",
            "password": TEST_PASSWORD,
        }
        for i in range(1, n_users + 1)
    ]

    output_path = "scripts/full_flow_test_data.json"
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump({"eventId": event_id, "users": users}, f, ensure_ascii=False, indent=2)

    print("Seed complete")
    print(f"  event_id: {event_id}")
    print(f"  event_title: {event[1]}")
    print(f"  event_status: {event[2]}")
    print(f"  total_seats: {event[3]}")
    print(f"  available_seats: {event[4]}")
    print(f"  seat_status_counts: {seat_counts}")
    print(f"  users: {n_users}")
    print(f"  output: {output_path}")

    cur.close()
    conn.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Create k6 users for an existing event")
    parser.add_argument("--event-id", type=int, required=True, help="Existing event id to test")
    parser.add_argument("--users", type=int, default=100, help="Number of test users to create")
    parser.add_argument("--clean", action="store_true", help="Clean previous test users and queue keys")
    args = parser.parse_args()

    if args.users < 1 or args.users > 10000:
        print("--users must be between 1 and 10000")
        sys.exit(1)

    seed(args.event_id, args.users, args.clean)
