-- Additive only: legacy metadata stays unknown and legacy objects stay unpublished.
ALTER TABLE hotels ADD COLUMN slug varchar(100);
ALTER TABLE hotels ADD COLUMN time_zone varchar(64);
ALTER TABLE hotels ADD COLUMN city_code varchar(64);
ALTER TABLE hotels ADD COLUMN published boolean NOT NULL DEFAULT false;
ALTER TABLE hotels ADD COLUMN demo boolean NOT NULL DEFAULT false;
ALTER TABLE hotels ADD COLUMN description text;
ALTER TABLE hotels ADD COLUMN latitude numeric(9,6);
ALTER TABLE hotels ADD COLUMN longitude numeric(9,6);
ALTER TABLE rooms ADD COLUMN published boolean NOT NULL DEFAULT false;
-- Widen only; preserve every existing legacy description.
ALTER TABLE rooms ALTER COLUMN description TYPE text;

CREATE UNIQUE INDEX ux_hotels_slug ON hotels(slug) WHERE slug IS NOT NULL;
CREATE INDEX idx_catalog_hotels ON hotels(city_code, time_zone, id) WHERE published AND demo;
CREATE INDEX idx_catalog_rooms ON rooms(hotel_id, price, id) WHERE published;

ALTER TABLE hotels ADD CONSTRAINT hotels_catalog_metadata CHECK (
    (slug IS NULL OR (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$' AND slug !~ '^[0-9]+$'))
    AND (city_code IS NULL OR city_code ~ '^[a-z0-9-]{1,64}$')
    AND ((latitude IS NULL AND longitude IS NULL)
         OR (latitude IS NOT NULL AND longitude IS NOT NULL AND latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180))
    AND (NOT published OR (demo AND slug IS NOT NULL AND time_zone IS NOT NULL
         AND city_code IS NOT NULL AND description IS NOT NULL
         AND length(name) BETWEEN 1 AND 200 AND length(city) BETWEEN 1 AND 100
         AND length(address) <= 300 AND length(description) <= 10000))
);
ALTER TABLE rooms ADD CONSTRAINT rooms_catalog_metadata CHECK (
    NOT published OR (price > 0 AND price <= 9999999999.99 AND room_capacity BETWEEN 1 AND 100
                     AND length(name) BETWEEN 1 AND 200 AND length(description) <= 5000)
);

-- An initially unknown legacy slug can be configured explicitly once; established URLs are immutable.
CREATE FUNCTION preserve_catalog_slug() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.slug IS NOT NULL AND NEW.slug IS DISTINCT FROM OLD.slug THEN
        RAISE EXCEPTION 'Established hotel slug is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER hotels_immutable_slug BEFORE UPDATE OF slug ON hotels
    FOR EACH ROW EXECUTE FUNCTION preserve_catalog_slug();

CREATE TABLE hotel_photos (
    hotel_id bigint NOT NULL REFERENCES hotels(id),
    display_order integer NOT NULL CHECK (display_order BETWEEN 0 AND 9),
    asset_path varchar(500) NOT NULL,
    alt varchar(200) NOT NULL CHECK (length(trim(alt)) > 0),
    credit varchar(300) NOT NULL CHECK (length(trim(credit)) > 0),
    license varchar(100) NOT NULL CHECK (length(trim(license)) > 0),
    PRIMARY KEY(hotel_id, display_order),
    CONSTRAINT hotel_photos_safe_asset CHECK (asset_path ~ '^/media/[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*\.(jpg|jpeg|png|webp|avif)$')
);
CREATE TABLE room_photos (
    room_id bigint NOT NULL REFERENCES rooms(id),
    display_order integer NOT NULL CHECK (display_order BETWEEN 0 AND 9),
    asset_path varchar(500) NOT NULL,
    alt varchar(200) NOT NULL CHECK (length(trim(alt)) > 0),
    credit varchar(300) NOT NULL CHECK (length(trim(credit)) > 0),
    license varchar(100) NOT NULL CHECK (length(trim(license)) > 0),
    PRIMARY KEY(room_id, display_order),
    CONSTRAINT room_photos_safe_asset CHECK (asset_path ~ '^/media/[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*\.(jpg|jpeg|png|webp|avif)$')
);
