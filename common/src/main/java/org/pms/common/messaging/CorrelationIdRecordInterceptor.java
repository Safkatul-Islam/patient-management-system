package org.pms.common.messaging;

import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.pms.common.web.correlation.CorrelationIds;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;

/**
 * Kafka counterpart of the HTTP correlation filters: puts the {@value CorrelationIds#HEADER}
 * message header into the MDC under {@value CorrelationIds#MDC_KEY} while a record is processed, so
 * a consumer's log lines carry the ID of the request that produced the event (the MDC itself does
 * not survive the hop to the consumer's poll thread). The header is validated like an HTTP header:
 * a missing or malformed value is replaced with a generated ID, never logged as-is.
 *
 * <p>The MDC entry is cleared after each record. Error handlers that run after that (retries,
 * dead-lettering) should read the header from the record they are given.
 */
public class CorrelationIdRecordInterceptor implements RecordInterceptor<Object, Object> {

  @Override
  public ConsumerRecord<Object, Object> intercept(
      ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
    MDC.put(CorrelationIds.MDC_KEY, CorrelationIds.resolve(headerValue(record)));
    return record;
  }

  @Override
  public void afterRecord(
      ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
    MDC.remove(CorrelationIds.MDC_KEY);
  }

  @Override
  public void clearThreadState(Consumer<?, ?> consumer) {
    MDC.remove(CorrelationIds.MDC_KEY);
  }

  private static String headerValue(ConsumerRecord<Object, Object> record) {
    Header header = record.headers().lastHeader(CorrelationIds.HEADER);
    if (header == null || header.value() == null) {
      return null;
    }
    return new String(header.value(), StandardCharsets.UTF_8);
  }
}
