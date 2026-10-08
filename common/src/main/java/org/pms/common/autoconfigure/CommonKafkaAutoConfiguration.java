package org.pms.common.autoconfigure;

import org.pms.common.messaging.CorrelationIdRecordInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.listener.RecordInterceptor;

/**
 * Registers the correlation-ID record interceptor in services that use Spring for Apache Kafka.
 * Boot's Kafka auto-configuration applies a single {@code RecordInterceptor<Object, Object>} bean
 * to the listener container factory, so consumers get it without wiring.
 */
@AutoConfiguration
@ConditionalOnClass(RecordInterceptor.class)
public class CommonKafkaAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(RecordInterceptor.class)
  CorrelationIdRecordInterceptor correlationIdRecordInterceptor() {
    return new CorrelationIdRecordInterceptor();
  }
}
