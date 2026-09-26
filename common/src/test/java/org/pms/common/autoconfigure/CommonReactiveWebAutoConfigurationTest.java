package org.pms.common.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.context.ContextRegistry;
import org.junit.jupiter.api.Test;
import org.pms.common.web.correlation.CorrelationIdWebFilter;
import org.pms.common.web.correlation.CorrelationIds;
import org.pms.common.web.correlation.MdcCorrelationIdAccessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class CommonReactiveWebAutoConfigurationTest {

  private static final AutoConfigurations COMMON =
      AutoConfigurations.of(
          CommonWebAutoConfiguration.class, CommonReactiveWebAutoConfiguration.class);

  @Test
  void reactiveAppsGetTheWebFilterAndMdcAccessorButNoServletFilter() {
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(COMMON)
        .run(
            context -> {
              assertThat(context).hasSingleBean(CorrelationIdWebFilter.class);
              assertThat(context).hasSingleBean(MdcCorrelationIdAccessor.class);
              assertThat(context).doesNotHaveBean("correlationIdFilter");
              assertThat(ContextRegistry.getInstance().getThreadLocalAccessors())
                  .anyMatch(accessor -> CorrelationIds.MDC_KEY.equals(accessor.key()));
            });
  }

  @Test
  void servletAppsGetNoReactivePieces() {
    new WebApplicationContextRunner()
        .withConfiguration(COMMON)
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(CorrelationIdWebFilter.class);
              assertThat(context).doesNotHaveBean(MdcCorrelationIdAccessor.class);
              assertThat(context).hasBean("correlationIdFilter");
            });
  }
}
