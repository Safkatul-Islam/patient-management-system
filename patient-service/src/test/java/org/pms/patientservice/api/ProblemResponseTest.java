package org.pms.patientservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Errors are RFC 7807 problem responses correlated to the request, and neither the response nor the
 * logs carry patient data.
 */
@ExtendWith(OutputCaptureExtension.class)
class ProblemResponseTest extends AbstractPatientApiTest {

  private static final String CORRELATION_HEADER = "X-Correlation-Id";

  @Test
  @DisplayName("404 is a problem body carrying the caller's correlation id")
  void notFoundProblemCarriesCallerCorrelationId() throws Exception {
    mockMvc
        .perform(
            put(PATIENTS + "/" + UUID.randomUUID())
                .with(asAdmin())
                .header(CORRELATION_HEADER, "test-corr-404")
                .contentType(MediaType.APPLICATION_JSON)
                .content(patientJson(uniqueEmail(), "1990-01-01", "2024-01-01")))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(header().string(CORRELATION_HEADER, "test-corr-404"))
        .andExpect(jsonPath("$.title").value("Patient not found"))
        .andExpect(jsonPath("$.correlationId").value("test-corr-404"));
  }

  @Test
  @DisplayName("log lines written while handling a request carry its correlation id")
  void requestLogsCarryCorrelationId(CapturedOutput output) throws Exception {
    mockMvc
        .perform(
            put(PATIENTS + "/" + UUID.randomUUID())
                .with(asAdmin())
                .header(CORRELATION_HEADER, "test-corr-log")
                .contentType(MediaType.APPLICATION_JSON)
                .content(patientJson(uniqueEmail(), "1990-01-01", "2024-01-01")))
        .andExpect(status().isNotFound());

    // Structured (ECS JSON) console logging includes MDC entries as fields.
    assertThat(output.getOut()).contains("\"correlationId\":\"test-corr-log\"");
  }

  @Test
  @DisplayName("a generated correlation id is echoed in the header and the problem body")
  void generatedCorrelationIdMatchesHeaderAndBody() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                put(PATIENTS + "/" + UUID.randomUUID())
                    .with(asAdmin())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(patientJson(uniqueEmail(), "1990-01-01", "2024-01-01")))
            .andExpect(status().isNotFound())
            .andReturn();

    String header = result.getResponse().getHeader(CORRELATION_HEADER);
    String body = result.getResponse().getContentAsString();
    assertThat(header).isNotBlank();
    assertThat((String) JsonPath.read(body, "$.correlationId")).isEqualTo(header);
  }

  @Test
  @DisplayName("successful responses also echo the correlation id")
  void successEchoesCorrelationId() throws Exception {
    mockMvc
        .perform(
            post(PATIENTS)
                .with(asAdmin())
                .header(CORRELATION_HEADER, "test-corr-201")
                .contentType(MediaType.APPLICATION_JSON)
                .content(patientJson(uniqueEmail(), "1990-01-01", "2024-01-01")))
        .andExpect(status().isCreated())
        .andExpect(header().string(CORRELATION_HEADER, "test-corr-201"));
  }

  @Test
  @DisplayName("409 names neither the email in the body nor in the logs")
  void conflictDoesNotExposeEmail(CapturedOutput output) throws Exception {
    String email = uniqueEmail();
    createPatient(email);

    mockMvc
        .perform(
            post(PATIENTS)
                .with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(patientJson(email, "1990-01-01", "2024-01-01")))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Email already in use"))
        .andExpect(content().string(not(containsString(email))));

    assertThat(output.getAll()).doesNotContain(email);
  }

  @Test
  @DisplayName("an unparseable date is not echoed into the logs")
  void unparseableDateIsNotLogged(CapturedOutput output) throws Exception {
    mockMvc
        .perform(
            post(PATIENTS)
                .with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(patientJson(uniqueEmail(), "17/08/1961", "2024-01-01")))
        .andExpect(status().isBadRequest());

    assertThat(output.getAll()).doesNotContain("17/08/1961");
  }

  @Test
  @DisplayName("validation failures are problem bodies listing the invalid fields")
  void validationFailureIsProblemWithFieldErrors() throws Exception {
    mockMvc
        .perform(
            post(PATIENTS)
                .with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(patientJson("not-an-email", "1990-01-01", "2024-01-01")))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Validation failed"))
        .andExpect(jsonPath("$.errors[0].field").value("email"));
  }
}
