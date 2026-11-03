package com.vms.watermark.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("vms.kafka")
public record KafkaTopicsProperties(
    @NotBlank String videoUploadedTopic, @NotBlank String watermarkResultTopic) {}
