# ADR 0001: Modular monolith API + separate watermark microservice

**Status:** accepted

## Context
The brief asks for a backend API and a separate watermark service. Auth, video metadata and event publishing must be consistent with each other. ffmpeg is CPU- and memory-heavy.

## Options
1. Everything in one service.
2. Fine-grained microservices: auth, video, notification, watermark.
3. A modular monolith for user-facing work, plus one worker service.

## Decision
Option 3.
- **api-service** is package-by-feature (`auth`, `video`, `storage`, `outbox`) and owns PostgreSQL.
- **watermark-service** owns no database. It talks only through Kafka and GCS.

## Consequences
**Benefits**
- Video state and outbox rows commit in one ACID transaction.
- One deployable for the API, so operations stay cheap.
- An ffmpeg crash or memory spike can't take down login or listing.
- Workers scale on their own: add consumers or partitions.

**Costs and mitigations**
- The two services agree through event contracts, which must be versioned. These live in `docs/contracts` with a `*.v1` suffix.
- Modules inside the monolith must stay decoupled. Enforced by packages and ports, so a module can be extracted later.
