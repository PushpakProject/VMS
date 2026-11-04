package com.vms.api.common.observability;

import com.vms.api.video.domain.FailureCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Business metrics, exposed at {@code /actuator/prometheus} (internal network only). Names follow
 * Micrometer conventions; Prometheus renders them as {@code vms_uploads_initiated_total} etc.
 */
@Component
public class VmsMetrics {

  private final MeterRegistry registry;
  private final Counter uploadsInitiated;
  private final Counter uploadsCompleted;
  private final Counter videosReady;

  public VmsMetrics(MeterRegistry registry) {
    this.registry = registry;
    this.uploadsInitiated =
        Counter.builder("vms.uploads.initiated")
            .description("Upload URLs issued")
            .register(registry);
    this.uploadsCompleted =
        Counter.builder("vms.uploads.completed")
            .description("Uploads verified in storage")
            .register(registry);
    this.videosReady =
        Counter.builder("vms.videos.ready").description("Videos watermarked").register(registry);
  }

  public void uploadInitiated() {
    uploadsInitiated.increment();
  }

  public void uploadCompleted() {
    uploadsCompleted.increment();
  }

  public void uploadRejected(FailureCode code) {
    registry.counter("vms.uploads.rejected", "code", code.name()).increment();
  }

  public void videoReady() {
    videosReady.increment();
  }

  public void videoFailed(FailureCode code) {
    registry.counter("vms.videos.failed", "code", code.name()).increment();
  }

  public void reconciled(String outcome) {
    registry.counter("vms.reconciler.videos", "outcome", outcome).increment();
  }
}
