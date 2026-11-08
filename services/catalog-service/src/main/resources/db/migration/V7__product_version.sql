-- A seller can save the same product from two requests at once.
-- The version makes the second save fail instead of overwriting the first price.
-- Each product update writes this column in addition to the changed fields.
ALTER TABLE product ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
