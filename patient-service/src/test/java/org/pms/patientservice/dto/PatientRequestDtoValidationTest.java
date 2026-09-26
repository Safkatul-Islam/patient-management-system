package org.pms.patientservice.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.groups.Default;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.pms.patientservice.dto.validators.CreatePatientValidationGroup;

/** Create validates Default + CreatePatientValidationGroup; update validates Default only. */
class PatientRequestDtoValidationTest {

  private static ValidatorFactory factory;
  private static Validator validator;

  @BeforeAll
  static void setUpValidator() {
    factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @AfterAll
  static void closeFactory() {
    factory.close();
  }

  @Test
  void validCreateRequestPasses() {
    assertThat(validateForCreate(validRequest())).isEmpty();
  }

  @Test
  void registeredDateIsRequiredOnCreate() {
    PatientRequestDto request = validRequest();
    request.setRegisteredDate(null);

    assertThat(validateForCreate(request))
        .singleElement()
        .satisfies(
            violation -> {
              assertThat(violation.getPropertyPath()).hasToString("registeredDate");
              assertThat(violation.getMessage()).isEqualTo("Register date is required!");
            });
  }

  @Test
  void registeredDateIsOptionalOnUpdate() {
    PatientRequestDto request = validRequest();
    request.setRegisteredDate(null);

    assertThat(validator.validate(request, Default.class)).isEmpty();
  }

  @Test
  void malformedEmailIsRejected() {
    PatientRequestDto request = validRequest();
    request.setEmail("not-an-email");

    assertThat(validator.validate(request, Default.class))
        .singleElement()
        .satisfies(
            violation -> {
              assertThat(violation.getPropertyPath()).hasToString("email");
              assertThat(violation.getMessage()).isEqualTo("Invalid email format!");
            });
  }

  @Test
  void nameIsLimitedTo100Characters() {
    PatientRequestDto atLimit = validRequest();
    atLimit.setName("a".repeat(100));
    PatientRequestDto overLimit = validRequest();
    overLimit.setName("a".repeat(101));

    assertThat(validator.validate(atLimit, Default.class)).isEmpty();
    assertThat(validator.validate(overLimit, Default.class))
        .singleElement()
        .satisfies(
            violation ->
                assertThat(violation.getMessage()).isEqualTo("Name cannot exceed 100 characters!"));
  }

  @Test
  void blankRequiredFieldsAreRejected() {
    PatientRequestDto request = new PatientRequestDto();
    request.setName(" ");
    request.setEmail(" ");
    request.setAddress(" ");
    request.setDateOfBirth(" ");

    assertThat(validator.validate(request, Default.class))
        .extracting(violation -> violation.getPropertyPath().toString())
        .contains("name", "email", "address", "dateOfBirth");
  }

  private static Set<ConstraintViolation<PatientRequestDto>> validateForCreate(
      PatientRequestDto request) {
    return validator.validate(request, Default.class, CreatePatientValidationGroup.class);
  }

  private static PatientRequestDto validRequest() {
    PatientRequestDto request = new PatientRequestDto();
    request.setName("Test Patient");
    request.setEmail("test.patient@example.com");
    request.setAddress("1 Test St");
    request.setDateOfBirth("1990-01-02");
    request.setRegisteredDate("2024-03-04");
    return request;
  }
}
