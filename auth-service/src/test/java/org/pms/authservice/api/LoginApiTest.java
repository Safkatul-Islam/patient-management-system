package org.pms.authservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pms.common.web.correlation.CorrelationIdFilter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

class LoginApiTest extends AbstractAuthApiTest {

  private static final String FIXED_CORRELATION_ID = "login-failure-check";

  @Test
  @DisplayName("Login returns a non-cacheable Bearer token pair")
  void loginSucceeds() throws Exception {
    String email = uniqueEmail();
    String password = randomPassword();
    createStaff(email, password, "NURSE");

    postJson("/auth/login", loginJson(email, password))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(jsonPath("$.accessToken").isString())
        .andExpect(jsonPath("$.tokenType").value("Bearer"))
        .andExpect(jsonPath("$.expiresIn").value(900))
        .andExpect(jsonPath("$.refreshToken").isString())
        .andExpect(jsonPath("$.refreshExpiresIn").value(604800));
  }

  @Test
  @DisplayName("Login email is matched case-insensitively and trimmed")
  void loginNormalizesEmail() throws Exception {
    String email = uniqueEmail();
    String password = randomPassword();
    createStaff(email, password, "NURSE");

    postJson("/auth/login", loginJson("  " + email.toUpperCase() + " ", password))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("Wrong password, unknown email and inactive user give identical 401 bodies")
  void failuresAreIndistinguishable() throws Exception {
    String email = uniqueEmail();
    String password = randomPassword();
    createStaff(email, password, "DOCTOR");
    String inactiveEmail = uniqueEmail();
    String inactivePassword = randomPassword();
    deactivate(createStaff(inactiveEmail, inactivePassword, "DOCTOR"));

    String wrongPassword = failedLogin(email, randomPassword());
    String unknownEmail = failedLogin(uniqueEmail(), password);
    String inactiveUser = failedLogin(inactiveEmail, inactivePassword);

    assertThat(wrongPassword).isEqualTo(unknownEmail).isEqualTo(inactiveUser);
  }

  @Test
  @DisplayName("A password over BCrypt's 72-byte limit is a 401, not a 500")
  void oversizedPasswordIsInvalidCredentials() throws Exception {
    String email = uniqueEmail();
    createStaff(email, randomPassword(), "DOCTOR");

    postJson("/auth/login", loginJson(email, "é".repeat(40)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("Invalid credentials."));
  }

  @Test
  @DisplayName("Blank login fields are a 400 validation problem")
  void blankFieldsAreValidationErrors() throws Exception {
    postJson("/auth/login", loginJson("", ""))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.errors[*].field").value(hasItems("email")));
  }

  private String failedLogin(String email, String password) throws Exception {
    return mockMvc
        .perform(
            post("/auth/login")
                .header(CorrelationIdFilter.HEADER, FIXED_CORRELATION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginJson(email, password)))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(401))
        .andExpect(jsonPath("$.title").value("Unauthorized"))
        .andExpect(jsonPath("$.detail").value("Invalid credentials."))
        .andExpect(jsonPath("$.correlationId").value(FIXED_CORRELATION_ID))
        .andReturn()
        .getResponse()
        .getContentAsString();
  }
}
