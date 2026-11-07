-- Stock for the sample catalog products. The seller id matches a fresh Keycloak import.
-- This service does not read the catalog database.

INSERT INTO inventory_item (product_id, seller_id, available, reserved, updated_at) VALUES
    ('8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d1001', '11111111-1111-4111-8111-111111111111', 10, 0, TIMESTAMPTZ '2026-11-06 00:00:00+00'),
    ('8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d1002', '11111111-1111-4111-8111-111111111111', 5, 0, TIMESTAMPTZ '2026-11-06 00:00:00+00'),
    ('8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d1003', '11111111-1111-4111-8111-111111111111', 2, 0, TIMESTAMPTZ '2026-11-06 00:00:00+00');
