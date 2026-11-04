# 08 · Security review (OWASP Top 10 pass)

- **Scope:** api-service, watermark-service, frontend, infra.
- **Method:** checklist review, plus secret scans and dangerous-API scans over all source (excluding `node_modules`, `target`, `dist` and the gitignored `infra/.env`).

## Automated scans
| Check | Result |
| --- | --- |
| Hardcoded secrets (`password/secret/token/api_key = "…"`, AWS keys, private keys, bearer tokens) | **None found** |
| Dangerous APIs (`innerHTML`, `dangerouslySetInnerHTML`, `eval`, `new Function`, `Runtime.exec`, `ObjectInputStream`, shell `-c`) | **None.** One native SQL query (outbox `SKIP LOCKED`) uses a bound parameter only |
| Sensitive data in logs (email, password, token, URL) | **None.** Logs use account ids |
| `npm audit --omit=dev` (runtime dependencies) | **0 vulnerabilities** |

## Checklist
| OWASP | Status | Evidence |
| --- | --- | --- |
| A01 Broken access control | ✅ | Every video query is owner-scoped (`VideoService.loadAccessible`); 404 for others; `/api/v1/admin/**` requires the ADMIN role; explicit CORS allow-list (no wildcard). Tests: `VideoServiceTest`, `VideoControllerSecurityTest`, `ApiIntegrationTest` |
| A02 Cryptographic failures | ✅ | TLS + HSTS; BCrypt(12); HS256 key ≥ 256 bit checked at startup; refresh tokens stored as SHA-256; signed URLs (V4 / HMAC-SHA256 locally, constant-time compare) |
| A03 Injection / XSS | ✅ | JPA parameters / Specifications only; ffmpeg argument list (no shell); React escaping + CSP; Tracking ID and filters regex-validated |
| A04 Insecure design | ✅ | Upload size enforced in the signature **and** re-checked; playback only for watermarked videos; state machine rejects illegal transitions |
| A05 Misconfiguration | ✅ | Separate profiles; no stack traces or messages in responses; Actuator internal only (nginx 404); prod self-registration off by default |
| A06 Vulnerable components | ✅ / ⚠️ | Spring Boot 4.1 (3.5 is past free support); exact pinned versions; dev-only moderate `minimatch` advisory in lint tooling. Java dependencies weren't scanned with OWASP Dependency-Check (needs an NVD API key). Recommended: Dependabot / OSV in CI |
| A07 Authentication failures | ✅ | Lockout after 5 failures; same generic error for every failure; timing equalisation for unknown users; refresh rotation + reuse detection; nginx auth rate limit 5 r/s |
| A08 Integrity / deserialization | ✅ | Jackson into concrete records (no polymorphic typing); event payloads validated before use; images tagged with the git SHA |
| A09 Logging and monitoring | ✅ (fixed in this review) | Auth audit log (`vms.audit.auth`: success, failure, lockout, refresh reuse, by account id); metrics and alert thresholds in `07-Observability.md` |
| A10 SSRF | ✅ | The server never fetches user-supplied URLs; storage keys are built on the server; Google keys come from a fixed JWKS URL |

## Findings and actions
| # | Severity | Finding | Action |
| --- | --- | --- | --- |
| 1 | Medium | No audit trail for authentication events (A09) | **Fixed:** `PasswordAuthenticationStrategy` logs success, failure and lockout by account id (never the login name) |
| 2 | Low | Public self-registration on the reviewer deployment widens the attack surface | **Fixed:** `vms.auth.registration-enabled` is false in prod by default (`VMS_REGISTRATION_ENABLED=true` to open it) |
| 3 | Low | `/actuator/prometheus` is unauthenticated inside the Docker network | Accepted: not routed by nginx, ports not published. Future: separate management port |
| 4 | Low | Kafka uses PLAINTEXT on the internal Docker network | Accepted for a single VM. Production: Managed Kafka with TLS + SASL |
| 5 | Low | Recordings and a 15-minute signed upload URL sit unencrypted in IndexedDB on the device | Accepted and documented. Jobs are filtered per user and deleted once READY. Future: WebCrypto per-user keys |
| 6 | Info | Google accounts are linked to existing accounts by verified email | Standard practice; requires `email_verified=true` from Google |
| 7 | Info | Java dependency CVE scan not automated | Recommend adding OSV-Scanner / Dependabot to CI |

**No Critical or High findings.**
