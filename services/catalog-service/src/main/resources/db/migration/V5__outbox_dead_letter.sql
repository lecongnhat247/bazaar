ALTER TABLE outbox DROP CONSTRAINT outbox_status_check;
ALTER TABLE outbox ADD CONSTRAINT outbox_status_check
    CHECK (status IN ('PENDING', 'SENDING', 'PUBLISHED', 'DEAD'));
ALTER TABLE outbox ADD COLUMN next_attempt_at TIMESTAMPTZ;
