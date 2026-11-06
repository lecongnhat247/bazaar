-- Runs only when the Postgres data volume is empty.
-- Changing a password here does nothing until that volume is removed.
-- CREATE DATABASE cannot run inside a transaction. The Postgres image
-- executes this file with psql autocommit, which is the required mode.

CREATE USER identity_user WITH PASSWORD 'identity_local';
CREATE DATABASE identity_db OWNER identity_user;

CREATE USER catalog_user WITH PASSWORD 'catalog_local';
CREATE DATABASE catalog_db OWNER catalog_user;

CREATE USER inventory_user WITH PASSWORD 'inventory_local';
CREATE DATABASE inventory_db OWNER inventory_user;

CREATE USER order_user WITH PASSWORD 'order_local';
CREATE DATABASE order_db OWNER order_user;

CREATE USER payment_user WITH PASSWORD 'payment_local';
CREATE DATABASE payment_db OWNER payment_user;

CREATE USER keycloak_user WITH PASSWORD 'keycloak_local';
CREATE DATABASE keycloak_db OWNER keycloak_user;
