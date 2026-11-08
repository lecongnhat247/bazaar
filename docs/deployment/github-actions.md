# GitHub Actions and GHCR

Two workflows live in `.github/workflows/`. They use `GITHUB_TOKEN`. No registry password is stored in the repository.

| Workflow | When it runs | What it does |
| --- | --- | --- |
| `ci.yml` | Pull requests, and pushes to `main` | Maven tests, storefront unit tests, lint, and production build, notification service tests and build, Helm lint. Pull requests also build the eight images and do not push them. |
| `publish-images.yml` | Push to `main`, push of a `v*` tag, or a manual run | Builds the eight images and pushes them to GHCR. |

A GitHub Release that creates a `v*` tag publishes images through the tag push. The publish workflow has no `pull_request` trigger and no `pull_request_target` trigger. A pull request from a fork cannot push images. The publish job also exits if it is ever invoked for a fork pull request.

## Permissions

Each job sets its own permissions. The workflow default is empty.

| Job | Token permissions |
| --- | --- |
| Backend tests, storefront, notification service, Helm lint, pull-request image build | `contents: read` |
| Image publish | `contents: read` and `packages: write` |

`packages: write` is what lets `GITHUB_TOKEN` create and update packages in GHCR. It is not granted to pull-request jobs. The login step uses `github.actor` as the user and `secrets.GITHUB_TOKEN` as the password. Nothing else is accepted as a credential.

In the repository settings, leave Actions workflow permissions at read for contents. The publish workflow raises its own token to `packages: write` for that job only. Do not add a personal access token to the repository secrets for this Phase 1 flow.

## Image names and tags

The owner is the GitHub repository owner, in lower case. The application name matches the local image name.

```text
ghcr.io/<owner>/bazaar-<application>:<tag>
```

| Application | Image |
| --- | --- |
| API Gateway | `ghcr.io/<owner>/bazaar-api-gateway` |
| Identity | `ghcr.io/<owner>/bazaar-identity-service` |
| Catalog | `ghcr.io/<owner>/bazaar-catalog-service` |
| Inventory | `ghcr.io/<owner>/bazaar-inventory-service` |
| Order | `ghcr.io/<owner>/bazaar-order-service` |
| Payment | `ghcr.io/<owner>/bazaar-payment-service` |
| Storefront | `ghcr.io/<owner>/bazaar-storefront` |
| Notification | `ghcr.io/<owner>/bazaar-notification-service` |

Tags:

- `sha-<12 character commit>` on every publish.
- `latest` only for a push to `main`.
- The git tag, such as `v0.1.0`, when the publish was triggered by a `v*` tag.
- The manual `image_tag` input when a person runs the workflow and fills it in.

The first push of a trusted branch creates the packages. A private repository gets private packages. This milestone added the workflows before the repository had a commit, so the first push is the first live publish. Until that push, GHCR does not contain these images.

## What a developer changes

Image tags are selected by the workflow. To publish an extra tag from GitHub, run **Publish images** manually and set `image_tag`. To point a cluster at a published tag, change the Helm values described in [remote Kubernetes](remote-kubernetes.md). Do not put the tag into a Dockerfile.
