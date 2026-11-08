# Notification Service

Turns order and payment facts into a customer's own notices. The browser never talks to Kafka. A signed-in customer receives only the notices for the subject in a verified token.

The service listens to `bazaar.order.events` and `bazaar.payment.events` in group `notification-service`. It keeps `ORDER_CREATED`, `PAYMENT_SUCCEEDED`, `PAYMENT_FAILED`, `ORDER_CONFIRMED`, and `ORDER_CANCELLED`. A repeated `eventId` is stored once. Notices survive a disconnect in `notification_db`. The socket is only the live path: after a reconnect the storefront reads `GET /api/v1/notifications` and the order from the order service.

A payload that is not JSON is copied to `bazaar.dlq` at once. A transient failure is retried twice with a longer pause each time, then copied there too. The log includes `eventId` and `correlationId`. Consumer lag is logged every 30 seconds. Readiness is the database. Kafka and Redis are not part of it. If Redis is set, Socket.IO uses it to fan out across instances. If Redis does not answer, that process keeps its own connections.

## Requirements

- Node.js 24
- PostgreSQL `notification_db` for a local run. Tests use an in-memory store and do not need Kafka.

## Build and test

```bash
cd services/notification-service
npm ci
npm test
npm run build
```

## Run

The process listens on port 8080 unless `PORT` is set. Compose publishes it on host port 8086.

## Endpoints

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| GET | `/health` | Public | Process is up |
| GET | `/health/ready` | Public | Database answers |
| GET | `/api/v1/notifications` | Bearer JWT or `bazaar_access` cookie | The caller's notices, newest 50 |

The socket is Socket.IO on the same port. The server reads the cookie or `auth.token`, checks the JWT, and joins `customer:{subject}`. The client cannot choose another customer.

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `PORT` | `8080` | HTTP and socket port |
| `DATABASE_URL` | empty | Postgres URL. Empty uses memory and is for tests |
| `KAFKA_BROKERS` | empty | Broker list. Empty skips the consumer |
| `REDIS_HOST` | empty | Redis for the Socket.IO adapter. Empty stays in memory |
| `REDIS_PORT` | `6379` | Redis port |
| `KEYCLOAK_ISSUER` | unset | Expected `iss` claim |
| `KEYCLOAK_JWKS_URI` | unset | RS256 signing keys |
| `JWT_HMAC_SECRET` | unset | Test-only HS256 secret. Leave unset in Compose |
| `CORS_ORIGIN` | `http://localhost:3000` | Browser origins allowed to open a socket |
