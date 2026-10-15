#!/usr/bin/env python3
"""Seed isolated users/slots and measure HTTP availability reads using only the stdlib.

Run against a disposable local Compose database. Each run adds new UUID-prefixed
users and slots; it never deletes data. Timings include HTTP/client overhead and
are a reproducible smoke measurement, not a production capacity guarantee.
"""

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timedelta, timezone
import json
import platform
import statistics
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import Request, urlopen
from uuid import UUID, uuid4


SLOTS_PER_DAY = 16
SLOT_MINUTES = 30
ANCHOR = datetime(2030, 2, 1, tzinfo=timezone.utc)
HTTP_TIMEOUT_SECONDS = 30


def positive_integer(value):
    number = int(value)
    if number <= 0:
        raise argparse.ArgumentTypeError("must be a positive integer")
    return number


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--users", type=positive_integer, default=200)
    parser.add_argument("--slots-per-user", type=positive_integer, default=25)
    parser.add_argument("--requests", type=positive_integer, default=400)
    parser.add_argument("--workers", type=positive_integer, default=8)
    args = parser.parse_args()
    try:
        parsed = urlsplit(args.base_url)
        parsed.port
    except ValueError:
        parser.error("--base-url is not a valid URL")
    if (parsed.scheme not in ("http", "https") or not parsed.hostname
            or parsed.username or parsed.password or parsed.query or parsed.fragment):
        parser.error("--base-url must be an HTTP(S) URL without credentials, query or fragment")
    if args.slots_per_user > SLOTS_PER_DAY * 31:
        parser.error("--slots-per-user must be <= 496 to keep the query window within 31 days")
    if args.workers > 64:
        parser.error("--workers must be <= 64")
    args.base_url = args.base_url.rstrip("/")
    return args


def timestamp(value):
    return value.isoformat().replace("+00:00", "Z")


def request_json(base_url, path, method="GET", payload=None, expected_status=200):
    body = None if payload is None else json.dumps(payload).encode("utf-8")
    headers = {"Accept": "application/json"}
    if body is not None:
        headers["Content-Type"] = "application/json"
    request = Request(base_url + path, data=body, headers=headers, method=method)
    try:
        with urlopen(request, timeout=HTTP_TIMEOUT_SECONDS) as response:
            if response.status != expected_status:
                raise RuntimeError(f"{method} {path}: expected {expected_status}, got {response.status}")
            return json.load(response)
    except HTTPError as error:
        detail = error.read(512).decode("utf-8", errors="replace")
        raise RuntimeError(f"{method} {path}: HTTP {error.code}: {detail}") from error
    except (URLError, TimeoutError, OSError, ValueError) as error:
        raise RuntimeError(f"{method} {path}: {error}") from error


def seed_user(args, run_id, index):
    user = request_json(args.base_url, "/users", method="POST", expected_status=201, payload={
        "name": f"Benchmark {run_id[:8]} / {index}",
        "email": f"benchmark-{run_id}-{index}@example.test",
        "timeZone": "UTC",
    })
    user_id = str(UUID(user["id"]))
    for slot_index in range(args.slots_per_user):
        day, within_day = divmod(slot_index, SLOTS_PER_DAY)
        start = ANCHOR + timedelta(days=day, hours=9, minutes=within_day * SLOT_MINUTES)
        request_json(args.base_url, f"/users/{user_id}/slots", method="POST", expected_status=201, payload={
            "startsAt": timestamp(start),
            "endsAt": timestamp(start + timedelta(minutes=SLOT_MINUTES)),
            "status": "BUSY" if slot_index % 5 == 0 else "FREE",
        })
    return user_id


def seed(args, run_id):
    user_ids = []
    with ThreadPoolExecutor(max_workers=args.workers) as executor:
        futures = [executor.submit(seed_user, args, run_id, index) for index in range(args.users)]
        try:
            for future in as_completed(futures):
                user_ids.append(future.result())
        except Exception:
            for future in futures:
                future.cancel()
            raise
    return sorted(user_ids)


def measure_request(args, user_id, query):
    started = time.perf_counter()
    try:
        response = request_json(args.base_url, f"/users/{user_id}/availability?{query}")
        if (not isinstance(response, dict) or not isinstance(response.get("free"), list)
                or not isinstance(response.get("busy"), list)
                or not (response["free"] or response["busy"])):
            raise RuntimeError("Unexpected availability response: expected nonempty free/busy intervals")
        return (time.perf_counter() - started) * 1000, None
    except Exception as error:
        return None, str(error)


def percentile(values, percent):
    ordered = sorted(values)
    position = (len(ordered) - 1) * percent / 100
    lower = int(position)
    upper = min(lower + 1, len(ordered) - 1)
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower)


def main():
    args = parse_args()
    run_id = uuid4().hex
    days = (args.slots_per_user + SLOTS_PER_DAY - 1) // SLOTS_PER_DAY
    window = {"from": timestamp(ANCHOR), "to": timestamp(ANCHOR + timedelta(days=days))}
    query = urlencode(window)
    print(f"Run {run_id}: seeding {args.users} users and "
          f"{args.users * args.slots_per_user} slots; data will remain in the database.", file=sys.stderr)
    seed_started = time.perf_counter()
    try:
        user_ids = seed(args, run_id)
    except Exception as error:
        print(f"Seeding failed: {error}\nRun {run_id} may have left partial data. "
              "Use a disposable Compose database.", file=sys.stderr)
        return 1
    seed_seconds = time.perf_counter() - seed_started
    print(f"Seed complete in {seed_seconds:.2f}s; measuring {args.requests} availability requests.",
          file=sys.stderr)
    query_started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=args.workers) as executor:
        results = list(executor.map(
            lambda index: measure_request(args, user_ids[index % len(user_ids)], query),
            range(args.requests),
        ))
    query_seconds = time.perf_counter() - query_started
    latencies = [latency for latency, error in results if error is None]
    errors = [error for _, error in results if error is not None]
    report = {
        "run_id": run_id,
        "base_url": args.base_url,
        "environment": {
            "client_platform": platform.platform(),
            "client_python": platform.python_version(),
            "note": "Client-observed HTTP timings; no separate warmup; server CPU/memory must be recorded separately.",
        },
        "dataset": {
            "users": len(user_ids),
            "slots_per_user": args.slots_per_user,
            "slots": len(user_ids) * args.slots_per_user,
            "window": window,
            "note": "16 half-hour slots/day from 09:00 UTC; every fifth slot BUSY; no meetings; data retained.",
        },
        "seed_seconds": round(seed_seconds, 3),
        "queries": {
            "attempted": args.requests,
            "succeeded": len(latencies),
            "errors": len(errors),
            "workers": args.workers,
            "elapsed_seconds": round(query_seconds, 3),
            "successful_requests_per_second": round(len(latencies) / query_seconds, 2),
            "successful_latency_ms": None if not latencies else {
                "mean": round(statistics.fmean(latencies), 3),
                "p50": round(percentile(latencies, 50), 3),
                "p95": round(percentile(latencies, 95), 3),
            },
        },
    }
    if errors:
        report["error_examples"] = errors[:5]
    print(json.dumps(report, indent=2))
    return 1 if errors else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("Interrupted; any data already created remains in the database.", file=sys.stderr)
        sys.exit(130)
