package org.pms.notificationservice.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Single time source for recorded timestamps, replaceable in tests. */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
