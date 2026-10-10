package org.pms.patientservice.event;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The background sender of {@link PatientCreatedPublisher} ({@code pms.events.publisher.*}).
 *
 * @param queueCapacity committed events that may wait for the single sender thread; beyond that an
 *     event is dropped and counted
 * @param drainTimeout how long shutdown waits for queued events to be sent; keep it at least the
 *     producer's {@code delivery.timeout.ms}
 */
@Validated
@ConfigurationProperties("pms.events.publisher")
public record PatientEventPublisherProperties(
    @Min(1) int queueCapacity, @NotNull Duration drainTimeout) {}
