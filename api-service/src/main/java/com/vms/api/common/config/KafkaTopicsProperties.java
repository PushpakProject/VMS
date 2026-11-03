package com.vms.api.common.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("vms.kafka")
public record KafkaTopicsProperties(
    @NotBlank String videoUploadedTopic,
    @NotBlank String watermarkResultTopic,
    @Min(1) int partitions,
    @Min(1) short replicationFactor) {

  public String watermarkResultDltTopic() {
    return watermarkResultTopic + "-dlt";
  }
}
