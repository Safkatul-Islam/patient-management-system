package org.pms.patientservice.exception;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.pms.patientservice.model.Patient;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.data.core.TypeInformation;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Maps each domain exception to its problem response, without the Spring context. */
class GlobalHandlerExceptionTest {

  private static final String EMAIL = "someone@example.com";

  @RestController
  static class ThrowingController {
    @GetMapping("/not-found")
    String notFound() {
      throw new PatientNotFoundException("Patient not found with ID: " + UUID.randomUUID());
    }

    @GetMapping("/access-denied")
    String accessDenied() {
      throw new PatientAccessDeniedException();
    }

    @GetMapping("/conflict")
    String conflict(@RequestParam String email) {
      throw new EmailAlreadyExistsException();
    }

    @GetMapping("/bad-date")
    String badDate(@RequestParam String date) {
      return LocalDate.parse(date).toString();
    }

    @GetMapping("/unsupported-sort")
    String unsupportedSort() {
      throw new InvalidSortPropertyException(List.of("name", "email"));
    }

    @GetMapping("/integrity-violation")
    String integrityViolation(@RequestParam String constraint) {
      SQLException sqlError =
          new SQLException("duplicate key; Key (email)=(" + EMAIL + ") already exists", "23505");
      throw new DataIntegrityViolationException(
          "could not execute statement",
          new ConstraintViolationException("could not execute statement", sqlError, constraint));
    }

    @GetMapping("/unknown-sort")
    String unknownSort() {
      throw new PropertyReferenceException(
          "secretColumn", TypeInformation.of(Patient.class), List.of());
    }
  }

  private final MockMvc mockMvc =
      MockMvcBuilders.standaloneSetup(new ThrowingController())
          .setControllerAdvice(new GlobalHandlerException())
          .build();

  @Test
  void patientNotFoundIs404Problem() throws Exception {
    mockMvc
        .perform(get("/not-found"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.title").value("Patient not found"))
        .andExpect(jsonPath("$.detail").value("No patient exists with the requested ID."));
  }

  @Test
  void patientAccessDeniedIs403Problem() throws Exception {
    mockMvc
        .perform(get("/access-denied"))
        .andExpect(status().isForbidden())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(403))
        .andExpect(jsonPath("$.title").value("Forbidden"))
        .andExpect(
            jsonPath("$.detail")
                .value("You do not have permission to access this patient record."));
  }

  @Test
  void duplicateEmailIs409ProblemWithoutTheEmail() throws Exception {
    mockMvc
        .perform(get("/conflict").param("email", EMAIL))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.title").value("Email already in use"))
        .andExpect(content().string(not(containsString(EMAIL))));
  }

  @Test
  void emailUniqueConstraintViolationIs409ProblemWithoutTheEmail() throws Exception {
    mockMvc
        .perform(get("/integrity-violation").param("constraint", "patients_email_key"))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.title").value("Email already in use"))
        .andExpect(content().string(not(containsString(EMAIL))));
  }

  @Test
  void otherIntegrityViolationIsGeneric500NotConflict() throws Exception {
    mockMvc
        .perform(get("/integrity-violation").param("constraint", "patients_pkey"))
        .andExpect(status().isInternalServerError())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Internal server error"))
        .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
        .andExpect(content().string(not(containsString(EMAIL))))
        .andExpect(content().string(not(containsString("patients_pkey"))));
  }

  @Test
  void unparseableDateIs400Problem() throws Exception {
    mockMvc
        .perform(get("/bad-date").param("date", "31-02-1990"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Invalid date"))
        .andExpect(
            jsonPath("$.detail").value("Invalid date format. Expected ISO-8601 (yyyy-MM-dd)."))
        .andExpect(content().string(not(containsString("31-02-1990"))))
        .andExpect(content().string(not(containsString("DateTimeParseException"))));
  }

  @Test
  void unsupportedSortPropertyIs400ProblemListingSortableProperties() throws Exception {
    mockMvc
        .perform(get("/unsupported-sort"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Invalid sort parameter"))
        .andExpect(
            jsonPath("$.detail")
                .value("Unsupported sort property. Sortable properties: name, email"));
  }

  @Test
  void unknownEntityPropertyIs400ProblemWithoutInternals() throws Exception {
    mockMvc
        .perform(get("/unknown-sort"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Invalid sort parameter"))
        .andExpect(jsonPath("$.detail").value("Unsupported sort property."))
        .andExpect(content().string(not(containsString("secretColumn"))))
        .andExpect(content().string(not(containsString("Patient"))));
  }
}
