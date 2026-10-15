# Mini Doodle

A meeting scheduling service built with Java 21, Spring Boot 3.5.3, Spring Data JPA and PostgreSQL 17.

## Run locally

Docker and Docker Compose v2 are the only runtime prerequisites:

```sh
docker compose up --build -d --wait
```

The multi-stage build includes Java and Maven. Compose starts the application and PostgreSQL, waits for health checks and applies Flyway migrations. The first build downloads base images and Maven dependencies and can take several minutes; subsequent builds reuse Docker layers when their inputs have not changed.

- [Swagger UI](http://localhost:8080/swagger-ui/index.html): explore and execute every request.
- [OpenAPI schema](http://localhost:8080/v3/api-docs).
- [Health](http://localhost:8080/actuator/health), [Prometheus metrics](http://localhost:8080/actuator/prometheus).

The default host ports are `8080` for HTTP and `5432` for PostgreSQL, both bound to loopback. Override them if another service uses those ports:

```sh
APP_PORT=18080 DB_PORT=15432 docker compose up --build -d --wait
```

To persist overrides, create an optional `.env` file in the project root; it is excluded from Git:

```dotenv
APP_PORT=8080
DB_PORT=15432
DB_LOCK_TIMEOUT=5s
```

Compose loads `.env` automatically; variables exported by your shell take precedence. `DB_PORT` changes the published host port; the application inside Compose still connects to `db:5432`. If you change `APP_PORT`, use that port in the links above and the example's `BASE` URL.

The local database, username and password are `minidoodle`. When running the application outside Compose, set `DB_URL`, `DB_USERNAME` and `DB_PASSWORD` in the application environment as needed. For a Compose database published on port `15432`, use `DB_URL=jdbc:postgresql://localhost:15432/minidoodle`. Compose's `.env` is not automatically loaded by Spring Boot when it runs directly from Maven or an IDE.

Database lock acquisition waits are limited to `5s` by default. Override `DB_LOCK_TIMEOUT`, for example `DB_LOCK_TIMEOUT=2s docker compose up --build -d --wait`. The application initializes PostgreSQL `lock_timeout` on every pooled connection. This is separate from the Hikari timeout for obtaining a connection from the pool.

```sh
docker compose logs -f app
docker compose down
```

The named PostgreSQL volume survives `down` and restarts. `docker compose down -v` also deletes its data.

## Project organization

HTTP contracts, application logic and persistence have separate layers:

```text
com.src.minidoodle
├── config                  OpenAPI metadata
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
│   └── system              Ordered calendar locks and shared validation
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
- A slot overlapping an active reservation cannot be changed or deleted. Cancel the meeting first. Slot `PATCH` accepts the complete body, not a JSON Merge Patch document.
- Cancellation releases reservations but retains meeting details and participants. Repeated cancellation is harmless, including after another meeting has booked the released time. Deleting a cancelled meeting's former source slot makes its `slotId` null.
- Personal availability clips and merges intervals. Shared availability intersects free time and unions busy time across the selected users.

Use ISO 8601 timestamps with an explicit offset, such as `2030-01-15T10:00:00Z` or `2030-01-15T11:00:00+01:00`. Numeric epochs and offset-free timestamps are rejected. Storage uses `Instant` / `timestamptz`; responses use UTC. After offset normalization, both boundaries must satisfy `0001-01-01T00:00:00Z <= timestamp < +10000-01-01T00:00:00Z`. Precision finer than microseconds is rejected. A user's validated `ZoneId` is for presentation and does not alter explicit timestamps.

Slot status accepts only the JSON strings `"FREE"` and `"BUSY"`. Numeric enum values, different casing and unknown strings are rejected.

Slots and query windows are limited to 31 days. Meetings support 50 distinct participants including the organizer. The organizer is added automatically and duplicate participant IDs are ignored, but the submitted `participantIds` array is also limited to 50 entries before deduplication. Shared queries accept between 1 and 50 submitted user IDs, with duplicates ignored after the input-count check. Slot listing defaults to 100 items, permits 500, and accepts offsets from 0 to 100000.

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

Errors have stable machine-readable codes and use `application/json`:

```json
{
  "status": 409,
  "code": "SLOT_OVERLAP",
  "message": "Slots in the same calendar cannot overlap",
  "details": {"path": "/users/00000000-0000-0000-0000-000000000001/slots"}
}
```

HTTP `400` covers malformed/invalid input, `404` missing resources, and `409` scheduling conflicts. Invalid timestamp syntax or missing offsets use `INVALID_TIME_RANGE` in slot request bodies and `BAD_REQUEST` in query parameters. After parsing, invalid boundaries or precision use `INVALID_TIME_RANGE`; durations over 31 days use `TIME_RANGE_TOO_LARGE`. Malformed JSON and invalid enum values use `MALFORMED_JSON`. Clients should branch on `code`, not message text. Shape validation uses `VALIDATION_FAILED` with `details.fields`; domain errors distinguish `SLOT_RESERVED`, `MEETING_CONFLICT`, `AVAILABILITY_REQUIRED` and other causes. Unexpected database errors are not presented as normal scheduling conflicts.

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

Six tables separate user identity, declared availability and meeting history: `users`, `calendars`, `time_slots`, `meetings`, `meeting_participants` and `meeting_reservations`. Spring Data JPA handles ordinary entity persistence; native queries provide PostgreSQL range operators and precise row-lock behavior.

Separating declared slots from reservations preserves the original availability when a meeting occupies only part of a participant's slot. Cancellation removes reservations without reconstructing slots. The tradeoff is computing effective free and busy intervals when reading availability.

Every scheduling mutation first obtains the affected calendar locks in database UUID order. The native query uses `FOR NO KEY UPDATE`: writes to the same calendar serialize, while child foreign-key checks remain compatible and unrelated calendars can proceed independently. Mutable slot and meeting state is refreshed after obtaining the locks, so a joined transaction cannot reuse stale managed entities. Participant coverage uses a single native scalar projection to read current values without fetching each slot separately. Booking validates all participants and inserts all reservations in one transaction. Cancellation uses the same participant calendar locks to serialize repeated requests and preserve newer reservations. This works across multiple application instances sharing PostgreSQL.

A lock wait exceeding `DB_LOCK_TIMEOUT` aborts the statement and rolls back the request transaction. The API returns `409 CONCURRENT_MODIFICATION`; the client can retry the complete request. PostgreSQL applies the limit to each lock acquisition attempt, rather than to the total request duration.

Exclusion constraints on `(calendar_id, tstzrange(starts_at, ends_at, '[)'))` reject overlapping slots and reservations. Native overlap queries use the same range expression, allowing PostgreSQL to use the GiST indexes. List pagination is applied in the database.

Availability performs three bulk reads: calendars, slots and reservations. A repeatable-read transaction gives those reads one coherent snapshot. Reading entity IDs through lazy associations avoids fetching unrelated user/entity graphs. Interval processing merges ranges and subtracts or intersects them; it does not generate per-minute rows.

New UUID entities use their lifecycle callbacks. Known-new composite participation/reservation rows use targeted native repository inserts, avoiding per-row merge existence checks.

## Tests

Install Java 21, Maven 3.9+ and run Docker:

```sh
mvn clean verify
```

Test packages mirror application packages under `unit` and `integration`, for example `unit.util`, `integration.controller` and `integration.service.meeting`. Shared PostgreSQL and separate-process fixtures remain in `integration.AbstractIntegrationTest` and `integration.AbstractMultiInstanceIntegrationTest`.

The normal Maven lifecycle runs unit and PostgreSQL integration tests and generates a JaCoCo coverage report at `target/site/jacoco/index.html` (XML: `target/site/jacoco/jacoco.xml`). Integration tests do not silently pass or skip when Docker is unavailable. They apply the actual Flyway migration, reset business tables between tests and use shared fixture helpers.

For the complete unit suite without Docker:

```sh
mvn -DexcludedGroups=integration test
```

Unit tests cover interval operations and invariants, normalization, request limits, availability guards, calendar lock inputs and error mapping. They use repository mocks only where the validation rule depends on persisted data.

The integration suite covers:

- HTTP contracts through MockMvc: DTO validation and exact input limits, error envelopes for binding and protocol failures, timestamp/status boundaries, slot CRUD, booking, cancellation and availability.
- Transactional concurrency with actual PostgreSQL lock contention, including both orderings of booking versus participant slot changes, competing bookings and cancellation.
- Separate application JVMs sharing PostgreSQL: real HTTP requests across both instances verify cross-process booking and cancellation.
- Session lock timeout configuration, a real blocked HTTP request, the conflict response, rollback and a successful retry after lock release.
- Booking and cancellation with 50 participants, preserving declared slots and meeting history.
- Ownership checks, participant deduplication, persisted pagination and rollback after actual SQL failures during user/calendar and meeting/reservation creation.
- PostgreSQL exclusion constraints tested directly through repositories, bypassing service guards: overlap is rejected; adjacency and independent calendars are allowed.
- Calendar locking through the Spring proxy: an existing transaction is required and locks follow PostgreSQL UUID ordering.
- Existing persistence contexts containing stale slots and meetings: committed changes and deletions must be observed before booking, slot mutation or repeated cancellation.
- A fixture of 200 users and 5,000 slots: exact personal/shared availability and a bounded SQL query count for one and 50 users.
- Booking, availability and cancellation with a connection pool of size one.
- OpenAPI paths, status codes, content types and schemas. The test writes the generated contract to `target/openapi/openapi.json`.

The multi-instance tests start two separate Java processes from the compiled test/runtime classpath, each with its own HTTP port and connection pool, against the same Testcontainers PostgreSQL. They stop both processes after each test class; their logs remain under `target/multi-instance/` for diagnosis. No previously running application or packaged JAR is required.

The load test uses 200 users and 5,000 declared slots, 16 concurrent workers and 835 HTTP requests across both processes. Its final contention phase submits 32 bookings for the same time and requires exactly one success with 31 meeting conflicts. It exercises independent bookings, meeting and availability reads, cancellation and repeated cancellation, followed by competing requests for the same time. Assertions check response codes, membership, reservations, restored availability and the absence of overlapping reservations. Measurements are written to `target/load-test-results.json`.

Run these checks separately with Docker running:

```sh
mvn -Dtest=MeetingMultiInstanceIntegrationTest,MeetingLoadIntegrationTest,CalendarLockTimeoutIntegrationTest test
```

Scale and load tests assert correctness and completion within generous test deadlines. Throughput and latency measurements describe the local test run; they are not hardware-independent capacity or SLA guarantees. The tests are part of the Java suite and require no external benchmark script. The Docker image build skips tests; `mvn verify` runs the complete verification suite.

## Observability and scope

Actuator exposes health/readiness/liveness and Prometheus-format HTTP, JVM and connection-pool metrics.

This is a trusted simulation without authentication/authorization. It omits recurring schedules, rescheduling, invitations/acceptance, notifications and external calendar integrations. Creation has no idempotency key: a conflicting retry returns `409`; cancellation is idempotent. Availability output size follows the resulting intervals within the bounded query window and is not paginated.
