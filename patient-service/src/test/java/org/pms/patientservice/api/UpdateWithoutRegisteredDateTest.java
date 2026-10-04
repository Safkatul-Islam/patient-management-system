package org.pms.patientservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Fix 1: updatePatient parsed registeredDate unconditionally, but that field is only required on
 * create. An update omitting it used to blow up with an NPE (HTTP 500).
 */
class UpdateWithoutRegisteredDateTest extends AbstractPatientApiTest {

  @Test
  @DisplayName("PUT without registeredDate succeeds instead of returning 500")
  void updateWithoutRegisteredDateSucceeds() throws Exception {
    String id = createPatient(uniqueEmail());
    String updatedEmail = uniqueEmail();

    mockMvc
        .perform(
            put(PATIENTS + "/" + id)
                .with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(patientJson(updatedEmail, "1991-02-03", null)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value(updatedEmail))
        .andExpect(jsonPath("$.dateOfBirth").value("1991-02-03"));
  }

  @Test
  @DisplayName("PUT without registeredDate preserves the original registration date")
  void updateWithoutRegisteredDatePreservesOriginalValue() throws Exception {
    String id = createPatient(uniqueEmail());

    // createPatient registers the patient on 2024-01-01.
    mockMvc
        .perform(
            put(PATIENTS + "/" + id)
                .with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(patientJson(uniqueEmail(), "1991-02-03", null)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.registeredDate").value("2024-01-01"));

    // ...and the stored record, not just the PUT response, keeps it.
    assertThat(fetchAllPatients())
        .filteredOn(patient -> id.equals(patient.get("id")))
        .singleElement()
        .satisfies(patient -> assertThat(patient.get("registeredDate")).isEqualTo("2024-01-01"));
  }

  @Test
  @DisplayName("PUT with registeredDate still succeeds")
  void updateWithRegisteredDateStillWorks() throws Exception {
    String id = createPatient(uniqueEmail());

    mockMvc
        .perform(
            put(PATIENTS + "/" + id)
                .with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(patientJson(uniqueEmail(), "1991-02-03", "2025-05-05")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.registeredDate").value("2025-05-05"));
  }
}
