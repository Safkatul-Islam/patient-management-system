package org.pms.apigateway.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Single time source for token expiry checks and the JWKS refetch cooldown. */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
