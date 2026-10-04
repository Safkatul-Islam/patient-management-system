package org.pms.apigateway.support;

import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the system clock with one the tests control. */
@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfig {

  @Bean
  @Primary
  MutableClock testClock() {
    return new MutableClock(Instant.now());
  }
}
