package org.pms.common.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.pms.common.messaging.CorrelationIdRecordInterceptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.listener.RecordInterceptor;

class CommonKafkaAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(CommonKafkaAutoConfiguration.class));

  @Test
  void registersTheCorrelationInterceptorWhenSpringKafkaIsPresent() {
    runner.run(context -> assertThat(context).hasSingleBean(CorrelationIdRecordInterceptor.class));
  }

  @Test
  void backsOffWhenTheServiceDefinesItsOwnInterceptor() {
    runner
        .withBean(
            "custom",
            RecordInterceptor.class,
            () -> (RecordInterceptor<Object, Object>) (r, c) -> r)
        .run(context -> assertThat(context).doesNotHaveBean(CorrelationIdRecordInterceptor.class));
  }

  @Test
  void staysInertWithoutSpringKafka() {
    runner
        .withClassLoader(new FilteredClassLoader(RecordInterceptor.class))
        .run(context -> assertThat(context).doesNotHaveBean(CorrelationIdRecordInterceptor.class));
  }
}
