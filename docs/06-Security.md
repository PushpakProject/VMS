# 06 · Security

| Area | Control |
| --- | --- |
| Transport | TLS 1.2/1.3 (Let's Encrypt), HSTS, HTTP→HTTPS redirect. The camera needs a secure origin, and so does Google Sign-In (it rejects raw IPs), hence `<ip>.sslip.io`. |
| Browser hardening | CSP (`script-src 'self' accounts.google.com/gsi/client`, `connect-src 'self' storage.googleapis.com`, `frame-ancestors 'none'`, `object-src 'none'`), `X-Content-Type-Options`, `X-Frame-Options: DENY`, `Referrer-Policy`, `Permissions-Policy: camera=(self) microphone=(self)` |
| Passwords | BCrypt (cost 12). Lock for 15 min after 5 failures. The same generic error for unknown user, wrong password and locked account. A dummy hash check for unknown users equalises response timing. |
| Tokens | Access JWT: HS256, 15 min, in memory only, issuer validated. Refresh token: 32 random bytes in an httpOnly / Secure / SameSite=Strict cookie scoped to `/api/v1/auth`, stored as SHA-256, rotated on every use; reuse outside a 10 s grace window revokes the whole family. |
| Google SSO | The ID token is verified on the server against Google's JWKS: signature, `iss`, `aud` = our client ID, `exp`, `email_verified`. It's open to any verified Google account: a new account always gets the **USER** role, never ADMIN, and an existing account is linked by verified email. It's covered by `GoogleIdTokenVerifierAdapterTest` (aud, iss, expiry, unverified email, tampered signature) and `GoogleAuthenticationStrategyTest`. An allow-list can be added in `GoogleAuthenticationStrategy` without UI changes. |
| CSRF | State-changing calls need the `Authorization` header, which browsers never attach to cross-site requests. The cookie-based `refresh`/`logout` endpoints also require `X-Requested-With` (which forces a CORS preflight) and an allowed Origin. Spring CSRF tokens are therefore disabled on purpose. |
| Authorization | Every video query is owner-scoped (admins excepted). Other users' videos return 404. `/api/v1/admin/**` needs `ROLE_ADMIN`. |
| Input validation | Bean Validation on all DTOs. Tracking ID regex. Content-type allow-list. Size and duration are checked before signing, enforced in the signature, and re-checked against the real object. Paging is capped at 100. |
| Object storage | Private bucket, uniform bucket-level access, public access prevention. Keys are built on the server (no path traversal). Upload URL: 15 min, PUT only, bound to content type and `x-goog-content-length-range`. Playback URL: 5 min, READY only. Unwatermarked raw files are never readable. |
| Command execution | ffmpeg/ffprobe run from an argument list (no shell), fixed binary paths, timeout + `destroyForcibly`, per-job temp dir deleted in `finally`, non-root container user (uid 10001). |
| Secrets | None in the repo (`.env.example` holds placeholders only). Production values come from Secret Manager into a root-only file on the VM. The VM service account signs through IAM signBlob, so no key files exist. |
| Infrastructure | Only 80/443 are public. SSH only through IAP. Postgres, Kafka and Actuator stay on the internal Docker network (nginx returns 404 for `/actuator`). Rate limits: 5 r/s on auth, 30 r/s on the API. |
| Errors and logs | RFC 7807 with stable codes. No stack traces or exception messages reach clients. Structured logs carry a correlation ID; no tokens, passwords, signed URLs or emails. |
| Dependencies | Exact versions, each at least 7 days old. Spring Boot 4.1 (3.5 left free support). `npm audit --omit=dev`: 0 vulnerabilities. The moderate `minimatch` advisory affects dev-only lint tooling, with no fix available yet. |
| Local storage emulator | Active only with `vms.storage.provider=local`. HMAC-SHA256 over method, key, expiry, content type and max size; constant-time comparison; path-traversal guard. |

## Known limitations
- IndexedDB recordings are not encrypted at rest on the device (mitigated by per-user filtering and deletion once READY).
- A single VM with a single Kafka broker is a single point of failure.
