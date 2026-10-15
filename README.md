# Mini Doodle

A meeting scheduling service built with Java 21, Spring Boot 3.5.3, Spring Data JPA and PostgreSQL 17.

## Run locally

Docker and Docker Compose v2 are the only runtime prerequisites:

```sh
docker compose up --build -d --wait
```

The multi-stage build includes Java and Maven. Compose starts the application and PostgreSQL, waits for health checks and applies Flyway migrations.

- [Swagger UI](http://localhost:8080/swagger-ui/index.html): explore and execute every request.
- [OpenAPI schema](http://localhost:8080/v3/api-docs).
- [Health](http://localhost:8080/actuator/health), [Prometheus metrics](http://localhost:8080/actuator/prometheus).

Ports bind to loopback. Override the defaults if another service uses them:

```sh
APP_PORT=18080 DB_PORT=15432 docker compose up --build -d --wait
```

The local database, username and password are `minidoodle`. When running outside Compose, set `DB_URL`, `DB_USERNAME` and `DB_PASSWORD` as needed.

```sh
docker compose logs -f app
docker compose down
```

The named PostgreSQL volume survives `down` and restarts. `docker compose down -v` also deletes its data.

## Project organization

The project separates HTTP contracts, application logic and persistence into layers:

```text
com.src.minidoodle
├── config                  OpenAPI and JSON configuration
├── constant                API paths, request limits and documentation constants
├── controller              HTTP binding and documented endpoint contracts
├── db
│   ├── model               JPA entities, lazy relationships and entity lifecycle
│   └── repository          Spring Data repositories and targeted native SQL
├── dto
│   ├── request             Jakarta-validated request records
│   └── response            Explicit response records and ApiError
├── enums                   Domain statuses and stable API error codes
├── exception               ApiException and GlobalExceptionHandler
├── service
│   ├── availability        Effective availability queries
│   ├── meeting             Booking, details and cancellation
│   ├── slot                Declared slot management
│   ├── user                User and calendar creation
│   └── system
│       └── validation      Reusable validation and normalization
└── util                    Half-open interval operations
```

Controllers depend on services and standalone DTOs. Transactional services coordinate use cases; validation services normalize input and enforce reusable rules. DTO conversion remains in private service methods. Entities use Lombok accessors, lazy associations and `@PrePersist` UUID/timestamp initialization. Flyway owns `db/migration/schema`; Hibernate validates the schema and Open Session in View is disabled.

## Scheduling rules

- Every user owns one internal calendar, created with the user. There is no public calendar management endpoint.
- Users declare `FREE` or `BUSY` slots with explicit boundaries. Duration is configurable through those boundaries. Slots in one calendar cannot overlap; adjacent slots are allowed.
- Intervals are half-open: `[startsAt, endsAt)`. A meeting ending at 11:00 does not conflict with one starting at 11:00.
- A meeting occupies the organizer's entire selected `FREE` slot. The organizer is included automatically. Other participants need declared free time covering the whole interval; adjacent free slots can provide that coverage together.
- Booking adds reservations without rewriting declared slots. Effective free time is declared `FREE` time minus active reservations. Effective busy time combines declared `BUSY` slots with active reservations.
- Undeclared time is unavailable: it is neither `free` nor `busy` in the response and cannot be booked. The two lists do not necessarily partition the requested window.
- A slot covering an active reservation cannot be changed or deleted. Cancel the meeting first. Slot `PATCH` accepts the complete body, not a JSON Merge Patch document.
- Cancellation releases reservations but retains meeting details and participants. Repeated cancellation is harmless, including after another meeting has booked the released time. Deleting a cancelled meeting's former source slot makes its `slotId` null.
- Personal availability clips and merges intervals. Shared availability intersects free time and unions busy time across the selected users.

Use ISO 8601 timestamps with an explicit offset, such as `2030-01-15T10:00:00Z` or `2030-01-15T11:00:00+01:00`. Numeric epochs and offset-free timestamps are rejected. Storage uses `Instant` / `timestamptz`; responses use UTC. Precision finer than microseconds is rejected. A user's validated `ZoneId` is for presentation and does not alter explicit timestamps.

Slots and query windows are limited to 31 days. Meetings support 50 participants including the organizer; shared queries accept 50 user IDs. Slot listing defaults to 100 items, permits 500, and accepts offsets from 0 to 100000.

## API

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/users` | Create a user and internal calendar |
| `GET` | `/users/{userId}` | Read a user |
| `POST` | `/users/{userId}/slots` | Declare a slot |
| `GET` | `/users/{userId}/slots?from=...&to=...&limit=100&offset=0` | List overlapping declared slots |
| `PATCH` | `/users/{userId}/slots/{slotId}` | Replace boundaries and state |
| `DELETE` | `/users/{userId}/slots/{slotId}` | Delete an unreserved slot |
| `POST` | `/meetings` | Book the source slot for all participants |
| `GET` | `/meetings/{meetingId}` | Read details and participants |
| `DELETE` | `/meetings/{meetingId}` | Cancel a meeting |
| `GET` | `/users/{userId}/availability?from=...&to=...` | Read effective availability |
| `GET` | `/availability?userIds=id1,id2&from=...&to=...` | Read shared free and combined busy time |

Successful creation returns `201` and `Location`; deletion/cancellation returns `204`. Slot listing returns complete stored intervals; availability results are clipped to the query window. Unknown JSON fields are rejected.

Errors have a stable contract and use `application/json`:

```json
{
  "status": 409,
  "code": "SLOT_OVERLAP",
  "message": "Slots in the same calendar cannot overlap",
  "details": {"path": "/users/00000000-0000-0000-0000-000000000001/slots"}
}
```

HTTP `400` covers malformed/invalid input, `404` missing resources, and `409` scheduling conflicts. Clients should branch on `code`, not message text. Shape validation uses `VALIDATION_FAILED` with `details.fields`; domain errors distinguish `SLOT_RESERVED`, `MEETING_CONFLICT`, `AVAILABILITY_REQUIRED` and other causes. Unexpected database errors are not presented as normal scheduling conflicts.

### Example flow

Swagger UI provides an executable version of these contracts. For `curl`, copy the returned `id` values into the indicated variables; no additional scripting runtime is needed.

```sh
BASE=http://localhost:8080
curl -i "$BASE/users" -H 'Content-Type: application/json' \
  -d '{"name":"Alice","email":"alice@example.com","timeZone":"Europe/Berlin"}'

# Replace this value with the id from the response.
USER_ID='paste-user-id-here'
curl -i "$BASE/users/$USER_ID/slots" -H 'Content-Type: application/json' \
  -d '{"startsAt":"2030-01-15T10:00:00Z","endsAt":"2030-01-15T11:00:00Z","status":"FREE"}'

# Replace this value with the returned slot id.
SLOT_ID='paste-slot-id-here'
curl -i "$BASE/meetings" -H 'Content-Type: application/json' \
  -d "{\"organizerId\":\"$USER_ID\",\"slotId\":\"$SLOT_ID\",\"title\":\"Design review\",\"description\":\"Scheduling API\",\"participantIds\":[]}"

curl -sS --get "$BASE/users/$USER_ID/availability" \
  --data-urlencode 'from=2030-01-15T09:00:00Z' \
  --data-urlencode 'to=2030-01-15T12:00:00Z'

MEETING_ID='paste-meeting-id-here'
curl -i -X DELETE "$BASE/meetings/$MEETING_ID"
```

The availability response before cancellation has no free intervals and one busy interval from 10:00 to 11:00. Afterwards that interval is free. For a multi-user meeting, create another user with covering free time and add their ID to `participantIds`. Their longer slot is preserved; only the meeting interval becomes effectively busy.

## Persistence and concurrency

Six tables separate user identity, declared availability and meeting history: `users`, `calendars`, `time_slots`, `meetings`, `meeting_participants` and `meeting_reservations`.

Every scheduling mutation first obtains the affected calendar locks in database UUID order. The native query uses `FOR NO KEY UPDATE`: writers serialize while child foreign-key checks do not require a conflicting key lock. Mutable slot and meeting state is read after obtaining the locks. Booking validates all participants and inserts all reservations in one transaction. Cancellation obtains its meeting-row lock after its calendar locks. This works across multiple application instances sharing PostgreSQL.

Exclusion constraints on `(calendar_id, tstzrange(starts_at, ends_at, '[)'))` reject overlapping slots and reservations. Native overlap queries use the same range expression, allowing PostgreSQL to use the GiST indexes. List pagination is applied in the database.

Availability performs three bulk reads: calendars, slots and reservations. A repeatable-read transaction gives those reads one coherent snapshot. Reading entity IDs through lazy associations avoids fetching unrelated user/entity graphs. Interval processing merges ranges and subtracts or intersects them; it does not generate per-minute rows.

New UUID entities use their lifecycle callbacks. Known-new composite participation/reservation rows use targeted native repository inserts, avoiding per-row merge existence checks. Neither an additional cache nor a message broker is required.

## Tests

Install Java 21+, Maven 3.9+ and run Docker:

```sh
mvn verify
```

The normal Maven lifecycle runs unit and PostgreSQL integration tests. Integration tests do not silently pass or skip when Docker is unavailable. They apply the actual Flyway migration, reset business tables between tests and use shared fixture helpers.

For a quick interval-only run without Docker:

```sh
mvn -Dtest=TimeIntervalUtilsTest test
```

The integration suite covers:

- HTTP contracts through MockMvc: DTO validation, stable error codes, slot CRUD, booking, cancellation and availability.
- Transactional concurrency with actual PostgreSQL lock contention, including conflicts between bookings, slot changes and cancellation.
- A fixture of 200 users and 5,000 slots: exact personal/shared availability and a bounded SQL query count for one and 50 users.
- Booking, availability and cancellation with a connection pool of size one.
- OpenAPI paths, status codes, content types and schemas. The test writes the generated contract to `target/openapi/openapi.json`.

Scale tests assert correctness and database access bounds, not machine-dependent latency thresholds. They are part of the Java test suite and require no external benchmark script. The Docker image build skips tests; `mvn verify` is the verification command before handoff.

## Observability and scope

Actuator exposes health/readiness/liveness and Prometheus-format HTTP, JVM and connection-pool metrics.

This is a trusted simulation without authentication/authorization. It omits recurring schedules, rescheduling, invitations/acceptance, notifications and external calendar integrations. Creation has no idempotency key: a conflicting retry returns `409`; cancellation is idempotent. Availability output size follows the resulting intervals within the bounded query window and is not paginated.

The project uses layered validation/error contracts, JPA practices, coordination locks and integration tests. Its scheduling domain remains specific to this challenge. The repository is local until a remote is configured for sharing with reviewers.
