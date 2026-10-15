# Mini Doodle

A persisted meeting scheduling simulation built with **Java 21, Spring Boot 3.5.3, Spring Data JPA and PostgreSQL 17**. Users declare availability, book meetings with participants, and query personal or shared free time. The service focuses on atomic booking, explicit interval semantics and efficient bounded queries.

## Run locally

Install Docker with Docker Compose v2, then run from the repository root:

```sh
docker compose up --build -d --wait
```

Compose builds the application, starts PostgreSQL, waits for health checks and applies Flyway migrations automatically. Java and Maven are included in the Docker build; neither is needed on the host for this command.

- API: [http://localhost:8080](http://localhost:8080)
- Interactive API documentation: [Swagger UI](http://localhost:8080/swagger-ui/index.html)
- OpenAPI schema: [/v3/api-docs](http://localhost:8080/v3/api-docs)
- Health: [/actuator/health](http://localhost:8080/actuator/health)
- Prometheus metrics: [/actuator/prometheus](http://localhost:8080/actuator/prometheus)

The application and database ports bind to loopback only. Defaults are `8080` and `5432`; override them if needed:

```sh
APP_PORT=18080 DB_PORT=15432 docker compose up --build -d --wait
```

The Compose database credentials are `minidoodle` / `minidoodle`, and the database is named `minidoodle`. These are local development defaults. Application configuration accepts `DB_URL`, `DB_USERNAME` and `DB_PASSWORD` when running outside Compose.

```sh
docker compose logs -f app
docker compose down
```

Database contents survive container restarts and `docker compose down` in the named `postgres-data` volume. **`docker compose down -v` deletes that data.**

## Scheduling rules

- Each user automatically receives one calendar. `Calendar` is an internal domain entity; public endpoints address users, slots and meetings.
- Slots have explicit `startsAt`, `endsAt` and `FREE` or `BUSY` status. Duration is determined by their boundaries. Slots belonging to the same user cannot overlap; adjacent slots are allowed.
- All intervals are half-open: `[startsAt, endsAt)`. A meeting ending at 11:00 does not conflict with one starting at 11:00.
- A meeting occupies its organizer's entire selected `FREE` slot. The organizer is included automatically, duplicate participant IDs are deduplicated, and every participant must have declared free time covering the whole meeting. Adjacent free slots can provide that coverage together.
- Booking creates reservations while preserving declared slots. Effective free time is declared `FREE` time minus active reservations. Effective busy time combines declared `BUSY` slots and active reservations.
- Undeclared time is **neither free nor busy** in the response. It cannot be booked. Consequently, `free` and `busy` need not partition the entire requested window.
- Changing or deleting a slot that overlaps an active reservation returns `409`. Cancel the meeting first. `PATCH` requires the complete slot body (`startsAt`, `endsAt`, `status`); it is not a JSON Merge Patch endpoint.
- Cancelling a meeting deletes its reservations, restores its underlying declared availability, and retains meeting details and participants with status `CANCELLED`. Repeated cancellation returns `204` without affecting later bookings. Deleting a formerly used source slot may make that cancelled meeting's `slotId` null.
- Personal availability merges adjacent intervals and clips results to the requested window. Shared availability intersects all users' effective free intervals and unions their busy intervals.

Timestamps must contain a UTC offset, for example `2030-01-15T10:00:00Z` or `2030-01-15T11:00:00+01:00`. Storage uses `Instant` / PostgreSQL `timestamptz`, and responses use UTC. User `timeZone` is a validated Java `ZoneId` (for example, `Europe/Berlin`) for client presentation; it does not change the meaning of explicit timestamps. Precision finer than microseconds is rejected to match PostgreSQL storage.

Limits: slots and query windows may cover at most **31 days**; a meeting may contain at most **50 participants including its organizer**; shared queries accept at most **50 user IDs**. Slot listing defaults to `limit=100`, allows up to `500`, and accepts `offset` from `0` to `100000`.

## API

All request bodies and successful resource responses use JSON. Create operations return `201` and a `Location` header. Deletion/cancellation returns `204`.

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/users` | Create a user and their internal calendar |
| `GET` | `/users/{userId}` | Get user details |
| `POST` | `/users/{userId}/slots` | Declare a free or busy slot |
| `GET` | `/users/{userId}/slots?from=...&to=...&limit=100&offset=0` | List overlapping declared slots, ordered by start and ID |
| `PATCH` | `/users/{userId}/slots/{slotId}` | Replace slot boundaries and status |
| `DELETE` | `/users/{userId}/slots/{slotId}` | Delete an unreserved slot |
| `POST` | `/meetings` | Book an organizer's slot for all participants |
| `GET` | `/meetings/{meetingId}` | Get meeting details, participants and status |
| `DELETE` | `/meetings/{meetingId}` | Cancel a meeting |
| `GET` | `/users/{userId}/availability?from=...&to=...` | Query effective personal availability |
| `GET` | `/availability?userIds=id1,id2&from=...&to=...` | Query shared free time and combined busy time |

`GET /users/{userId}/slots` returns complete stored intervals that overlap the window; availability endpoints return clipped intervals. Both availability endpoints return this shape:

```json
{
  "from": "2030-01-15T09:00:00Z",
  "to": "2030-01-15T12:00:00Z",
  "free": [
    {"startsAt": "2030-01-15T09:00:00Z", "endsAt": "2030-01-15T10:00:00Z"},
    {"startsAt": "2030-01-15T11:00:00Z", "endsAt": "2030-01-15T12:00:00Z"}
  ],
  "busy": [
    {"startsAt": "2030-01-15T10:00:00Z", "endsAt": "2030-01-15T11:00:00Z"}
  ]
}
```

Errors use `application/problem+json` with `status` and `detail`: `400` for invalid input, `404` for missing resources, and `409` for conflicting availability, duplicate email addresses or concurrent write conflicts. Unknown JSON fields are rejected. Participant IDs are required as an array; use `[]` for an organizer-only meeting.

## Try a complete flow

These commands require `curl` and Python 3 for extracting generated IDs. Run them in one Bash or Zsh session against the local Compose service. A unique email suffix lets the example be run again without resetting the database.

```sh
BASE=http://localhost:8080
RUN_ID=$(python3 -c 'import uuid; print(uuid.uuid4().hex[:8])')
json_id() { python3 -c 'import json, sys; print(json.load(sys.stdin)["id"])'; }

# 1. Create users and their calendars.
ALICE=$(curl -fsS "$BASE/users" -H 'Content-Type: application/json' \
  -d "{\"name\":\"Alice\",\"email\":\"alice-$RUN_ID@example.com\",\"timeZone\":\"Europe/Berlin\"}" | json_id)
BOB=$(curl -fsS "$BASE/users" -H 'Content-Type: application/json' \
  -d "{\"name\":\"Bob\",\"email\":\"bob-$RUN_ID@example.com\",\"timeZone\":\"America/New_York\"}" | json_id)

# 2. Alice is free 10:00–11:00 UTC; Bob is free 09:00–12:00 UTC.
ALICE_SLOT=$(curl -fsS "$BASE/users/$ALICE/slots" -H 'Content-Type: application/json' \
  -d '{"startsAt":"2030-01-15T10:00:00Z","endsAt":"2030-01-15T11:00:00Z","status":"FREE"}' | json_id)
BOB_SLOT=$(curl -fsS "$BASE/users/$BOB/slots" -H 'Content-Type: application/json' \
  -d '{"startsAt":"2030-01-15T09:00:00Z","endsAt":"2030-01-15T12:00:00Z","status":"FREE"}' | json_id)

# 3. Book Alice's complete slot. Alice is added to the participants automatically.
MEETING=$(curl -fsS "$BASE/meetings" -H 'Content-Type: application/json' \
  -d "{\"organizerId\":\"$ALICE\",\"slotId\":\"$ALICE_SLOT\",\"title\":\"Design review\",\"description\":\"Discuss the scheduling API\",\"participantIds\":[\"$BOB\"]}" | json_id)
curl -fsS "$BASE/meetings/$MEETING"

# 4. Bob has two free intervals (09:00–10:00 and 11:00–12:00), plus one busy interval.
curl -fsS --get "$BASE/users/$BOB/availability" \
  --data-urlencode 'from=2030-01-15T09:00:00Z' \
  --data-urlencode 'to=2030-01-15T12:00:00Z'

# Alice and Bob currently have no shared free time in this window.
curl -fsS --get "$BASE/availability" \
  --data-urlencode "userIds=$ALICE,$BOB" \
  --data-urlencode 'from=2030-01-15T09:00:00Z' \
  --data-urlencode 'to=2030-01-15T12:00:00Z'

# 5. Cancel the meeting. Its history remains available; Bob is free 09:00–12:00 again.
curl -fsS -X DELETE "$BASE/meetings/$MEETING"
curl -fsS "$BASE/meetings/$MEETING"
curl -fsS --get "$BASE/users/$BOB/availability" \
  --data-urlencode 'from=2030-01-15T09:00:00Z' \
  --data-urlencode 'to=2030-01-15T12:00:00Z'

# 6. With the reservation cancelled, Alice can mark her slot busy or remove it.
curl -fsS -X PATCH "$BASE/users/$ALICE/slots/$ALICE_SLOT" \
  -H 'Content-Type: application/json' \
  -d '{"startsAt":"2030-01-15T10:00:00Z","endsAt":"2030-01-15T11:00:00Z","status":"BUSY"}'
curl -fsS -X DELETE "$BASE/users/$ALICE/slots/$ALICE_SLOT"
```

## Design and persistence

The application follows a controller → transactional service → Spring Data JPA repository structure. Flyway owns the schema; Hibernate validates it on startup. Six tables separate declared availability from active reservations and retained meeting history:

| Table | Responsibility |
|---|---|
| `users` | Identity, unique normalized email and presentation time zone |
| `calendars` | One per user; also the lock target for scheduling writes |
| `time_slots` | Declared non-overlapping `FREE` / `BUSY` intervals |
| `meetings` | Title, description, organizer, source slot, time and cancellation status |
| `meeting_participants` | Membership retained after cancellation |
| `meeting_reservations` | Active occupied intervals, removed on cancellation |

Keeping reservations separate avoids splitting or rewriting the participant's longer availability slot when booking a shorter meeting. Cancelling simply releases reservations. Scalar foreign-key mappings keep database access explicit and prevent accidental lazy-loading queries; constraints in PostgreSQL enforce relationships.

### Concurrent writes

Every scheduling mutation first locks the affected calendar rows using a native `SELECT ... ORDER BY id FOR UPDATE`. Booking locks all participant calendars in that stable order, then checks coverage and conflicts, and saves the meeting, membership and reservations in one transaction. A failure rolls back the entire booking. Slot changes and cancellations use the same calendar lock protocol, so checking availability and modifying it cannot race for the same user. Unrelated calendars can be modified independently.

PostgreSQL exclusion constraints on `(calendar_id, tstzrange(starts_at, ends_at, '[)'))` reject overlapping slots and overlapping active reservations as a second line of protection. Cancellation obtains any meeting row lock after its calendar locks. The lock protocol works across application instances sharing the same database; it does not depend on in-memory synchronization.

### Query and write efficiency

Availability uses three bulk reads: calendars, overlapping declared slots and overlapping reservations. Query count does not grow with participant count. A repeatable-read transaction gives those reads one coherent snapshot. Meeting-detail reads use the same isolation level for metadata and membership.

Native PostgreSQL range predicates match the GiST indexes created by the exclusion constraints. Slot listing applies limit/offset in the database. Interval processing sorts and merges ranges, then subtracts reservations or intersects users' free intervals. No per-minute rows are generated. Queries and participant lists are bounded as described above.

New JPA entities use assigned-ID lifecycle tracking to avoid unnecessary `merge` lookups; Hibernate batches inserts with a batch size of 50. A single application and PostgreSQL are sufficient for the intended hundreds of users and thousands of slots. Very dense calendars still produce responses proportional to the number of resulting intervals; availability responses are not paginated.

## Tests and observability

Local test execution requires Java 21+ and Maven 3.9+:

```sh
# Fast interval unit tests; Docker is not required.
mvn test

# Unit and HTTP integration tests against an isolated PostgreSQL Testcontainer.
# A running Docker engine is required; unavailable Docker fails the suite.
mvn verify -Pintegration
```

The verified suite contains 7 interval unit tests and 16 HTTP integration tests (23 total). Integration tests cover validation, slot CRUD, half-open boundaries, participant coverage and gaps, atomic booking rejection, cancellation/history, shared availability, and concurrent booking, slot-change and cancellation scenarios. The suite uses real PostgreSQL so database constraints and locking behavior are exercised. The Docker image build skips tests; run the integration command separately before handing over changes.

Actuator exposes health/readiness/liveness probes and Prometheus-format HTTP, JVM and connection-pool metrics. No separate monitoring service is required to run the application.

## Reproducible availability measurement

The optional Python 3 script uses only the standard library. **Run it against a disposable database:** each run creates new uniquely named users and slots, retains them afterwards and can leave partial data if interrupted. It never deletes existing data.

For example, start a separate Compose project with its own volume and ports:

```sh
APP_PORT=18081 DB_PORT=15433 docker compose -p mini-doodle-bench up --build -d --wait
python3 scripts/benchmark.py --base-url http://localhost:18081 \
  --users 200 --slots-per-user 25 --requests 400 --workers 8
docker compose -p mini-doodle-bench down
```

These defaults seed 200 users and 5,000 half-hour slots, then issue 400 personal availability requests with eight concurrent workers. Every fifth slot is busy; the dataset contains no meetings. The JSON report includes seeding duration, successful/error counts, throughput and mean/p50/p95 successful request latency.

Measurements include HTTP/client overhead and have no separate warmup. Record the server hardware and Docker resource allocation alongside the report. This is a reproducible local query measurement, not a capacity guarantee or a benchmark of meeting writes and shared availability.

### Example local result

Verified on 2026-10-15 with the Compose application and PostgreSQL running on an Apple M1 host, Docker Desktop 20.10.21 allocated 4 CPUs and 7.67 GiB RAM. The Python 3.13.1 client ran on the host. The README walkthrough had already exercised the application; no additional warmup phase was used.

| Measurement | Result |
|---|---:|
| Newly seeded users / slots | 200 / 5,000 |
| Seeding time | 6.859 s |
| Availability requests / concurrent workers | 400 / 8 |
| Successful requests / errors | 400 / 0 |
| Mean / p50 / p95 latency | 10.945 / 8.675 / 27.511 ms |
| Measured throughput | 716.38 requests/s |

This short run measures personal availability reads over the two-day seeded window. It does not establish sustained production capacity, cold-start latency, or meeting-write throughput. Functional and concurrent booking correctness are covered separately by the PostgreSQL integration tests.

## Scope and tradeoffs

This is a trusted simulation: there is no authentication or authorization, and caller-supplied user IDs are not an access-control mechanism. It does not implement meeting rescheduling, recurrence, invitations/acceptance, notifications or external calendar synchronization. User time zones support presentation rather than recurring local-time rules. Creating meetings is not idempotent by request key; conflicting retries return `409`. Cancellation is idempotent.

The domain and implementation are specific to this challenge. The repository is maintained locally with meaningful commits; remote publication and sharing with reviewers are a separate handoff step.
