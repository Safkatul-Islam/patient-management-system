package org.pms.analyticsservice.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.pms.analyticsservice.event.PatientCreatedEvent;
import org.pms.analyticsservice.event.PatientCreatedEventReader;
import org.pms.analyticsservice.service.PatientCreatedHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code patient.created} in the {@code analytics-service} group. Anything thrown here
 * goes to the container's error handler ({@link KafkaErrorHandlingConfig}); the offset is committed
 * only after this method returns.
 */
@Component
public class PatientCreatedListener {

  private static final Logger log = LoggerFactory.getLogger(PatientCreatedListener.class);

  private final PatientCreatedEventReader reader;
  private final PatientCreatedHandler handler;

  public PatientCreatedListener(PatientCreatedEventReader reader, PatientCreatedHandler handler) {
    this.reader = reader;
    this.handler = handler;
  }

  @KafkaListener(topics = "${pms.events.patient-created.topic:patient.created}")
  public void onPatientCreated(ConsumerRecord<String, String> record) {
    PatientCreatedEvent event = reader.read(record.value());
    if (handler.handle(event)) {
      // After the commit; the correlation ID comes from the record header via the MDC.
      log.info("Counted patient registration eventId={}", event.eventId());
    }
  }
}
