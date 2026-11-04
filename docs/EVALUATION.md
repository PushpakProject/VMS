# Evaluation criteria → evidence

| Criterion | Evidence |
| --- | --- |
| **Functionality and completeness** | Every brief requirement is traced to code and tests in `docs/01-BRD.md`. 20-scenario matrix in `REVIEWER-GUIDE.md`. `infra/scripts/smoke-test.sh` covers sign-in → signed upload → Kafka → ffmpeg → READY → playback, plus negative checks. Deliverables: live URL, repo, setup guide (`docs/00-Run-Guide.md`), test credentials (Secret Manager). |
| **Code quality and structure** | Package-by-feature; ports and adapters; Strategy, State, Template Method, Builder, Factory, Observer, Outbox (`docs/03-LLD.md`). Formatting is enforced (Spotless google-java-format, Prettier, ESLint with `--max-warnings 0`, strict TypeScript). Tests: **api 62** (including Testcontainers Postgres + Kafka), **watermark 24** (including real ffmpeg), **frontend 35**. CI in `.github/workflows/ci.yml`. |
| **Security** | `docs/06-Security.md` and `docs/08-Security-Review.md` (OWASP pass, no Critical/High findings). Signed URLs with a size range; owner checks with 404 for others; httpOnly rotating refresh cookie with reuse detection; CSP and HSTS; least-privilege service accounts; no key files; Secret Manager. |
| **Error handling** | RFC 7807 errors with stable codes (`docs/04-API.md`). Specific camera error messages. Offline queue: backoff + jitter, connectivity probe, crash recovery, URL refresh. Retryable vs permanent failures; retry topics + DLT; attempt history; owner retry and admin reprocess; stale-upload reconciler; outbox for Kafka outages; automatic deployment rollback. |
| **Architecture and design decisions** | `docs/02-HLD.md` (diagrams and explained flows) and `docs/03-LLD.md` (ER diagram, concurrency controls). 6 ADRs in `docs/adr/`. Versioned event contracts in `docs/contracts/`. Trade-offs and production path in `README.md`. |
| **Deployment** | One-command local stack. Idempotent Terraform (`infra/terraform`) (VM, private bucket, IAM, secrets, firewall, IAP). `deploy.sh`: Cloud Build → Artifact Registry → VM, Let's Encrypt HTTPS, health-checked automatic rollback, `--rollback`. Observability in `docs/07-Observability.md` (Cloud Logging, Prometheus metrics, alert thresholds). |

## Measured numbers
| Metric | Value |
| --- | --- |
| Watermark a 60 s 720p clip | 6.2 s (laptop, 2 ffmpeg threads) |
| Watermark a 120 s 720p clip | 12.1 s |
| Size of a 2-minute clip at 2.5 Mbps | ~40 MB (limit 100 MB) |
| End to end, 20 s clip (local stack) | READY within ~4 s after `/complete` |
