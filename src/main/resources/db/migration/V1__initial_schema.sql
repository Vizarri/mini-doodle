CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE users (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(254) NOT NULL,
    time_zone VARCHAR(100) NOT NULL,
    CONSTRAINT users_email_unique UNIQUE (email)
);

CREATE TABLE calendars (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id),
    CONSTRAINT calendars_user_unique UNIQUE (user_id)
);

CREATE TABLE time_slots (
    id UUID PRIMARY KEY,
    calendar_id UUID NOT NULL REFERENCES calendars (id),
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(4) NOT NULL,
    CONSTRAINT time_slots_positive_duration CHECK (starts_at < ends_at),
    CONSTRAINT time_slots_valid_status CHECK (status IN ('FREE', 'BUSY')),
    CONSTRAINT time_slots_no_overlap EXCLUDE USING gist (
        calendar_id WITH =,
        tstzrange(starts_at, ends_at, '[)') WITH &&
    )
);

CREATE TABLE meetings (
    id UUID PRIMARY KEY,
    organizer_user_id UUID NOT NULL REFERENCES users (id),
    source_slot_id UUID REFERENCES time_slots (id) ON DELETE SET NULL,
    title VARCHAR(200) NOT NULL,
    description VARCHAR(4000),
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    cancelled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT meetings_positive_duration CHECK (starts_at < ends_at)
);

CREATE INDEX meetings_organizer_idx ON meetings (organizer_user_id);
CREATE INDEX meetings_source_slot_idx ON meetings (source_slot_id);

CREATE TABLE meeting_participants (
    meeting_id UUID NOT NULL REFERENCES meetings (id),
    user_id UUID NOT NULL REFERENCES users (id),
    PRIMARY KEY (meeting_id, user_id)
);

CREATE INDEX meeting_participants_user_idx ON meeting_participants (user_id);

CREATE TABLE meeting_reservations (
    meeting_id UUID NOT NULL REFERENCES meetings (id),
    calendar_id UUID NOT NULL REFERENCES calendars (id),
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (meeting_id, calendar_id),
    CONSTRAINT meeting_reservations_positive_duration CHECK (starts_at < ends_at),
    CONSTRAINT meeting_reservations_no_overlap EXCLUDE USING gist (
        calendar_id WITH =,
        tstzrange(starts_at, ends_at, '[)') WITH &&
    )
);
