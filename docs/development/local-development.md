# Local development

This is the day-to-day path on the development machine. Tool versions and the reason for each choice are in the [setup guide](setup.md).

A service is ready when its health endpoint responds and, for the five backend services, its info endpoint responds. Those two paths stay public. Any other business path requires a Keycloak JWT. Unit tests start PostgreSQL with Testcontainers and do not start Keycloak. A full login uses Compose.

## Backend

From the repository root, for that shell only:

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-25.0.5.0.3"
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw test
./mvnw -pl services/identity-service -am spring-boot:run
```

The parent project skips `spring-boot:run` because it has no main class. The selected service turns that skip off. Stop the process before starting another service on a different port. Ports are 8080 for the gateway and 8081–8085 for the other services.

`JAVA_HOME` is not set for the whole operating system. Each new shell needs the export above.

## Storefront

```bash
cd apps/storefront
npm ci
npm run dev
```

Open http://localhost:3000. The page calls the gateway through `API_GATEWAY_URL`. The example value is `http://localhost:8080` in `apps/storefront/.env.example`. Copy that file to `.env.local` only when you need a different address. `.env.local` stays untracked.

`npm run lint` and `npm run build` match the checks used in CI. `npm start` serves the production build.

## Which runtime to use

| Goal | Use | Leave stopped |
| --- | --- | --- |
| Edit a service quickly | Maven or `npm run dev` | Compose and kind |
| Run the packaged images together | [Docker Compose](../deployment/docker-compose.md) | kind |
| Run the Helm chart | [kind](../deployment/kubernetes-kind.md), from WSL Ubuntu | Compose |

Docker Desktop has about 7.6 GiB. Compose and kind together do not fit that budget. kind and Helm commands run in WSL Ubuntu. Git Bash does not have `kind`.

## Configuration

Root [`.env.example`](../../.env.example) lists the variable names. Addresses change by environment. Database passwords in that file are for this machine. Running one business service from Maven needs a reachable Postgres database and, for a real token, Keycloak. `./mvnw test` does not need either of those running.
