package org.pms.common.autoconfigure;

import io.micrometer.context.ContextRegistry;
import org.pms.common.web.correlation.CorrelationIdWebFilter;
import org.pms.common.web.correlation.MdcCorrelationIdAccessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the shared reactive conventions (the API gateway). Inert in servlet applications, which
 * get {@link CommonWebAutoConfiguration} instead.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class CommonReactiveWebAutoConfiguration {

  @Bean
  CorrelationIdWebFilter correlationIdWebFilter() {
    return new CorrelationIdWebFilter();
  }

  /**
   * Makes the correlation ID that {@link CorrelationIdWebFilter} puts in the Reactor context
   * visible in the MDC. Takes effect together with {@code spring.reactor.context-propagation=auto}.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(ContextRegistry.class)
  static class MdcPropagationConfiguration {

    @Bean
    MdcCorrelationIdAccessor mdcCorrelationIdAccessor() {
      MdcCorrelationIdAccessor accessor = new MdcCorrelationIdAccessor();
      ContextRegistry.getInstance().registerThreadLocalAccessor(accessor);
      return accessor;
    }
  }
}
