# VMS Platform: notes for contributors and AI assistants

## Evaluation criteria (non-negotiable)
Every change must keep all six green:
1. **Functionality and completeness.** Every requirement is traced in `docs/01-BRD.md`.
2. **Code quality and structure.** Package-by-feature, ports and adapters, small classes, no dead code.
3. **Security.**
   - No secrets in the repo; ownership checks on every video; validate all input.
   - Signed URLs only; unwatermarked video is never served.
4. **Error handling.**
   - RFC 7807 errors with stable codes and no internals.
   - Offline-safe uploads; retries plus a dead-letter topic.
5. **Architecture.**
   - The watermark service owns no database.
   - Outbox for every event; keep the ADRs and diagrams up to date.
6. **Deployment.** One-command local run; scripted GCP deploy with rollback.

## Commands
| What | Command |
| --- | --- |
| API tests + format check | `cd api-service && mvn verify` (`mvn spotless:apply` to format) |
| Watermark tests (needs ffmpeg locally for the integration test) | `cd watermark-service && mvn verify` |
| Frontend | `cd frontend && npm ci && npm run lint && npm test && npm run build` (`npm run format`) |
| Local stack | `docker compose -f infra/docker-compose.yml --env-file infra/.env up -d --build` → http://localhost:8088 |
| Provision GCP | copy `infra/terraform/terraform.tfvars.example` to `terraform.tfvars`, then `terraform -chdir=infra/terraform init && terraform -chdir=infra/terraform apply` |
| Kafka locally | Kafka UI: `docker compose -f infra/docker-compose.yml --env-file infra/.env --profile tools up -d kafka-ui` → http://localhost:8090 (read-only); host clients: `localhost:9094`. See `docs/09-Kafka-Local-Guide.md` |
| Validate Terraform | `terraform -chdir=infra/terraform fmt -check && terraform -chdir=infra/terraform init -backend=false && terraform -chdir=infra/terraform validate` (also in CI) |
| Deploy / rollback | `./infra/scripts/deploy.sh` / `./infra/scripts/deploy.sh --rollback` |

## Conventions
- Java 17, Spring Boot 4.1 (Jackson 3: `tools.jackson.*`), google-java-format via Spotless.
- Configuration comes only from environment variables (see `.env.example`). Recording limits live in `vms.recording` and reach the browser through `GET /api/v1/config/public`.
- **Event contracts:** `docs/contracts/*.schema.json`. Each service keeps its own record copies; there is no shared jar.
- **Versions:** pin exact dependency versions; only use releases at least 7 days old.
