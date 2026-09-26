package org.pms.authservice.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class AdminUserApiTest extends AbstractAuthApiTest {

  @Test
  @DisplayName("Creating a staff user without a token is a 401")
  void noTokenIsUnauthorized() throws Exception {
    postJson("/auth/admin/users", staffJson(uniqueEmail(), randomPassword(), "NURSE"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
  }

  @Test
  @DisplayName("A DOCTOR cannot create accounts (403 problem)")
  void doctorIsForbidden() throws Exception {
    String doctorAccess = JsonPath.read(newDoctorSession(), "$.accessToken");

    postJson("/auth/admin/users", staffJson(uniqueEmail(), randomPassword(), "NURSE"), doctorAccess)
        .andExpect(status().isForbidden())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(403))
        .andExpect(jsonPath("$.title").value("Forbidden"));
    postJson(
            "/auth/admin/patients",
            patientAccountJson(uniqueEmail(), randomPassword(), UUID.randomUUID()),
            doctorAccess)
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("ADMIN creates a staff user: 201 with id, normalized email, no password hash")
  void adminCreatesStaffUser() throws Exception {
    String email = uniqueEmail();

    postJson(
            "/auth/admin/users",
            staffJson(email.toUpperCase(), randomPassword(), "BILLING_STAFF"),
            adminAccessToken())
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").isString())
        .andExpect(jsonPath("$.email").value(email))
        .andExpect(jsonPath("$.role").value("BILLING_STAFF"))
        .andExpect(jsonPath("$.patientId").doesNotExist())
        .andExpect(jsonPath("$.active").value(true))
        .andExpect(jsonPath("$.passwordHash").doesNotExist())
        .andExpect(jsonPath("$.password").doesNotExist());
  }

  @Test
  @DisplayName("ADMIN creates a PATIENT account linked to a patient id")
  void adminCreatesPatientAccount() throws Exception {
    UUID patientId = UUID.randomUUID();

    postJson(
            "/auth/admin/patients",
            patientAccountJson(uniqueEmail(), randomPassword(), patientId),
            adminAccessToken())
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.role").value("PATIENT"))
        .andExpect(jsonPath("$.patientId").value(patientId.toString()));
  }

  @Test
  @DisplayName("Staff endpoint rejects role PATIENT with a 400 validation problem")
  void staffEndpointRejectsPatientRole() throws Exception {
    postJson(
            "/auth/admin/users",
            staffJson(uniqueEmail(), randomPassword(), "PATIENT"),
            adminAccessToken())
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.errors[0].field").value("role"));
  }

  @Test
  @DisplayName("Staff endpoint rejects a patientId with a 400 validation problem")
  void staffEndpointRejectsPatientId() throws Exception {
    String json =
        "{\"email\":\"%s\",\"password\":\"%s\",\"role\":\"DOCTOR\",\"patientId\":\"%s\"}"
            .formatted(uniqueEmail(), randomPassword(), UUID.randomUUID());

    postJson("/auth/admin/users", json, adminAccessToken())
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[*].field").value(hasItem("patientId")));
  }

  @Test
  @DisplayName("Patient account without patientId is a 400 validation problem")
  void patientAccountRequiresPatientId() throws Exception {
    String json =
        "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(uniqueEmail(), randomPassword());

    postJson("/auth/admin/patients", json, adminAccessToken())
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[*].field").value(hasItem("patientId")));
  }

  @Test
  @DisplayName("Duplicate email (any case) is a 409 problem")
  void duplicateEmailIsConflict() throws Exception {
    String email = uniqueEmail();
    createStaff(email, randomPassword(), "NURSE");

    postJson(
            "/auth/admin/users",
            staffJson(email.toUpperCase(), randomPassword(), "DOCTOR"),
            adminAccessToken())
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.detail").value("Email already in use."));
  }

  @Test
  @DisplayName("A second account for the same patient is a 409 problem")
  void secondAccountForSamePatientIsConflict() throws Exception {
    UUID patientId = UUID.randomUUID();
    createPatientAccount(uniqueEmail(), randomPassword(), patientId);

    postJson(
            "/auth/admin/patients",
            patientAccountJson(uniqueEmail(), randomPassword(), patientId),
            adminAccessToken())
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Patient already has an account"))
        .andExpect(jsonPath("$.detail").value(not(containsString(patientId.toString()))));
  }

  @Test
  @DisplayName("Invalid fields are a 400 problem listing each field")
  void validationErrorsListFields() throws Exception {
    postJson("/auth/admin/users", staffJson("not-an-email", "short", "DOCTOR"), adminAccessToken())
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Validation failed"))
        .andExpect(jsonPath("$.errors[*].field").value(hasItem("email")))
        .andExpect(jsonPath("$.errors[*].field").value(hasItem("password")));
  }

  @Test
  @DisplayName("A password within 72 characters but over 72 UTF-8 bytes is a 400")
  void passwordOverByteLimitIsBadRequest() throws Exception {
    String password = "é".repeat(40);

    postJson("/auth/admin/users", staffJson(uniqueEmail(), password, "DOCTOR"), adminAccessToken())
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("password"));
  }

  @Test
  @DisplayName("An unknown role value is a 400, not a 500")
  void unknownRoleIsBadRequest() throws Exception {
    postJson(
            "/auth/admin/users",
            staffJson(uniqueEmail(), randomPassword(), "JANITOR"),
            adminAccessToken())
        .andExpect(status().isBadRequest());
  }
}
