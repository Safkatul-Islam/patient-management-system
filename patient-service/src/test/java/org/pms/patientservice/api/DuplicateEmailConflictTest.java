package org.pms.patientservice.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Fix 3b: EmailAlreadyExistsException was mapped to 400. A uniqueness collision on an existing
 * resource is a 409.
 */
class DuplicateEmailConflictTest extends AbstractPatientApiTest {

  @Test
  @DisplayName("POST with an already-registered email returns 409, not 400")
  void createWithDuplicateEmailReturns409() throws Exception {
    String email = uniqueEmail();
    createPatient(email);

    mockMvc
        .perform(
            post(PATIENTS)
                .contentType(MediaType.APPLICATION_JSON)
                .content(patientJson(email, "1990-01-01", "2024-01-01")))
        .andExpect(status().isConflict());
  }

  @Test
  @DisplayName("PUT moving a patient onto another patient's email returns 409")
  void updateOntoExistingEmailReturns409() throws Exception {
    String takenEmail = uniqueEmail();
    createPatient(takenEmail);
    String otherId = createPatient(uniqueEmail());

    mockMvc
        .perform(
            put(PATIENTS + "/" + otherId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(patientJson(takenEmail, "1990-01-01", "2024-01-01")))
        .andExpect(status().isConflict());
  }

  @Test
  @DisplayName("PUT keeping a patient's own email is not a conflict")
  void updateKeepingOwnEmailSucceeds() throws Exception {
    String email = uniqueEmail();
    String id = createPatient(email);

    mockMvc
        .perform(
            put(PATIENTS + "/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(patientJson(email, "1992-03-04", "2024-01-01")))
        .andExpect(status().isOk());
  }
}
