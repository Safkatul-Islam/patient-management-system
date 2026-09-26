package org.pms.patientservice.exception;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
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

    @GetMapping("/conflict")
    String conflict(@RequestParam String email) {
      throw new EmailAlreadyExistsException();
    }

    @GetMapping("/bad-date")
    String badDate(@RequestParam String date) {
      return LocalDate.parse(date).toString();
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
}
