ALTER TABLE outbox ADD COLUMN topic VARCHAR(120) NOT NULL DEFAULT 'bazaar.order.events';

ALTER TABLE customer_order ADD COLUMN compensate_to VARCHAR(32);
ALTER TABLE customer_order ADD COLUMN payment_state VARCHAR(16) NOT NULL DEFAULT 'NONE';
ALTER TABLE customer_order ADD COLUMN saga_attempt INT NOT NULL DEFAULT 0;

ALTER TABLE order_item ALTER COLUMN reservation_id DROP NOT NULL;
ALTER TABLE order_item ADD COLUMN stock_state VARCHAR(16);
