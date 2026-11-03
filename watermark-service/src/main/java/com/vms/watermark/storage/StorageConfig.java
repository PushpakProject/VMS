package com.vms.watermark.storage;

import com.google.cloud.storage.StorageOptions;
import java.nio.file.Path;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StorageConfig {

  @Bean
  @ConditionalOnProperty(name = "vms.storage.provider", havingValue = "gcs")
  ObjectStoragePort gcsStorage(StorageProperties properties) {
    if (properties.bucket() == null || properties.bucket().isBlank()) {
      throw new IllegalStateException("vms.storage.bucket is required for the gcs provider");
    }
    return new GcsObjectStorageAdapter(
        StorageOptions.getDefaultInstance().getService(), properties.bucket());
  }

  @Bean
  @ConditionalOnProperty(name = "vms.storage.provider", havingValue = "local")
  ObjectStoragePort localStorage(StorageProperties properties) {
    return new LocalObjectStorageAdapter(Path.of(properties.localDir()));
  }

  @ConfigurationProperties("vms.storage")
  public record StorageProperties(String provider, String bucket, String localDir) {}
}
