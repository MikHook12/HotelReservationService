-- Match the service's hotel-name uniqueness rule, including concurrent requests.
-- Existing duplicate hotel names must be resolved explicitly before migration.
CREATE UNIQUE INDEX IF NOT EXISTS ux_hotels_name ON hotels (name);

-- NOT VALID preserves existing legacy rows for review while enforcing invariants
-- for every new/updated row. No historic data is rewritten or deleted.
ALTER TABLE bookings ADD CONSTRAINT bookings_valid_dates
    CHECK (check_in_date < check_out_date) NOT VALID;
ALTER TABLE rooms ADD CONSTRAINT rooms_positive_values
    CHECK (number > 0 AND price > 0 AND room_capacity > 0) NOT VALID;
ALTER TABLE hotels ADD CONSTRAINT hotels_valid_distance_and_count
    CHECK (distance_from_city_center >= 0 AND count_of_ratings >= 0) NOT VALID;
