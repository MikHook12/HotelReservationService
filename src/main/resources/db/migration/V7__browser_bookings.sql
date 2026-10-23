-- Additive migration. No price, timezone, or guest count is invented for history.
ALTER TABLE bookings ADD COLUMN status varchar(20) NOT NULL DEFAULT 'CONFIRMED';
ALTER TABLE bookings ADD COLUMN origin varchar(20) NOT NULL DEFAULT 'LEGACY';
ALTER TABLE bookings ADD COLUMN guests integer;
ALTER TABLE bookings ADD COLUMN nightly_price numeric(12,2);
ALTER TABLE bookings ADD COLUMN total_price numeric(14,2);
ALTER TABLE bookings ADD COLUMN currency varchar(3);
ALTER TABLE bookings ADD COLUMN nights integer;
ALTER TABLE bookings ADD COLUMN snapshot_json text;
ALTER TABLE bookings ADD COLUMN time_zone varchar(100);
ALTER TABLE bookings ADD COLUMN cancellation_deadline_at timestamptz;
ALTER TABLE bookings ADD COLUMN created_at timestamptz;
ALTER TABLE bookings ADD COLUMN cancelled_at timestamptz;
ALTER TABLE bookings ADD COLUMN timezone_provenance varchar(40);
ALTER TABLE bookings ADD CONSTRAINT bookings_status CHECK(status IN ('CONFIRMED','CANCELLED'));
ALTER TABLE bookings ADD CONSTRAINT bookings_origin CHECK(origin IN ('LEGACY','V2'));
ALTER TABLE bookings ADD CONSTRAINT bookings_cancellation CHECK((status = 'CONFIRMED' AND cancelled_at IS NULL) OR (status = 'CANCELLED' AND cancelled_at IS NOT NULL));
ALTER TABLE bookings ADD CONSTRAINT bookings_v2_snapshot CHECK(origin <> 'V2' OR
    (guests IS NOT NULL AND guests BETWEEN 1 AND 100 AND nightly_price IS NOT NULL AND nightly_price > 0
     AND total_price IS NOT NULL AND total_price = nightly_price * nights AND currency IS NOT NULL AND currency = 'RUB'
     AND nights IS NOT NULL AND nights BETWEEN 1 AND 30 AND nights = check_out_date - check_in_date
     AND snapshot_json IS NOT NULL AND time_zone IS NOT NULL AND cancellation_deadline_at IS NOT NULL
     AND created_at IS NOT NULL AND timezone_provenance IS NOT NULL AND timezone_provenance = 'CAPTURED_AT_CREATION'));
CREATE INDEX idx_bookings_active_dates ON bookings(room_id,check_in_date,check_out_date) WHERE status='CONFIRMED';
-- Existing overlaps must be reviewed before migration; do not delete history to bypass this.
CREATE EXTENSION IF NOT EXISTS btree_gist;
ALTER TABLE bookings ADD CONSTRAINT bookings_no_active_overlap EXCLUDE USING gist
    (room_id WITH =, daterange(check_in_date,check_out_date,'[)') WITH &&)
    WHERE (status='CONFIRMED' AND check_in_date < check_out_date);

CREATE TABLE browser_booking_quotes (
    user_id bigint NOT NULL REFERENCES users(id),
    request_key uuid NOT NULL,
    payload_hash varchar(64) NOT NULL,
    token_hash varchar(64) UNIQUE,
    quote_cipher text,
    error_cipher text,
    error_status integer,
    error_code varchar(80),
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    retain_until timestamptz NOT NULL,
    PRIMARY KEY(user_id,request_key)
);
CREATE INDEX idx_browser_quotes_retention ON browser_booking_quotes(retain_until);
CREATE TABLE browser_booking_operations (
    user_id bigint NOT NULL REFERENCES users(id),
    request_key uuid NOT NULL,
    payload_hash varchar(64),
    state varchar(20) NOT NULL CHECK(state IN ('PROCESSING','SUCCEEDED','FAILED')),
    attempt_token uuid NOT NULL,
    lease_until timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    completed_at timestamptz,
    booking_id bigint REFERENCES bookings(id),
    receipt_json text,
    error_cipher text,
    error_status integer,
    error_code varchar(80),
    PRIMARY KEY(user_id,request_key),
    CONSTRAINT browser_operation_outcome CHECK
      ((state='PROCESSING' AND completed_at IS NULL AND booking_id IS NULL AND receipt_json IS NULL)
       OR (state='SUCCEEDED' AND completed_at IS NOT NULL AND booking_id IS NOT NULL AND receipt_json IS NOT NULL)
       OR (state='FAILED' AND completed_at IS NOT NULL AND booking_id IS NULL AND receipt_json IS NULL AND error_status IS NOT NULL AND error_code IS NOT NULL))
);
CREATE INDEX idx_browser_operations_recovery ON browser_booking_operations(lease_until) WHERE state='PROCESSING';
