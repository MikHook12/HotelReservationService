-- Spring Session JDBC PostgreSQL schema. Existing data is retained; no automatic demo accounts.
CREATE TABLE SPRING_SESSION (
    PRIMARY_ID CHAR(36) NOT NULL PRIMARY KEY,
    SESSION_ID CHAR(36) NOT NULL,
    CREATION_TIME BIGINT NOT NULL,
    LAST_ACCESS_TIME BIGINT NOT NULL,
    MAX_INACTIVE_INTERVAL INT NOT NULL,
    EXPIRY_TIME BIGINT NOT NULL,
    PRINCIPAL_NAME VARCHAR(100)
);
CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION(SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION(EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION(PRINCIPAL_NAME);
CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36) NOT NULL REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE,
    ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES BYTEA NOT NULL,
    PRIMARY KEY(SESSION_PRIMARY_ID, ATTRIBUTE_NAME)
);
ALTER TABLE users ADD COLUMN browser_security_version BIGINT NOT NULL DEFAULT 0;
CREATE FUNCTION browser_identity_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.name,NEW.email,NEW.password,NEW.role) IS DISTINCT FROM ROW(OLD.name,OLD.email,OLD.password,OLD.role) THEN
        NEW.browser_security_version := OLD.browser_security_version + 1;
    ELSE
        -- A stale ORM update cannot lower a revocation version.
        NEW.browser_security_version := GREATEST(NEW.browser_security_version, OLD.browser_security_version);
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER browser_identity_revocation BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION browser_identity_changed();
CREATE TABLE browser_rate_limits (
    bucket_key VARCHAR(64) PRIMARY KEY,
    window_start TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL CHECK(attempts >= 0)
);
CREATE INDEX browser_rate_limits_expiry ON browser_rate_limits(window_start);
CREATE TABLE browser_security_settings (
    name VARCHAR(64) PRIMARY KEY,
    secret BYTEA NOT NULL
);
CREATE TABLE browser_demo_accounts (
    user_id BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL
);
