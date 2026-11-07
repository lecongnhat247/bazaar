# BAZAAR Storefront

Customer shop for browsing products, keeping a cart in the browser, signing in through Keycloak, and checking out. The browser never receives the API gateway address or the access token. Tokens stay in HTTP-only cookies.

## Requirements

- Node.js 24.22.1 and npm 11.19.1, or any Node.js 20.9 or newer
- The Compose stack when you want live products, sign-in, and checkout

## Setup

From `apps/storefront`:

```bash
cp .env.example .env.local
npm ci
```

`.env.local` stays uncommitted.

| Variable | Local value | Purpose |
| --- | --- | --- |
| `API_GATEWAY_URL` | `http://localhost:8080` | Gateway called by the Next.js server |
| `KEYCLOAK_PUBLIC_URL` | `http://localhost:8088` | Address the browser uses for sign-in |
| `KEYCLOAK_INTERNAL_URL` | `http://localhost:8088` | Address the server uses to exchange the code. Compose sets this to `http://keycloak:8080` |

## Develop

```bash
npm test
npm run lint
npm run dev
```

Open http://localhost:3000. Active products load without an account. Cart, checkout, payment simulation, and orders ask you to sign in. The local customer is `customer` / `customer_local`.

## Pages

| Path | Purpose |
| --- | --- |
| `/` | Product list, search, categories, and the gateway check |
| `/products/{id}` | Product detail and add to cart |
| `/cart` | Browser cart |
| `/login` | Start Keycloak sign-in |
| `/checkout` | Review the cart |
| `/checkout/payment` | Simulate payment success or failure |
| `/orders` | The signed-in customer's orders |
| `/orders/{id}` | Order detail, saved prices, and status history |

## Versions

Pinned by `package-lock.json`: Next.js 16.4.5, React 19.3.0, TypeScript 5.
