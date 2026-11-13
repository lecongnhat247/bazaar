# Seller Center

Seller desk for one store. It is a separate Next.js app and uses the same Keycloak client and HTTP-only cookie as the storefront. It does not send a seller id. The gateway token is the seller.

Open it at http://localhost:3002. The local seller is `seller` / `seller_local`. There is no public sign-up.

A new store stays `PENDING_APPROVAL` until an administrator approves it. Product publish, stock changes, and new campaigns call the existing seller-status check and return 403 until the store is `ACTIVE`.

The browser talks only to this app. The server calls the gateway. The proxy allows store, catalog, stock, sales, and campaign routes. It does not forward customer order lists, checkout, payments, or seller moderation.

## Run

```bash
cd apps/seller-center
npm ci
npm test
npm run dev
```

Copy `.env.example` to `.env` when the gateway and Keycloak are on the host. Compose sets the same variables.

Keycloak must allow `http://localhost:3002/*` as a redirect. That URI is in `infrastructure/keycloak/bazaar-realm.json`. A realm that was imported before this change keeps the old redirect list until the client is updated.

## Limits

Sales show only this seller's order lines. The status is the customer order's checkout saga. There is no shipment. Inventory movements have no list API, so the stock page does not invent one. Campaign performance is quota and remaining quota, not revenue. The kind chart does not run this app.
