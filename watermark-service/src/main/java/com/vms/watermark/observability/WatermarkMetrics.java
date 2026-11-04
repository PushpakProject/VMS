package com.vms.watermark.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Worker metrics at {@code /actuator/prometheus}. Kafka consumer metrics (including lag) are
 * registered automatically by Spring Kafka's Micrometer integration.
 */
@Component
public class WatermarkMetrics {

  private final MeterRegistry registry;
  private final Timer duration;

  public WatermarkMetrics(MeterRegistry registry) {
    this.registry = registry;
    this.duration =
        Timer.builder("vms.watermark.duration")
            .description("Time to watermark one video, end to end")
            .publishPercentiles(0.5, 0.95)
            .register(registry);
  }

  public void completed(Duration elapsed, boolean alreadyDone) {
    duration.record(elapsed);
    outcome(alreadyDone ? "DUPLICATE" : "COMPLETED", "NONE");
  }

  public void retrying(String code) {
    outcome("RETRYING", code);
  }

  public void failed(String code) {
    outcome("FAILED", code);
  }

  public void deadLettered() {
    registry.counter("vms.watermark.dlt").increment();
  }

  private void outcome(String result, String code) {
    registry.counter("vms.watermark.outcome", "result", result, "code", code).increment();
  }
}
