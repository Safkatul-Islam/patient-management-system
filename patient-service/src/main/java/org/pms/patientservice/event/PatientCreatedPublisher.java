package org.pms.patientservice.event;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.pms.common.web.correlation.CorrelationIds;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes {@code patient.created} once the transaction that created the patient has committed, so
 * a rolled-back create never produces an event.
 *
 * <p>The patient is already committed when this runs, so a failed publish cannot undo it and must
 * not fail the request either: every failure, whether {@code send()} throws or the broker never
 * acknowledges, is logged once at ERROR with identifiers only and counted in {@value
 * #FAILURE_COUNTER}. The event is then lost (an accepted V1 limitation; see PIPELINE.md). The
 * request thread waits at most the producer's {@code max.block.ms}, never for the acknowledgement.
 */
@Slf4j
@Component
public class PatientCreatedPublisher {

  static final String FAILURE_COUNTER = "pms.events.publish.failures";

  private final KafkaTemplate<String, String> kafkaTemplate;
  private final JsonMapper jsonMapper;
  private final String topic;
  private final Counter publishFailures;

  public PatientCreatedPublisher(
      KafkaTemplate<String, String> kafkaTemplate,
      JsonMapper jsonMapper,
      PatientCreatedTopicProperties topicProperties,
      MeterRegistry meterRegistry) {
    this.kafkaTemplate = kafkaTemplate;
    this.jsonMapper = jsonMapper;
    this.topic = topicProperties.topic();
    this.publishFailures =
        Counter.builder(FAILURE_COUNTER)
            .description("Domain events that could not be published to Kafka")
            .tag("eventType", PatientCreatedV1.EVENT_TYPE)
            .register(meterRegistry);
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void publish(PatientCreated event) {
    try {
      kafkaTemplate
          .send(toRecord(event))
          .whenComplete(
              (result, failure) -> {
                if (failure != null) {
                  recordFailure(event, failure);
                } else {
                  log.debug(
                      "Published patient.created eventId={} patientId={}",
                      event.eventId(),
                      event.patientId());
                }
              });
    } catch (Exception failure) {
      // Building the record or send() itself threw. Never rethrown: the framework would only log
      // it, without our counter and with the full exception message.
      recordFailure(event, failure);
    }
  }

  private ProducerRecord<String, String> toRecord(PatientCreated event) {
    String payload = jsonMapper.writeValueAsString(PatientCreatedV1.from(event));
    RecordHeader correlationId =
        new RecordHeader(
            CorrelationIds.HEADER, event.correlationId().getBytes(StandardCharsets.UTF_8));
    return new ProducerRecord<>(
        topic, null, event.patientId().toString(), payload, List.of(correlationId));
  }

  /**
   * Logs exception types only: Kafka exception messages can be long and opaque, and the payload
   * must never be logged from here.
   */
  private void recordFailure(PatientCreated event, Throwable failure) {
    publishFailures.increment();
    log.error(
        "Failed to publish patient.created eventId={} patientId={} errorType={} causeType={}",
        event.eventId(),
        event.patientId(),
        failure.getClass().getName(),
        rootCause(failure).getClass().getName());
  }

  private static Throwable rootCause(Throwable failure) {
    Throwable cause = failure;
    while (cause.getCause() != null && cause.getCause() != cause) {
      cause = cause.getCause();
    }
    return cause;
  }
}
