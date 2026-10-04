package org.pms.patientservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fix 2: PatientResponseDto had no id, so a client had no way to reference a record it had just
 * created for a later PUT or DELETE.
 */
class PatientResponseIdTest extends AbstractPatientApiTest {

  @Test
  @DisplayName("id from POST response matches the id for the same patient in GET")
  void postIdMatchesGetId() throws Exception {
    String email = uniqueEmail();
    String createdId = createPatient(email);

    // The id must be usable to find the same record in the collection.
    assertThat(fetchAllPatients())
        .filteredOn(patient -> email.equals(patient.get("email")))
        .singleElement()
        .satisfies(patient -> assertThat(patient.get("id")).isEqualTo(createdId));
  }

  @Test
  @DisplayName("POST response body exposes a non-blank id")
  void postResponseContainsId() throws Exception {
    String email = uniqueEmail();
    String createdId = createPatient(email);

    org.junit.jupiter.api.Assertions.assertNotNull(createdId);
    org.junit.jupiter.api.Assertions.assertFalse(createdId.isBlank());
    // Must be a real UUID, not a placeholder.
    java.util.UUID.fromString(createdId);
  }

  @Test
  @DisplayName("GET list exposes an id on every patient")
  void listExposesIdOnEveryPatient() throws Exception {
    mockMvc
        .perform(get(PATIENTS).with(asAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[*].id").isNotEmpty())
        .andExpect(jsonPath("$.content[?(!@.id)]").isEmpty());
  }
}
