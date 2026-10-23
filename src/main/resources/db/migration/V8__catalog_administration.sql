-- Additive administration metadata; existing bookings and catalogue rows are preserved.
ALTER TABLE rooms ADD COLUMN version bigint NOT NULL DEFAULT 0;
CREATE TABLE local_environment_state (
    environment_id varchar(100) PRIMARY KEY,
    initialized_at timestamptz NOT NULL DEFAULT now()
);
