-- Commit and release look up an order line by reservation_id.
-- The primary key is the line id, so that lookup scanned the table.
-- The index is maintained on insert and when the reservation id is filled in.
CREATE INDEX order_item_reservation_idx ON order_item (reservation_id);
