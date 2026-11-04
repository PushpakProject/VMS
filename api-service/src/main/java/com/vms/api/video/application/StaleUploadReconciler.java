package com.vms.api.video.application;

import com.vms.api.common.observability.VmsMetrics;
import com.vms.api.video.application.VideoService.ReconcileOutcome;
import com.vms.api.video.domain.VideoStatus;
import com.vms.api.video.infrastructure.VideoRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically looks at videos stuck in UPLOADING. Each video is reconciled in its own transaction
 * so one bad row never blocks the rest.
 */
@Component
public class StaleUploadReconciler {

  private static final Logger log = LoggerFactory.getLogger(StaleUploadReconciler.class);
  private static final int BATCH = 100;

  private final VideoRepository videos;
  private final VideoService videoService;
  private final ReconcilerProperties properties;
  private final VmsMetrics metrics;
  private final Clock clock;

  public StaleUploadReconciler(
      VideoRepository videos,
      VideoService videoService,
      ReconcilerProperties properties,
      VmsMetrics metrics,
      Clock clock) {
    this.videos = videos;
    this.videoService = videoService;
    this.properties = properties;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Scheduled(
      fixedDelayString = "${vms.reconciler.interval:PT5M}",
      initialDelayString = "${vms.reconciler.initial-delay:PT1M}")
  public Map<ReconcileOutcome, Integer> run() {
    Map<ReconcileOutcome, Integer> counts = new EnumMap<>(ReconcileOutcome.class);
    var ids =
        videos.findIdsByStatusCreatedBefore(
            VideoStatus.UPLOADING, clock.instant().minus(properties.staleAfter()), Limit.of(BATCH));
    for (UUID id : ids) {
      try {
        ReconcileOutcome outcome = videoService.reconcile(id, properties.abandonAfter());
        counts.merge(outcome, 1, Integer::sum);
        if (outcome != ReconcileOutcome.SKIPPED && outcome != ReconcileOutcome.WAITING) {
          metrics.reconciled(outcome.name());
        }
      } catch (RuntimeException ex) {
        log.warn("Reconcile failed for video {}: {}", id, ex.getClass().getSimpleName());
      }
    }
    if (counts.containsKey(ReconcileOutcome.RECOVERED)
        || counts.containsKey(ReconcileOutcome.ABANDONED)
        || counts.containsKey(ReconcileOutcome.REJECTED)) {
      log.info("Stale upload reconciliation: {}", counts);
    }
    return counts;
  }

  @ConfigurationProperties("vms.reconciler")
  public record ReconcilerProperties(Duration staleAfter, Duration abandonAfter) {

    public ReconcilerProperties {
      staleAfter = staleAfter == null ? Duration.ofMinutes(30) : staleAfter;
      abandonAfter = abandonAfter == null ? Duration.ofDays(7) : abandonAfter;
    }
  }
}
