package org.pms.patientservice.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.pms.patientservice.support.PostgresTestcontainerConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Shared setup for the patient API regression tests. Tests share one container and one cached
 * context, so each test creates its own records with a unique email and never asserts on the total
 * row count.
 *
 * <p>Every request must carry the identity headers the API gateway would forward; {@link
 * #asAdmin()} and friends add them. Header names and values are written out literally here on
 * purpose: they are the contract with the gateway.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestcontainerConfig.class)
abstract class AbstractPatientApiTest {

  protected static final String PATIENTS = "/api/v1/patients";

  protected static final String USER_ID_HEADER = "X-User-Id";
  protected static final String USER_ROLES_HEADER = "X-User-Roles";
  protected static final String PATIENT_ID_HEADER = "X-Patient-Id";

  @Autowired protected MockMvc mockMvc;

  /** Identity headers of an ADMIN, who may use every patient endpoint. */
  protected static RequestPostProcessor asAdmin() {
    return asStaff("ADMIN");
  }

  /** Identity headers of a staff user holding {@code role}. */
  protected static RequestPostProcessor asStaff(String role) {
    return request -> {
      request.addHeader(USER_ID_HEADER, UUID.randomUUID().toString());
      request.addHeader(USER_ROLES_HEADER, role);
      return request;
    };
  }

  /** Identity headers of a PATIENT whose own record is {@code patientId}. */
  protected static RequestPostProcessor asPatient(String patientId) {
    return request -> {
      request.addHeader(USER_ID_HEADER, UUID.randomUUID().toString());
      request.addHeader(USER_ROLES_HEADER, "PATIENT");
      request.addHeader(PATIENT_ID_HEADER, patientId);
      return request;
    };
  }

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
                    .with(asAdmin())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(patientJson(email, "1990-01-01", "2024-01-01")))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

    return JsonPath.read(response, "$.id");
  }

  /**
   * Reads every page of the patient list. Other tests share the database, so a given patient is not
   * guaranteed to be on the first page.
   */
  protected List<Map<String, Object>> fetchAllPatients() throws Exception {
    List<Map<String, Object>> patients = new ArrayList<>();
    int page = 0;
    int totalPages;
    do {
      String response =
          mockMvc
              .perform(
                  get(PATIENTS)
                      .with(asAdmin())
                      .param("page", String.valueOf(page))
                      .param("size", "100"))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      patients.addAll(JsonPath.read(response, "$.content"));
      totalPages = JsonPath.read(response, "$.page.totalPages");
      page++;
    } while (page < totalPages);
    return patients;
  }
}
