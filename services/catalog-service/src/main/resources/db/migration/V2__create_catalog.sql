CREATE TABLE category (
    id UUID PRIMARY KEY,
    name VARCHAR(80) NOT NULL UNIQUE,
    slug VARCHAR(80) NOT NULL UNIQUE
);

CREATE TABLE product (
    id UUID PRIMARY KEY,
    seller_id VARCHAR(64) NOT NULL,
    category_id UUID NOT NULL REFERENCES category (id),
    name VARCHAR(160) NOT NULL,
    description VARCHAR(4000),
    sku VARCHAR(64) NOT NULL UNIQUE,
    price NUMERIC(19, 2) NOT NULL CHECK (price > 0),
    currency CHAR(3) NOT NULL,
    status VARCHAR(32) NOT NULL,
    attributes JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX product_seller_status_idx ON product (seller_id, status);
CREATE INDEX product_category_status_idx ON product (category_id, status);
CREATE INDEX product_price_idx ON product (price);

CREATE TABLE product_image (
    id UUID PRIMARY KEY,
    product_id UUID NOT NULL REFERENCES product (id) ON DELETE CASCADE,
    url VARCHAR(500) NOT NULL,
    alt_text VARCHAR(160),
    position INT NOT NULL,
    UNIQUE (product_id, position)
);
