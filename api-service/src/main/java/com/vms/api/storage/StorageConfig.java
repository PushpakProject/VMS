package com.vms.api.storage;

import com.google.cloud.storage.StorageOptions;
import com.vms.api.auth.infrastructure.AuthProperties;
import com.vms.api.storage.gcs.GcsStorageAdapter;
import com.vms.api.storage.gcs.ResumableSessionStarter;
import com.vms.api.storage.local.LocalStorageAdapter;
import com.vms.api.storage.local.LocalUrlSigner;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Chooses the storage adapter (Strategy) from configuration. */
@Configuration
public class StorageConfig {

  @Bean
  @ConditionalOnProperty(name = "vms.storage.provider", havingValue = "gcs")
  StoragePort gcsStorage(StorageProperties properties, AuthProperties auth, Clock clock) {
    if (properties.bucket() == null || properties.bucket().isBlank()) {
      throw new IllegalStateException("vms.storage.bucket is required for the gcs provider");
    }
    String browserOrigin =
        auth.corsAllowedOrigins().isEmpty() ? null : auth.corsAllowedOrigins().get(0);
    return new GcsStorageAdapter(
        StorageOptions.getDefaultInstance().getService(),
        properties.bucket(),
        clock,
        ResumableSessionStarter.http(),
        browserOrigin);
  }

  @Bean
  @ConditionalOnProperty(name = "vms.storage.provider", havingValue = "local")
  LocalStorageAdapter localStorage(StorageProperties properties, Clock clock) {
    return new LocalStorageAdapter(
        Path.of(properties.localDir()), new LocalUrlSigner(properties.localSigningKey()), clock);
  }
}
