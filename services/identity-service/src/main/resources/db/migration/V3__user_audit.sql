CREATE TABLE user_audit (
    id UUID PRIMARY KEY,
    actor_subject VARCHAR(64) NOT NULL,
    action VARCHAR(32) NOT NULL,
    target_subject VARCHAR(64) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    result_status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX user_audit_created_idx ON user_audit (created_at DESC);
