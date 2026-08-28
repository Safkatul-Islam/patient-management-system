package org.pms.patientservice.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.pms.patientservice.support.PostgresTestcontainerConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Shared setup for the patient API regression tests. Tests share one container and one cached
 * context, so each test creates its own records with a unique email and never asserts on the total
 * row count.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestcontainerConfig.class)
abstract class AbstractPatientApiTest {

  protected static final String PATIENTS = "/api/v1/patients";

  /** A seed patient from data.sql, present in every fresh container. */
  protected static final String SEEDED_PATIENT_ID = "123e4567-e89b-12d3-a456-426614174000";

  @Autowired protected MockMvc mockMvc;

  protected static String uniqueEmail() {
    return "regression-" + UUID.randomUUID() + "@example.com";
  }

  protected static String patientJson(String email, String dateOfBirth, String registeredDate) {
    StringBuilder json =
        new StringBuilder()
            .append("{\"name\":\"Regression Patient\",")
            .append("\"email\":\"")
            .append(email)
            .append("\",")
            .append("\"address\":\"1 Regression St\",")
            .append("\"dateOfBirth\":\"")
            .append(dateOfBirth)
            .append("\"");
    if (registeredDate != null) {
      json.append(",\"registeredDate\":\"").append(registeredDate).append("\"");
    }
    return json.append("}").toString();
  }

  /** Creates a patient via the API and returns the id from the response body. */
  protected String createPatient(String email) throws Exception {
    String response =
        mockMvc
            .perform(
                post(PATIENTS)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(patientJson(email, "1990-01-01", "2024-01-01")))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

    return JsonPath.read(response, "$.id");
  }
}
