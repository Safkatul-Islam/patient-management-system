package org.pms.patientservice.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Fix 5: an unparseable date escaped as DateTimeParseException and surfaced as a 500. It is
 * malformed client input, so it is a 400 with a clean body and no stack trace.
 */
class MalformedDateTest extends AbstractPatientApiTest {

  @Test
  @DisplayName("POST with a garbage dateOfBirth returns 400, not 500")
  void createWithGarbageDateOfBirthReturns400() throws Exception {
    mockMvc
        .perform(
            post(PATIENTS)
                .contentType(MediaType.APPLICATION_JSON)
                .content(patientJson(uniqueEmail(), "not-a-date", "2024-01-01")))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.Error").value("Invalid date format. Expected ISO-8601 (yyyy-MM-dd)."));
  }

  @Test
  @DisplayName("POST with a garbage registeredDate returns 400")
  void createWithGarbageRegisteredDateReturns400() throws Exception {
    mockMvc
        .perform(
            post(PATIENTS)
                .contentType(MediaType.APPLICATION_JSON)
                .content(patientJson(uniqueEmail(), "1990-01-01", "13/45/9999")))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("PUT with a garbage date returns 400")
  void updateWithGarbageDateReturns400() throws Exception {
    String id = createPatient(uniqueEmail());

    mockMvc
        .perform(
            put(PATIENTS + "/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(patientJson(uniqueEmail(), "31-02-1990", null)))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("error body leaks no stack trace or internal detail")
  void errorBodyLeaksNoInternalDetail() throws Exception {
    String body =
        mockMvc
            .perform(
                post(PATIENTS)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(patientJson(uniqueEmail(), "not-a-date", "2024-01-01")))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andReturn()
            .getResponse()
            .getContentAsString();

    org.junit.jupiter.api.Assertions.assertFalse(body.contains("DateTimeParseException"), body);
    org.junit.jupiter.api.Assertions.assertFalse(body.contains("org.pms"), body);
    org.junit.jupiter.api.Assertions.assertFalse(body.contains("java.time"), body);
    org.junit.jupiter.api.Assertions.assertFalse(body.contains("at "), body);
  }
}
