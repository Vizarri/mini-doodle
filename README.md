# Mini Doodle

A small meeting scheduling service built with Java 21, Spring Boot and PostgreSQL.

## Scope and assumptions

- Each user owns one internal calendar; calendars have no public management API.
- Users declare non-overlapping free or busy slots. Adjacent slots are valid.
- A meeting occupies the organizer's entire selected free slot. Every participant, including the organizer, must have a free slot covering the meeting interval.
- Meeting reservations preserve declared slots. Effective availability subtracts active reservations from free slots; undeclared time is unavailable.
- All intervals are half-open: `[start, end)`. API timestamps require an explicit offset and are stored as instants.
- Booking is atomic across participants. Calendar locks serialize conflicting writes; unrelated calendars can be updated independently.
- This trusted local simulation has no authentication, invitations, recurrence, notifications or external calendar integration.

## Implementation sequence

1. Bootstrap the application, database migrations and Docker Compose.
2. Add users, internal calendars and persisted time slots.
3. Implement transactional meeting booking and cancellation.
4. Add effective availability queries.
5. Verify concurrency and API behavior with PostgreSQL integration tests.
6. Document API examples, operational endpoints and design tradeoffs.

This service has its own domain model and repository history.

Local run instructions and complete API examples will be added alongside the implementation.
