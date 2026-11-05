# ADR-001: Monorepo structure

- Status: Accepted
- Date: 2026-11-04

## Context

BAZAAR will grow into several applications and services. Phase 1 needs a layout that keeps service boundaries clear while a single developer can build the skeleton from one checkout. The services must still build and deploy independently.

The workspace started empty on `main`, with `origin` at `https://github.com/lecongnhat247/bazaar.git`.

## Decision

Use one Git repository.

- `apps/storefront` holds the Next.js application.
- `services/<name>` holds each Spring Boot application, its tests, configuration, Dockerfile, and README.
- `docs/` holds architecture, ADRs, development, and deployment documents.
- `infrastructure/` holds the kind configuration, the Helm chart, and the cluster scripts.
- `.github/workflows/` holds the test workflow and the GHCR publish workflow.
- A parent POM and one Maven Wrapper live at the repository root. Shared dependency versions live in the parent.
- A service is built with `./mvnw -pl services/<name> -am test` from the root.
- The storefront is built with npm inside `apps/storefront`, using the committed lockfile.
- Directories are added in the milestone that puts real files in them. Empty placeholder trees are not committed.
- `contracts/` is added when an OpenAPI document or event schema exists.

## Consequences

- One pull request can change a service and the gateway route that calls it.
- Each service still has its own pipeline job, image, and Kubernetes Deployment.
- The parent POM is required to build a service. That is a repository dependency, not a runtime dependency.
- Publishing one service does not require publishing the others.

## Alternatives considered

Separate repositories would isolate release cadence earlier and would slow the skeleton. A copy of the Maven Wrapper inside every service would repeat the same files. One root wrapper is the build entry point.
