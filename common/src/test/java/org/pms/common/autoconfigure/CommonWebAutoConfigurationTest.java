package org.pms.common.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;

class CommonWebAutoConfigurationTest {

  private static final AutoConfigurations COMMON_WEB =
      AutoConfigurations.of(CommonWebAutoConfiguration.class);

  @Test
  void registersCorrelationFilterFirstInServletApps() {
    new WebApplicationContextRunner()
        .withConfiguration(COMMON_WEB)
        .run(
            context -> {
              FilterRegistrationBean<?> registration =
                  context.getBean("correlationIdFilter", FilterRegistrationBean.class);
              assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
            });
  }

  @Test
  void staysInertOutsideServletApps() {
    new ApplicationContextRunner()
        .withConfiguration(COMMON_WEB)
        .run(context -> assertThat(context).doesNotHaveBean("correlationIdFilter"));
  }
}
