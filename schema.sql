-- PostgreSQL schema for Cultural Management Platform (MVP)
-- Identifiers are automatically generated sequential integers.

BEGIN;

-- Enum types
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'role_enum') THEN
        CREATE TYPE role_enum AS ENUM ('ROLE_ADMIN', 'ROLE_OPERATOR', 'ROLE_USER');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'reservation_status') THEN
        CREATE TYPE reservation_status AS ENUM ('PENDING', 'CONFIRMED', 'CANCELLED');
    END IF;
END$$;

-- Users
CREATE TABLE IF NOT EXISTS users (
    id INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username VARCHAR(100) NOT NULL UNIQUE,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role role_enum NOT NULL DEFAULT 'ROLE_USER',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Catalog items
CREATE TABLE IF NOT EXISTS catalog_items (
    id INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title VARCHAR(512) NOT NULL,
    description TEXT,
    author VARCHAR(255),
    year INTEGER,
    media_url TEXT,
    category VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Events
CREATE TABLE IF NOT EXISTS events (
    id INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title VARCHAR(512) NOT NULL,
    description TEXT,
    start_datetime TIMESTAMPTZ NOT NULL,
    end_datetime TIMESTAMPTZ,
    capacity INTEGER NOT NULL CHECK (capacity >= 0),
    price NUMERIC(10,2) NOT NULL DEFAULT 0.00,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT events_price_nonnegative CHECK (price >= 0),
    CONSTRAINT events_valid_date_range
        CHECK (end_datetime IS NULL OR end_datetime >= start_datetime)
);

-- Apply event constraints and defaults to existing tables as well.
UPDATE events
SET price = 0
WHERE price IS NULL;

ALTER TABLE events
    ALTER COLUMN price SET DEFAULT 0.00,
    ALTER COLUMN price SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'events_price_nonnegative'
          AND conrelid = 'events'::regclass
    ) THEN
        ALTER TABLE events
            ADD CONSTRAINT events_price_nonnegative CHECK (price >= 0);
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'events_valid_date_range'
          AND conrelid = 'events'::regclass
    ) THEN
        ALTER TABLE events
            ADD CONSTRAINT events_valid_date_range
            CHECK (end_datetime IS NULL OR end_datetime >= start_datetime);
    END IF;
END $$;

-- Reservations
CREATE TABLE IF NOT EXISTS reservations (
    id INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    event_id INTEGER NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    seats INTEGER NOT NULL CHECK (seats > 0),
    status reservation_status NOT NULL DEFAULT 'CONFIRMED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Allow a new reservation after cancellation, while keeping one active reservation per user/event.
ALTER TABLE reservations
    DROP CONSTRAINT IF EXISTS one_active_reservation_per_user_event;

CREATE UNIQUE INDEX IF NOT EXISTS idx_reservations_one_active_per_user_event
    ON reservations (user_id, event_id)
    WHERE status IN ('PENDING', 'CONFIRMED');

-- Feedbacks
CREATE TABLE IF NOT EXISTS feedbacks (
    id INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id INTEGER REFERENCES users(id) ON DELETE SET NULL,
    item_id INTEGER REFERENCES catalog_items(id) ON DELETE SET NULL,
    event_id INTEGER REFERENCES events(id) ON DELETE SET NULL,
    rating INTEGER CHECK (rating >= 1 AND rating <= 5),
    comment TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Apply the nullable user reference to existing tables as well.
ALTER TABLE feedbacks
    ALTER COLUMN user_id DROP NOT NULL;

-- Indexes
CREATE INDEX IF NOT EXISTS idx_events_start ON events(start_datetime);
CREATE INDEX IF NOT EXISTS idx_catalog_title ON catalog_items USING gin (to_tsvector('english', title || ' ' || coalesce(description,'')));

-- Note on reservations capacity enforcement:
-- The application must enforce capacity atomically. Two concrete approaches:
-- 1) SELECT capacity FROM events WHERE id = :id FOR UPDATE; then sum PENDING/CONFIRMED seats, check capacity and insert within the same transaction.
-- 2) Keep an available_seats column on events and update it with "UPDATE events SET available_seats = available_seats - :seats WHERE id = :id AND available_seats >= :seats" returning rows affected = 1 means success.

-- Example seed (optional):
-- INSERT INTO users (username, email, password_hash, role) VALUES ('admin', 'admin@example.com', '<bcrypt-hash>', 'ROLE_ADMIN');

COMMIT;
