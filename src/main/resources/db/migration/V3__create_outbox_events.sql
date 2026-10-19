CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    topic VARCHAR(255) NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    next_attempt_at TIMESTAMP NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    published_at TIMESTAMP
);

CREATE INDEX idx_outbox_pending ON outbox_events (next_attempt_at, created_at, id)
    WHERE published_at IS NULL;
