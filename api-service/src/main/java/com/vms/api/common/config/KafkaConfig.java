package com.vms.api.common.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;
import tools.jackson.core.JacksonException;

@Configuration
public class KafkaConfig {

  @Bean
  NewTopic videoUploadedTopic(KafkaTopicsProperties topics) {
    return new NewTopic(
        topics.videoUploadedTopic(), topics.partitions(), topics.replicationFactor());
  }

  @Bean
  NewTopic watermarkResultTopic(KafkaTopicsProperties topics) {
    return new NewTopic(
        topics.watermarkResultTopic(), topics.partitions(), topics.replicationFactor());
  }

  @Bean
  NewTopic watermarkResultDltTopic(KafkaTopicsProperties topics) {
    return new NewTopic(
        topics.watermarkResultDltTopic(), topics.partitions(), topics.replicationFactor());
  }

  /**
   * Result events are retried in place (short backoff, e.g. optimistic-lock conflicts or a DB
   * blip), then parked on a DLT so a poison message never blocks the partition.
   */
  @Bean
  DefaultErrorHandler kafkaErrorHandler(
      KafkaOperations<String, String> template, KafkaTopicsProperties topics) {
    DeadLetterPublishingRecoverer recoverer =
        new DeadLetterPublishingRecoverer(
            template,
            (record, ex) ->
                new org.apache.kafka.common.TopicPartition(
                    topics.watermarkResultDltTopic(), record.partition()));
    ExponentialBackOff backOff = new ExponentialBackOff(500, 2.0);
    backOff.setMaxElapsedTime(15_000);
    DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
    handler.addNotRetryableExceptions(JacksonException.class, IllegalArgumentException.class);
    return handler;
  }
}
