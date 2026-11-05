# BAZAAR Storefront

Customer-facing Next.js application. This milestone is a landing page and a live check that the API Gateway is reachable. Accounts, catalog browsing, cart, and checkout are later work.

The page in the browser calls the storefront at `/api/gateway-health`. That route reads `API_GATEWAY_URL` on the server and requests the gateway health endpoint. The gateway address is not compiled into the browser bundle.

## Requirements

- Node.js 24.22.1 and npm 11.19.1, or any Node.js 20.9 or newer
- The API Gateway from `services/api-gateway` when you want the success state

## Setup

From `apps/storefront`:

```bash
cp .env.example .env.local
npm ci
```

`.env.local` stays uncommitted. The example value is `API_GATEWAY_URL=http://localhost:8080`.

## Develop

```bash
npm run dev
```

Open http://localhost:3000. `127.0.0.1` is also allowed for the dev server. The page shows a loading line, then either a success message or an error with a retry button. The marketplace section stays empty until products exist.

## Production build

```bash
npm run lint
npm run build
npm run start
```

`npm run start` also reads `.env.local`.

## Versions

Pinned by `package-lock.json`: Next.js 16.4.5, React 19.3.0, TypeScript 5. Cache Components are left off so each status check talks to the current gateway process.
