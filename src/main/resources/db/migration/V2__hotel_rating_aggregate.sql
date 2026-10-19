ALTER TABLE hotels ADD COLUMN rating_sum numeric(19,4) NOT NULL DEFAULT 0;
ALTER TABLE hotels ADD COLUMN version bigint NOT NULL DEFAULT 0;

-- Existing averages have already been rounded; preserve the available historical aggregate.
UPDATE hotels SET rating_sum = round((coalesce(rating, 0) * count_of_ratings)::numeric, 4);
