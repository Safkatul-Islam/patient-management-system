package org.pms.patientservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * A PATIENT may read only its own record. Role membership alone is not enough: the requested id
 * must be the caller's own patient id.
 */
class PatientSelfScopeTest extends AbstractPatientApiTest {

  private static final String CORRELATION_HEADER = "X-Correlation-Id";

  @Test
  @DisplayName("a PATIENT reads its own record")
  void patientReadsOwnRecord() throws Exception {
    String email = uniqueEmail();
    String ownId = createPatient(email);

    mockMvc
        .perform(get(PATIENTS + "/" + ownId).with(asPatient(ownId)))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.id").value(ownId))
        .andExpect(jsonPath("$.email").value(email));
  }

  @Test
  @DisplayName("a PATIENT asking for another existing record gets a 403 problem")
  void patientCannotReadAnotherRecord() throws Exception {
    String ownId = createPatient(uniqueEmail());
    String otherId = createPatient(uniqueEmail());

    mockMvc
        .perform(get(PATIENTS + "/" + otherId).with(asPatient(ownId)))
        .andExpect(status().isForbidden())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Forbidden"))
        .andExpect(
            jsonPath("$.detail").value("You do not have permission to access this patient record."))
        .andExpect(jsonPath("$.email").doesNotExist());
  }

  @Test
  @DisplayName("another record answers the same 403 whether or not it exists (no existence oracle)")
  void forbiddenBodyIsTheSameForExistingAndMissingRecords() throws Exception {
    String ownId = createPatient(uniqueEmail());
    String existingOtherId = createPatient(uniqueEmail());
    String missingOtherId = UUID.randomUUID().toString();

    Map<String, Object> existing = forbiddenBody(ownId, existingOtherId);
    Map<String, Object> missing = forbiddenBody(ownId, missingOtherId);

    // instance is the request path, which necessarily differs; everything else must not.
    assertThat(existing.remove("instance")).isEqualTo(PATIENTS + "/" + existingOtherId);
    assertThat(missing.remove("instance")).isEqualTo(PATIENTS + "/" + missingOtherId);
    assertThat(missing).isEqualTo(existing);
  }

  @Test
  @DisplayName("a PATIENT whose own record does not exist gets a 404")
  void patientOwnMissingRecordIsNotFound() throws Exception {
    String ownId = UUID.randomUUID().toString();

    mockMvc
        .perform(get(PATIENTS + "/" + ownId).with(asPatient(ownId)))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Patient not found"));
  }

  @Test
  @DisplayName("a PATIENT cannot list patients")
  void patientCannotList() throws Exception {
    String ownId = createPatient(uniqueEmail());

    mockMvc
        .perform(get(PATIENTS).with(asPatient(ownId)))
        .andExpect(status().isForbidden())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Forbidden"))
        .andExpect(jsonPath("$.content").doesNotExist());
  }

  /**
   * The 403 body for a PATIENT asking for {@code requestedId}, sent with a fixed correlation id.
   */
  private Map<String, Object> forbiddenBody(String ownId, String requestedId) throws Exception {
    String body =
        mockMvc
            .perform(
                get(PATIENTS + "/" + requestedId)
                    .with(asPatient(ownId))
                    .header(CORRELATION_HEADER, "self-scope-oracle"))
            .andExpect(status().isForbidden())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return new HashMap<>(JsonPath.<Map<String, Object>>read(body, "$"));
  }
}
