IF OBJECT_ID(N'dbo.analytics_event', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.analytics_event (
        event_id UNIQUEIDENTIFIER NOT NULL PRIMARY KEY,
        occurred_at DATETIMEOFFSET NOT NULL,
        body NVARCHAR(MAX) NOT NULL
    );
END;

IF OBJECT_ID(N'dbo.fact_order', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.fact_order (
        order_id UNIQUEIDENTIFIER NOT NULL PRIMARY KEY,
        status NVARCHAR(32) NOT NULL,
        currency NCHAR(3) NOT NULL,
        total DECIMAL(19, 2) NOT NULL,
        customer_id NVARCHAR(64) NOT NULL,
        campaign_id UNIQUEIDENTIFIER NULL,
        occurred_at DATETIMEOFFSET NOT NULL
    );
END;

IF OBJECT_ID(N'dbo.fact_order_item', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.fact_order_item (
        order_id UNIQUEIDENTIFIER NOT NULL,
        product_id UNIQUEIDENTIFIER NOT NULL,
        seller_id NVARCHAR(64) NOT NULL,
        product_name NVARCHAR(160) NULL,
        quantity INT NOT NULL,
        unit_price DECIMAL(19, 2) NULL,
        line_total DECIMAL(19, 2) NULL,
        discount_amount DECIMAL(19, 2) NOT NULL,
        PRIMARY KEY (order_id, product_id)
    );
END;

IF OBJECT_ID(N'dbo.fact_payment', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.fact_payment (
        payment_id UNIQUEIDENTIFIER NOT NULL PRIMARY KEY,
        order_id UNIQUEIDENTIFIER NOT NULL,
        customer_id NVARCHAR(64) NOT NULL,
        amount DECIMAL(19, 2) NOT NULL,
        currency NCHAR(3) NOT NULL,
        status NVARCHAR(32) NOT NULL,
        occurred_at DATETIMEOFFSET NOT NULL
    );
END;

IF OBJECT_ID(N'dbo.fact_seller_amount', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.fact_seller_amount (
        order_id UNIQUEIDENTIFIER NOT NULL,
        seller_id NVARCHAR(64) NOT NULL,
        gross DECIMAL(19, 2) NOT NULL,
        discount DECIMAL(19, 2) NOT NULL,
        commission DECIMAL(19, 2) NOT NULL,
        rate_bps INT NULL,
        PRIMARY KEY (order_id, seller_id)
    );
END;

IF OBJECT_ID(N'dbo.dim_product', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.dim_product (
        product_id UNIQUEIDENTIFIER NOT NULL PRIMARY KEY,
        name NVARCHAR(160) NOT NULL,
        category_id UNIQUEIDENTIFIER NULL,
        seller_id NVARCHAR(64) NULL,
        status NVARCHAR(32) NOT NULL,
        updated_at DATETIMEOFFSET NOT NULL
    );
END;
