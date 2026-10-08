package org.pms.notificationservice.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.pms.notificationservice.service.PatientCreatedProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code patient.created}. Thin on purpose: read the contract, hand off to the processor.
 * Anything thrown here goes to the container's error handler (retry, then dead-letter); the offset
 * is committed only after this method returns.
 *
 * <p>The correlation ID of the originating request is in the MDC while this runs, put there by the
 * common module's record interceptor from the {@code X-Correlation-Id} header.
 */
@Component
public class PatientCreatedListener {

  /** The listener container's ID, for looking it up in the endpoint registry. */
  public static final String ID = "patient-created";

  private static final Logger log = LoggerFactory.getLogger(PatientCreatedListener.class);

  private final PatientCreatedEventReader reader;
  private final PatientCreatedProcessor processor;

  public PatientCreatedListener(
      PatientCreatedEventReader reader, PatientCreatedProcessor processor) {
    this.reader = reader;
    this.processor = processor;
  }

  // idIsGroup = false: the consumer group comes from spring.kafka.consumer.group-id, not the ID.
  @KafkaListener(
      id = ID,
      idIsGroup = false,
      topics = "${pms.events.patient-created.topic:patient.created}")
  public void onPatientCreated(ConsumerRecord<String, String> record) {
    PatientCreatedEvent event = reader.read(record.value());
    if (processor.process(event)) {
      // Logged after commit. IDs only: the patient ID is an opaque reference, not personal data.
      log.info("Recorded welcome notification for patient {}", event.patientId());
    } else {
      log.debug("Skipped already processed patient.created event {}", event.eventId());
    }
  }
}
