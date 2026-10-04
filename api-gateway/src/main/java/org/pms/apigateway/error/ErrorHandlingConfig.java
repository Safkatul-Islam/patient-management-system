package org.pms.apigateway.error;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
public class ErrorHandlingConfig {

  @Bean
  ProblemResponseWriter problemResponseWriter(JsonMapper jsonMapper) {
    return new ProblemResponseWriter(jsonMapper);
  }

  @Bean
  GatewayErrorWebExceptionHandler gatewayErrorWebExceptionHandler(ProblemResponseWriter writer) {
    return new GatewayErrorWebExceptionHandler(writer);
  }

  /** Picked up by Spring Security's WebFilterChainProxy in place of its bare-400 default. */
  @Bean
  FirewallRejectionHandler firewallRejectionHandler(ProblemResponseWriter writer) {
    return new FirewallRejectionHandler(writer);
  }
}
