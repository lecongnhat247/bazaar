CREATE TABLE outbox (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    aggregate_type VARCHAR(40) NOT NULL,
    aggregate_id VARCHAR(80) NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    event_version INT NOT NULL CHECK (event_version >= 1),
    correlation_id UUID NOT NULL,
    causation_id UUID,
    payload JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    claimed_at TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'SENDING', 'PUBLISHED')),
    retry_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(500),
    CONSTRAINT outbox_event_id_uq UNIQUE (event_id)
);

CREATE INDEX outbox_pending_idx ON outbox (occurred_at) WHERE status = 'PENDING';

CREATE TABLE processed_events (
    event_id UUID NOT NULL,
    consumer_name VARCHAR(80) NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT processed_events_pk PRIMARY KEY (consumer_name, event_id)
);
