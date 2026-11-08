-- The order keeps the campaign id so a retried reservation command still
-- spends campaign quota. A normal checkout leaves this null.
ALTER TABLE customer_order ADD COLUMN campaign_id UUID;
