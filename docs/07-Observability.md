# 07 · Observability

## Signals
| Signal | Implementation |
| --- | --- |
| Logs | Structured JSON (ECS) in prod with `correlationId`. It comes from `X-Request-Id`, is stored in the outbox row, travels as the Kafka header `x-correlation-id`, and is put in the worker's MDC, so one search follows a video from HTTP through Kafka to ffmpeg. Docker's `gcplogs` driver sends logs to **Cloud Logging**. No tokens, passwords, signed URLs or emails are logged. |
| Health | `/actuator/health/liveness` and `/readiness`. The watermark readiness includes the `ffmpeg` indicator (binaries and logo present). Used by the compose health checks and by `remote-deploy.sh`'s automatic rollback. |
| Metrics | Micrometer at `/actuator/prometheus` on both services. **Internal only:** nginx returns 404 for `/actuator/**` and the service ports aren't published. |

## Key metrics
| Metric | Type | Service | Meaning / alert idea |
| --- | --- | --- | --- |
| `vms_uploads_initiated_total` | counter | api | Signed upload URLs issued |
| `vms_uploads_completed_total` | counter | api | Uploads verified in storage |
| `vms_uploads_rejected_total{code}` | counter | api | `TOO_LARGE`, `TOO_LONG`, `UNSUPPORTED_FORMAT` |
| `vms_videos_ready_total` / `vms_videos_failed_total{code}` | counter | api | End-to-end outcome |
| `vms_outbox_pending` | gauge | api | **Alert if > 50 for 5 min:** Kafka unreachable or relay stuck |
| `vms_reconciler_videos_total{outcome}` | counter | api | RECOVERED / ABANDONED / REJECTED by the stale-upload reconciler |
| `vms_watermark_duration_seconds` (p50, p95) | timer | watermark | **Alert if p95 > 90 s** |
| `vms_watermark_outcome_total{result,code}` | counter | watermark | COMPLETED / DUPLICATE / RETRYING / FAILED |
| `vms_watermark_dlt_total` | counter | watermark | **Alert if > 0:** messages need an operator |
| `kafka_consumer_fetch_manager_records_lag_max` | gauge | watermark | **Alert if > 20:** add workers or partitions |
| `http_server_requests_seconds{uri,status}` | timer | both | Latency and error rate per endpoint |

## Reading metrics
```bash
# local
docker compose -f infra/docker-compose.yml --env-file infra/.env exec api \
  curl -s localhost:8080/actuator/prometheus | grep '^vms_'
docker compose -f infra/docker-compose.yml --env-file infra/.env exec watermark \
  curl -s localhost:8081/actuator/prometheus | grep -E '^vms_|records_lag_max'
# on the VM: the same commands with --env-file /opt/vms/.env -f /opt/vms/docker-compose.yml
```

## Useful Cloud Logging queries
| Purpose | Query |
| --- | --- |
| Everything for one request/video | `jsonPayload.correlationId="<id>"` |
| Watermark failures | `jsonPayload.container.name:"watermark" AND jsonPayload.message:"failed"` |
| Parked messages | `jsonPayload.message:"Message parked on"` |
| Refresh-token reuse | `jsonPayload.message:"reuse detected"` |

## Next steps
A Prometheus + Grafana compose profile that scrapes both services, plus the four alerts above.
