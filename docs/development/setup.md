# Development setup

This guide matches the tools found on the development machine during M0. It does not install or upgrade them.

## What M1 requires

M1 is documentation. You can read it with no JDK, Node.js, Docker, or cluster running.

## Tools already present

| Tool | Location | Version |
| --- | --- | --- |
| Git | Windows | 2.40.1 |
| JDK | `C:\Program Files\Java\jdk-25.0.5.0.3` | Oracle 25.0.5.0.3 |
| Node.js and npm | Windows | 24.22.1 and 11.19.1 |
| Docker Desktop | Windows, WSL integration on | Engine 29.9.2, Compose 5.6.1, Buildx 0.38.1 |
| kubectl, kind, Helm | WSL Ubuntu | kubectl 1.37.2, kind 0.33.0, Helm 4.3.1 |

Maven is not installed. M2 adds the Maven Wrapper, which downloads Maven on first use. The GitHub CLI is not installed. CI files can be reviewed without it. Publishing to GHCR needs repository permissions and is verified when a trusted branch is pushed.

`JAVA_HOME` is unset. For a backend build, set it in that shell only:

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-25.0.5.0.3"
export PATH="$JAVA_HOME/bin:$PATH"
java -version
```

Leave the Windows system environment unchanged unless you decide to set `JAVA_HOME` yourself.

## Repository

```bash
git clone https://github.com/lecongnhat247/bazaar.git
cd bazaar
```

The current checkout is already the working tree for this repository. The branch is `main`. M1 is the first content.

Line endings are LF through `.gitattributes`, except Windows command scripts.

## Configuration

```bash
cp .env.example .env
```

`.env` stays uncommitted. Phase 1 values are URLs and ports, not passwords. See [`.env.example`](../../.env.example).

## Backend commands

Set `JAVA_HOME` in the shell, then run the wrapper from the repository root. The first run downloads Maven 3.10.0 into the user cache. If the shell says `./mvnw` is not executable, run `chmod +x mvnw`.

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-25.0.5.0.3"
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw test
./mvnw -pl services/identity-service -am test
./mvnw -pl services/api-gateway -am spring-boot:run
```

Replace `identity-service` or `api-gateway` with another module name to build or run that service alone. Default ports are in each service README.

## Storefront commands

From `apps/storefront`:

```bash
cp .env.example .env.local
npm ci
npm run dev
```

Open http://localhost:3000. `API_GATEWAY_URL` defaults to `http://localhost:8080` in `.env.example`. Start the API Gateway before expecting the success state.

## Compose commands

From the repository root:

```bash
docker compose up --build -d
docker compose ps
docker compose down
```

See the [Compose guide](../deployment/docker-compose.md).

## kind commands

From WSL Ubuntu, with Compose stopped:

```bash
bash infrastructure/scripts/create-cluster.sh
bash infrastructure/scripts/load-images.sh
bash infrastructure/scripts/install.sh
bash infrastructure/scripts/delete-cluster.sh
```

Use WSL Ubuntu for kind, Helm, and kubectl. Windows kubectl 1.36.1 ships with Docker Desktop and is one minor behind the kind 1.37 API. Stop Docker Compose before creating the kind cluster. The Docker VM has about 7.6 GiB. See the [kind guide](../deployment/kubernetes-kind.md).

Day-to-day commands are in [local development](local-development.md). Image publishing is described in [GitHub Actions and GHCR](../deployment/github-actions.md).

## How to verify M1

Confirm these files exist and that the Mermaid blocks render:

- `README.md`
- `docs/architecture/overview.md`
- `docs/architecture/system-context.md`
- `docs/architecture/container-diagram.md`
- `docs/architecture/service-responsibilities.md`
- `docs/architecture/deployment-architecture.md`
- `docs/adr/ADR-001-monorepo-structure.md` through `ADR-005-database-ownership.md`
- `docs/development/setup.md`
- `docs/deployment/overview.md`
- `.gitignore`, `.gitattributes`, `.editorconfig`, `.env.example`

There is no application to start.
