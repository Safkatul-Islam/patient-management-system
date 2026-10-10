package org.pms.patientservice.event;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The {@code patient.created} topic ({@code pms.events.patient-created.*}). Startup fails if any
 * value is missing or out of range.
 */
@Validated
@ConfigurationProperties("pms.events.patient-created")
public record PatientCreatedTopicProperties(
    @NotBlank String topic, @Min(1) int partitions, @Min(1) int replicas) {}
