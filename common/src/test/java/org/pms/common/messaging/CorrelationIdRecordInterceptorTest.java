package org.pms.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pms.common.web.correlation.CorrelationIds;
import org.slf4j.MDC;

class CorrelationIdRecordInterceptorTest {

  private final CorrelationIdRecordInterceptor interceptor = new CorrelationIdRecordInterceptor();

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  private static ConsumerRecord<Object, Object> record(String correlationId) {
    ConsumerRecord<Object, Object> record =
        new ConsumerRecord<>("patient.created", 0, 0L, "k", "v");
    if (correlationId != null) {
      record.headers().add(CorrelationIds.HEADER, correlationId.getBytes(StandardCharsets.UTF_8));
    }
    return record;
  }

  @Test
  void wellFormedHeaderGoesIntoMdcAndRecordIsUnchanged() {
    ConsumerRecord<Object, Object> record = record("corr-123");

    ConsumerRecord<Object, Object> returned = interceptor.intercept(record, null);

    assertThat(returned).isSameAs(record);
    assertThat(MDC.get(CorrelationIds.MDC_KEY)).isEqualTo("corr-123");
  }

  @Test
  void missingHeaderGetsAGeneratedId() {
    interceptor.intercept(record(null), null);

    assertThat(UUID.fromString(MDC.get(CorrelationIds.MDC_KEY))).isNotNull();
  }

  @Test
  void malformedHeaderIsReplacedNotLogged() {
    interceptor.intercept(record("evil\r\nline"), null);

    String id = MDC.get(CorrelationIds.MDC_KEY);
    assertThat(id).doesNotContain("evil");
    assertThat(UUID.fromString(id)).isNotNull();
  }

  @Test
  void mdcIsClearedAfterEachRecordAndOnThreadCleanup() {
    interceptor.intercept(record("corr-1"), null);
    interceptor.afterRecord(record("corr-1"), null);
    assertThat(MDC.get(CorrelationIds.MDC_KEY)).isNull();

    interceptor.intercept(record("corr-2"), null);
    interceptor.clearThreadState(null);
    assertThat(MDC.get(CorrelationIds.MDC_KEY)).isNull();
  }
}
