package org.pms.patientservice.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Every cell of the approved role x endpoint matrix. Each case runs against its own freshly created
 * record; a PATIENT caller is given that record as its own, so a 403 on a write proves the role is
 * refused even for the caller's own record (ownership never grants writes).
 */
class AuthorizationMatrixTest extends AbstractPatientApiTest {

  enum Endpoint {
    LIST,
    GET_ONE,
    CREATE,
    UPDATE,
    DELETE
  }

  private static final int FORBIDDEN = 403;

  static Stream<Arguments> matrix() {
    return Stream.of(
        // GET /api/v1/patients
        cell(Endpoint.LIST, "ADMIN", 200),
        cell(Endpoint.LIST, "DOCTOR", 200),
        cell(Endpoint.LIST, "NURSE", 200),
        cell(Endpoint.LIST, "BILLING_STAFF", 200),
        cell(Endpoint.LIST, "PATIENT", FORBIDDEN),
        // GET /api/v1/patients/{id} (PATIENT: own record)
        cell(Endpoint.GET_ONE, "ADMIN", 200),
        cell(Endpoint.GET_ONE, "DOCTOR", 200),
        cell(Endpoint.GET_ONE, "NURSE", 200),
        cell(Endpoint.GET_ONE, "BILLING_STAFF", 200),
        cell(Endpoint.GET_ONE, "PATIENT", 200),
        // POST /api/v1/patients
        cell(Endpoint.CREATE, "ADMIN", 201),
        cell(Endpoint.CREATE, "DOCTOR", 201),
        cell(Endpoint.CREATE, "NURSE", 201),
        cell(Endpoint.CREATE, "BILLING_STAFF", FORBIDDEN),
        cell(Endpoint.CREATE, "PATIENT", FORBIDDEN),
        // PUT /api/v1/patients/{id}
        cell(Endpoint.UPDATE, "ADMIN", 200),
        cell(Endpoint.UPDATE, "DOCTOR", 200),
        cell(Endpoint.UPDATE, "NURSE", 200),
        cell(Endpoint.UPDATE, "BILLING_STAFF", FORBIDDEN),
        cell(Endpoint.UPDATE, "PATIENT", FORBIDDEN),
        // DELETE /api/v1/patients/{id}
        cell(Endpoint.DELETE, "ADMIN", 204),
        cell(Endpoint.DELETE, "DOCTOR", FORBIDDEN),
        cell(Endpoint.DELETE, "NURSE", FORBIDDEN),
        cell(Endpoint.DELETE, "BILLING_STAFF", FORBIDDEN),
        cell(Endpoint.DELETE, "PATIENT", FORBIDDEN));
  }

  @ParameterizedTest(name = "{1} {0} -> {2}")
  @MethodSource("matrix")
  void roleMatrix(Endpoint endpoint, String role, int expectedStatus) throws Exception {
    String id = createPatient(uniqueEmail());
    RequestPostProcessor caller = "PATIENT".equals(role) ? asPatient(id) : asStaff(role);

    var result =
        mockMvc.perform(request(endpoint, id).with(caller)).andExpect(status().is(expectedStatus));

    if (expectedStatus == FORBIDDEN) {
      result
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
          .andExpect(jsonPath("$.status").value(FORBIDDEN))
          .andExpect(jsonPath("$.title").value("Forbidden"));
      // A refused write or delete must leave the record untouched.
      mockMvc
          .perform(get(PATIENTS + "/" + id).with(asAdmin()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.name").value("Regression Patient"));
    }
  }

  private MockHttpServletRequestBuilder request(Endpoint endpoint, String id) {
    return switch (endpoint) {
      case LIST -> get(PATIENTS);
      case GET_ONE -> get(PATIENTS + "/" + id);
      case CREATE ->
          post(PATIENTS)
              .contentType(MediaType.APPLICATION_JSON)
              .content(patientJson(uniqueEmail(), "1990-01-01", "2024-01-01"));
      case UPDATE ->
          put(PATIENTS + "/" + id)
              .contentType(MediaType.APPLICATION_JSON)
              .content(
                  """
                  {"name":"Updated By Matrix","email":"%s","address":"2 Matrix St",\
                  "dateOfBirth":"1991-02-03"}"""
                      .formatted(uniqueEmail()));
      case DELETE -> delete(PATIENTS + "/" + id);
    };
  }

  private static Arguments cell(Endpoint endpoint, String role, int expectedStatus) {
    return Arguments.of(endpoint, role, expectedStatus);
  }
}
