-- Sample catalog for local development. The seller id matches the imported
-- Keycloak user "seller" when that realm is imported into an empty database.
-- An existing Keycloak volume keeps the seller id it created on first import.

INSERT INTO category (id, name, slug) VALUES
    ('8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d0001', 'Electronics', 'electronics'),
    ('8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d0002', 'Home', 'home'),
    ('8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d0003', 'Fashion', 'fashion');

INSERT INTO product (
    id, seller_id, category_id, name, description, sku, price, currency, status, attributes, created_at, updated_at
) VALUES
    (
        '8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d1001',
        '11111111-1111-4111-8111-111111111111',
        '8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d0002',
        'Desk lamp',
        'A small lamp for a desk.',
        'HOME-LAMP-1',
        25.00,
        'USD',
        'ACTIVE',
        '{"color":"white"}'::jsonb,
        TIMESTAMPTZ '2026-11-06 00:00:00+00',
        TIMESTAMPTZ '2026-11-06 00:00:00+00'
    ),
    (
        '8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d1002',
        '11111111-1111-4111-8111-111111111111',
        '8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d0003',
        'Cotton shirt',
        'A plain cotton shirt.',
        'FASHION-SHIRT-1',
        19.50,
        'USD',
        'ACTIVE',
        '{}'::jsonb,
        TIMESTAMPTZ '2026-11-06 00:00:00+00',
        TIMESTAMPTZ '2026-11-06 00:00:00+00'
    ),
    (
        '8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d1003',
        '11111111-1111-4111-8111-111111111111',
        '8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d0001',
        'Pocket radio',
        'Not published yet.',
        'ELEC-RADIO-1',
        40.00,
        'USD',
        'DRAFT',
        '{}'::jsonb,
        TIMESTAMPTZ '2026-11-06 00:00:00+00',
        TIMESTAMPTZ '2026-11-06 00:00:00+00'
    );

INSERT INTO product_image (id, product_id, url, alt_text, position) VALUES
    (
        '8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d2001',
        '8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d1001',
        'https://cdn.localhost/catalog/desk-lamp.jpg',
        'Desk lamp',
        0
    );
