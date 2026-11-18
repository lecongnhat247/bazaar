# Analytics service

Reporting read model for BAZAAR. It is not the authority for orders, inventory, payments, or settlement.

The process is ASP.NET Core on .NET 10. An empty `ANALYTICS_SQL` keeps the fold in memory and drops it on restart. Compose profile `analytics` points `ANALYTICS_SQL` at SQL Server database `analytics_db`. The service creates that database from `master` and applies `schema.sql`. It does not read `order_db`, `payment_db`, or `catalog_db`.

## Run locally

From this directory, with no SQL Server:

```bash
dotnet test tests/Analytics.Tests.csproj
dotnet run --urls http://localhost:8089
```

Set `KAFKA_BROKERS` to consume. Leave it empty and the consumer does not start. Tokens are HMAC when `JWT_HMAC_SECRET` is set, otherwise RS256 from `KEYCLOAK_JWKS_URI`. The issuer is `KEYCLOAK_ISSUER`, default `http://localhost:8088/realms/bazaar`.

SQL Server is not part of `docker compose up`. Start it only when the rest of the stack is already up and kind is not running:

```bash
docker compose --profile analytics up -d sqlserver analytics-service
```

Host port 8089. The gateway route is `/api/v1/analytics/**`. The gateway does not wait for this service, so the shop still starts when the profile is off.

## Reports

| Method | Path | Who |
| --- | --- | --- |
| GET | `/api/v1/analytics/platform/overview` | ADMIN or OPERATOR |
| GET | `/api/v1/analytics/platform/sales?grain=day\|week\|month` | ADMIN or OPERATOR |
| GET | `/api/v1/analytics/platform/reconciliation` | ADMIN or OPERATOR |
| GET | `/api/v1/analytics/products/performance` | ADMIN or OPERATOR |
| GET | `/api/v1/analytics/products/categories` | ADMIN or OPERATOR |
| GET | `/api/v1/analytics/flash-sales/performance` | ADMIN or OPERATOR |
| GET | `/api/v1/analytics/sellers/me/sales` | the token subject when it has SELLER, or staff |
| GET | `/api/v1/analytics/sellers/{sellerId}/sales` | that seller, or staff |
| POST | `/api/v1/analytics/rebuild` | ADMIN |

`sellerId` as a query is rejected. A customer token cannot read seller sales. Revenue is the sum of order totals whose current status is `CONFIRMED`, grouped by currency. A refund changes the payment status and does not change that revenue. Commission comes only from the `sellers` array on `order.confirmed`. If that array is missing, commission is null and `commissionComplete` is false. Settlement is `{ available: false }` because no settlement event is published.

`asOf` is the last successful apply. The fold is eventually consistent and at-least-once. It is not exactly-once and it is not a full scan of a warehouse at production size: each apply rewrites the projection from the in-memory fold.

Rebuild folds the stored raw events again. It cannot replay Kafka. Local topic retention is one hour.

Readiness is `SELECT 1` on SQL Server, or the memory store. Kafka and Keycloak are not readiness checks. The kind chart does not include this service or SQL Server.
