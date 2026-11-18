CREATE TABLE IF NOT EXISTS recommendation_event (
    event_id UUID PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL,
    body JSONB NOT NULL
);

CREATE INDEX IF NOT EXISTS recommendation_event_occurred_idx
    ON recommendation_event (occurred_at, event_id);
