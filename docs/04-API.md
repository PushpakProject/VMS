# 04 · REST API (v1)

All endpoints are JSON. Errors are RFC 7807: `{type, title, status, detail, code, requestId, errors?}`. Every response carries an `X-Request-Id` header.

## Auth
| Method | Path | Auth | Notes |
| --- | --- | --- | --- |
| POST | `/api/v1/auth/login` | – | `{login, password}` → `{accessToken, expiresIn, user}` + `vms_rt` cookie (httpOnly, Secure, SameSite=Strict, path `/api/v1/auth`) |
| POST | `/api/v1/auth/google` | – | `{idToken}` (Google Identity Services) → same as login |
| POST | `/api/v1/auth/register` | – | `{email, username, displayName, password ≥ 12}` → 201 |
| POST | `/api/v1/auth/refresh` | cookie + `X-Requested-With: XMLHttpRequest` | Rotates the refresh token; reuse revokes the family |
| POST | `/api/v1/auth/logout` | cookie + header | 204, revokes the family, clears the cookie |
| GET | `/api/v1/auth/me` | Bearer | Current user |

## Public
| Method | Path | Notes |
| --- | --- | --- |
| GET | `/api/v1/config/public` | `{maxDurationSec, warnAtSec, maxUploadBytes, allowedContentTypes, videoBitsPerSecond, videoWidth, videoHeight, googleClientId}` |
| GET | `/api/v1/ping` | 204, `Cache-Control: no-store` (connectivity probe) |

## Videos (Bearer; owner-scoped, admins see all)
| Method | Path | Notes |
| --- | --- | --- |
| POST | `/api/v1/videos` | Header `Idempotency-Key: <uuid>`; `{trackingId, contentType, sizeBytes, recordedAt?}` → 201 `{videoId, status, upload: {protocol, url, method, headers, expiresAt, chunkSizeBytes}}`. With `protocol: RESUMABLE` (the default), `url` is a storage session: send `PUT` with `Content-Range: bytes */TOTAL` to get the committed bytes (`308` + `Range`), then `PUT` the pieces; `200` means complete, `404` means request a new session. With `SIMPLE`, send one `PUT` with `headers`. A repeat call returns the same video with a fresh URL. 413 if too large, 415 if the type isn't allowed. |
| POST | `/api/v1/videos/{id}/complete` | `{durationMs}` → 202 video. 409 `UPLOAD_NOT_FOUND` if nothing is in storage. An oversize or too-long upload returns status `FAILED` with `failureCode`. |
| GET | `/api/v1/videos?page&size(≤100)&trackingId&status` | Paged `{content, page, size, totalElements, totalPages}`, newest first |
| GET | `/api/v1/videos/{id}` | Video + `attempts[]` (run, attempt, status, errorCode, errorMessage, timestamps) |
| GET | `/api/v1/videos/{id}/playback-url` | READY only → `{url, expiresAt}` (5 min). Otherwise 409 `NOT_READY`. |
| POST | `/api/v1/videos/{id}/retry` | Owner, FAILED with a retryable code, raw still present → 202. 409 `NOT_RETRYABLE` / `RAW_NOT_AVAILABLE`. |
| POST | `/api/v1/admin/videos/{id}/reprocess` | ADMIN, any FAILED with raw present |
| DELETE | `/api/v1/videos/{id}` | Soft delete + object deletion → 204 |

Other users' videos return **404** (no existence leak).

## Error codes
`VALIDATION_FAILED`, `MALFORMED_REQUEST`, `INVALID_CREDENTIALS`, `INVALID_REFRESH_TOKEN`, `UNAUTHENTICATED`, `FORBIDDEN`, `NOT_FOUND`, `ACCOUNT_EXISTS`, `INVALID_STATE`, `DUPLICATE_REQUEST`, `UPLOAD_NOT_FOUND`, `NOT_READY`, `RAW_NOT_AVAILABLE`, `NOT_RETRYABLE`, `PAYLOAD_TOO_LARGE`, `UNSUPPORTED_MEDIA_TYPE`, `STORAGE_UNAVAILABLE`, `INTERNAL_ERROR`.

## Failure codes on videos
| Code | Retryable by owner | Meaning |
| --- | --- | --- |
| `TOO_LARGE` | no | Over 100 MB |
| `TOO_LONG` | no | Over 130 s |
| `CORRUPT_INPUT` | no | ffprobe can't read a video stream |
| `UNSUPPORTED_FORMAT` | no | Stored type doesn't match the declared one |
| `PROCESSING_FAILED` | yes | Transient failures exhausted the retry topics |
