package org.pms.patientservice.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.pms.patientservice.dto.PatientRequestDto;
import org.pms.patientservice.dto.PatientResponseDto;
import org.pms.patientservice.model.Patient;

class PatientMapperTest {

  private final PatientMapper mapper = new PatientMapper();

  @Test
  void mapsEveryEntityFieldToTheResponse() {
    UUID id = UUID.randomUUID();
    Patient patient =
        new Patient(
            id,
            "Test Patient",
            "test.patient@example.com",
            "1 Test St",
            LocalDate.of(1990, 1, 2),
            LocalDate.of(2024, 3, 4));

    PatientResponseDto dto = mapper.mapToDto(patient);

    assertThat(dto.getId()).isEqualTo(id.toString());
    assertThat(dto.getName()).isEqualTo("Test Patient");
    assertThat(dto.getEmail()).isEqualTo("test.patient@example.com");
    assertThat(dto.getAddress()).isEqualTo("1 Test St");
    assertThat(dto.getDateOfBirth()).isEqualTo("1990-01-02");
    assertThat(dto.getRegisteredDate()).isEqualTo("2024-03-04");
  }

  @Test
  void mapsEveryRequestFieldToANewEntity() {
    PatientRequestDto request = request("1990-01-02", "2024-03-04");

    Patient patient = mapper.mapToEntity(request);

    assertThat(patient.getId()).isNull();
    assertThat(patient.getName()).isEqualTo("Test Patient");
    assertThat(patient.getEmail()).isEqualTo("test.patient@example.com");
    assertThat(patient.getAddress()).isEqualTo("1 Test St");
    assertThat(patient.getDateOfBirth()).isEqualTo(LocalDate.of(1990, 1, 2));
    assertThat(patient.getRegisteredDate()).isEqualTo(LocalDate.of(2024, 3, 4));
  }

  @Test
  void rejectsANonIsoDate() {
    assertThatThrownBy(() -> mapper.mapToEntity(request("02/01/1990", "2024-03-04")))
        .isInstanceOf(DateTimeParseException.class);
  }

  private static PatientRequestDto request(String dateOfBirth, String registeredDate) {
    PatientRequestDto request = new PatientRequestDto();
    request.setName("Test Patient");
    request.setEmail("test.patient@example.com");
    request.setAddress("1 Test St");
    request.setDateOfBirth(dateOfBirth);
    request.setRegisteredDate(registeredDate);
    return request;
  }
}
