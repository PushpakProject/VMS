package com.vms.watermark;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class WatermarkServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(WatermarkServiceApplication.class, args);
  }
}
