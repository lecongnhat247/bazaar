-- M4: auditable marketplace ledger. ledger_entry is append-only (enforced by
-- trigger). Corrections are written as REVERSAL entries, never as updates.
-- Mock settlement only: nothing here moves real money.

CREATE TABLE commission_rate (
    id UUID PRIMARY KEY,
    seller_id VARCHAR(64) NOT NULL,
    rate_bps INTEGER NOT NULL CHECK (rate_bps >= 0 AND rate_bps <= 3000),
    effective_from TIMESTAMPTZ NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX commission_rate_seller_idx ON commission_rate (seller_id, effective_from DESC);

CREATE TABLE ledger_entry (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES customer_order (id),
    seller_id VARCHAR(64) NOT NULL,
    account VARCHAR(20) NOT NULL CHECK (account IN ('SELLER_PAYABLE', 'PLATFORM_REVENUE')),
    entry_type VARCHAR(20) NOT NULL CHECK (entry_type IN ('SALE', 'DISCOUNT', 'COMMISSION', 'PLATFORM_DISCOUNT', 'REVERSAL')),
    currency CHAR(3) NOT NULL,
    amount NUMERIC(19, 2) NOT NULL CHECK (amount <> 0),
    rate_bps INTEGER,
    reverses_entry_id UUID REFERENCES ledger_entry (id),
    idempotency_key VARCHAR(120) NOT NULL UNIQUE,
    reason VARCHAR(200),
    created_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ledger_reversal_shape CHECK ((entry_type = 'REVERSAL') = (reverses_entry_id IS NOT NULL))
);

CREATE INDEX ledger_entry_group_idx ON ledger_entry (order_id, seller_id);
CREATE INDEX ledger_entry_seller_idx ON ledger_entry (seller_id, account, created_at DESC);

-- An entry can be reversed at most once.
CREATE UNIQUE INDEX ledger_entry_single_reversal ON ledger_entry (reverses_entry_id)
    WHERE reverses_entry_id IS NOT NULL;

CREATE FUNCTION ledger_entry_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entry is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER ledger_entry_no_change
    BEFORE UPDATE OR DELETE ON ledger_entry
    FOR EACH ROW EXECUTE FUNCTION ledger_entry_append_only();

CREATE TRIGGER ledger_entry_no_truncate
    BEFORE TRUNCATE ON ledger_entry
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_entry_append_only();

-- One row per settlement request key. Replaying a key returns the original result,
-- even when that run found nothing to settle.
CREATE TABLE settlement_run (
    run_key VARCHAR(64) PRIMARY KEY,
    created_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE settlement (
    id UUID PRIMARY KEY,
    seller_id VARCHAR(64) NOT NULL,
    currency CHAR(3) NOT NULL,
    amount NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    entry_count INTEGER NOT NULL CHECK (entry_count > 0),
    status VARCHAR(10) NOT NULL DEFAULT 'MOCK_PAID' CHECK (status IN ('MOCK_PAID')),
    run_key VARCHAR(120) NOT NULL UNIQUE,
    created_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX settlement_seller_idx ON settlement (seller_id, created_at DESC);

-- A ledger entry can belong to at most one settlement.
CREATE TABLE settlement_entry (
    settlement_id UUID NOT NULL REFERENCES settlement (id),
    ledger_entry_id UUID NOT NULL UNIQUE REFERENCES ledger_entry (id),
    PRIMARY KEY (settlement_id, ledger_entry_id)
);
