package org.pms.patientservice.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Fix 3a: PatientNotFoundException was mapped to 400. A missing resource is a 404. */
class NotFoundStatusTest extends AbstractPatientApiTest {

  @Test
  @DisplayName("GET on a nonexistent id returns 404")
  void getNonexistentPatientReturns404() throws Exception {
    mockMvc
        .perform(get(PATIENTS + "/" + UUID.randomUUID()).with(asAdmin()))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("PUT on a nonexistent id returns 404, not 400")
  void updateNonexistentPatientReturns404() throws Exception {
    mockMvc
        .perform(
            put(PATIENTS + "/" + UUID.randomUUID())
                .with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(patientJson(uniqueEmail(), "1990-01-01", "2024-01-01")))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("DELETE on a nonexistent id returns 404, not 400")
  void deleteNonexistentPatientReturns404() throws Exception {
    mockMvc
        .perform(delete(PATIENTS + "/" + UUID.randomUUID()).with(asAdmin()))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("DELETE on an existing id still returns 204")
  void deleteExistingPatientReturns204() throws Exception {
    String id = createPatient(uniqueEmail());

    mockMvc.perform(delete(PATIENTS + "/" + id).with(asAdmin())).andExpect(status().isNoContent());
  }
}
