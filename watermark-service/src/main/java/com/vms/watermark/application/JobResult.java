package com.vms.watermark.application;

public record JobResult(
    String finalObjectKey, long finalSizeBytes, Integer durationMs, boolean alreadyDone) {}
