package com.vms.watermark.application;

import java.util.UUID;

/** What to process. Object keys are decided by the API; the worker never derives them. */
public record JobRequest(
    UUID videoId, int run, String rawObjectKey, String finalObjectKey, String contentType) {}
