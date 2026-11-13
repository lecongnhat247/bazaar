CREATE TABLE product_moderation (
    id UUID PRIMARY KEY,
    actor_subject VARCHAR(64) NOT NULL,
    product_id UUID NOT NULL REFERENCES product (id),
    action VARCHAR(32) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    result_status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX product_moderation_product_idx ON product_moderation (product_id, created_at DESC);
