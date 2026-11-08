# Release

Images are built by GitHub Actions and pushed to GHCR. A cluster deploy uses an immutable tag. This repository does not contain a production cluster, and it does not deploy one on its own.

## Tags

Every publish adds `sha-` plus the full 40-character commit SHA. A push to `main` also adds `latest`. A push of a `v*` git tag also adds that git tag. A manual publish can add one extra tag, and that tag must be `sha-<40 hex characters>` or `v<major>.<minor>.<patch>`.

`latest` moves. Do not deploy it. `infrastructure/scripts/deploy.sh` and `infrastructure/scripts/rollback.sh` refuse it. Local kind still loads images tagged `latest` because those images were built on the laptop and were not published.

## What the workflows do

| Workflow | When it runs | What it checks |
| --- | --- | --- |
| `ci.yml` | Pull request, and push to `main` | Maven tests, storefront tests and build, notification tests and build, Helm lint, release-tag rules, a rendered chart, npm audit, Trivy filesystem scan, image build, Trivy image scan |
| `publish-images.yml` | Push to `main`, push of a `v*` tag, or a manual run | Builds each image, scans it, and pushes only after the critical scan passes |
| `deploy.yml` | Manual run only | Renders the chart. Applies it only when the action is `deploy` and the `staging` environment has a `KUBECONFIG` secret |

Pull requests do not publish images. The publish job has `contents: read` and `packages: write`. The other jobs have `contents: read`. The kubeconfig is written to a file with mode `0600` and is not printed.

Trivy `0.67.2` reports fixed high vulnerabilities and fails the job on a fixed critical vulnerability. Unfixed findings are ignored. The order image scanned on 8 November 2026 had no critical findings. It did have fixed high findings: `apt` CVE-2026-109518, `at.yawk.lz4:lz4-java` 1.11.2 (CVE-2026-106451), and `org.xerial.snappy:snappy-java` 1.1.10.7 (CVE-2026-108106, CVE-2026-93451). Those highs do not fail the job. They are visible in the scan log.

`npm audit --audit-level=high` covers the storefront and the notification service. Notification pins `multer` 2.5.0 so the 2.4.0 advisory is not installed.

Maven `./mvnw test` runs the unit tests and the Testcontainers tests in one Surefire run. There is no second integration-test phase.

## Deploy

Render without a cluster:

```bash
bash infrastructure/scripts/deploy.sh --render sha-<40 hex characters> \
  --values infrastructure/helm/bazaar/values-remote.example.yaml
```

Apply to the current kubectl context:

```bash
bash infrastructure/scripts/deploy.sh sha-<40 hex characters> \
  --values infrastructure/helm/bazaar/values-remote.example.yaml
```

Helm upgrades with `--rollback-on-failure` and `--wait`. If the wait fails, Helm returns the release to the last successful revision. The script then checks `kubectl rollout status` for all eight Deployments. Java readiness still needs PostgreSQL, Kafka, Redis, and Keycloak outside the chart. A laptop kind cluster without those stores fails this wait, and the failure rolls the release back.

The GitHub workflow named Deploy does the same render. Choosing `deploy` uses the GitHub Environment `staging`. Add required reviewers on that environment before storing `KUBECONFIG` there. The secret value is the kubeconfig file text. Without the secret, the job stops and nothing is applied. There is no production environment in this workflow.

## Rollback

```bash
helm history bazaar --namespace bazaar
bash infrastructure/scripts/rollback.sh <revision>
```

The revision number comes from that history. The script refuses an empty revision and revision 0, so a rollback names the release it restores. `--wait` checks the Deployments. If that revision is also unhealthy, run the script again with an earlier revision whose image tag is a known `sha-` or `vX.Y.Z`.

The image tag for a revision is in the Helm history description and in the Deployment image. Match it to the commit before treating the rollback as the running release.
