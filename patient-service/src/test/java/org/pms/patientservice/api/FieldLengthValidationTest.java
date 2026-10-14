package org.pms.patientservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;

/**
 * Every free-text field is limited to what its database column holds, so an over-long value is a
 * 400 naming the field, never a database error surfacing as a 500.
 */
class FieldLengthValidationTest extends AbstractPatientApiTest {

  /** 64-character local part, the longest a valid address may have. */
  private static final String LOCAL_PART = "a".repeat(64);

  @ParameterizedTest(name = "{0} too long -> 400")
  @CsvSource({"name, 101", "email, 312", "address, 256"})
  @DisplayName("A value longer than its column is rejected with a 400 problem naming the field")
  void tooLongFieldIsRejected(String field, int length) throws Exception {
    String name = field.equals("name") ? "n".repeat(length) : "Length Test";
    String email = field.equals("email") ? emailOfLength(length) : uniqueEmail();
    String address = field.equals("address") ? "x".repeat(length) : "1 Length Test St";

    mockMvc
        .perform(
            post(PATIENTS)
                .with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(name, email, address)))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Validation failed"))
        .andExpect(jsonPath("$.errors[0].field").value(field));
  }

  @Test
  @DisplayName("Email and address of exactly 255 characters still fit their columns")
  void columnLengthValuesAreAccepted() throws Exception {
    String email = emailOfLength(255);
    assertThat(email).hasSize(255);

    mockMvc
        .perform(
            post(PATIENTS)
                .with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("Length Test", email, "y".repeat(255))))
        .andExpect(status().isCreated());
  }

  /**
   * A syntactically valid, unique address of exactly {@code length} characters: a 64-character
   * local part and a domain of 60-character labels, so it passes {@code @Email}.
   */
  private static String emailOfLength(int length) {
    String unique = UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    StringBuilder domain = new StringBuilder(unique);
    int domainLength = length - LOCAL_PART.length() - 1;
    while (domain.length() + 4 < domainLength) {
      int label = Math.min(60, domainLength - domain.length() - 5);
      domain.append('.').append("d".repeat(Math.max(1, label)));
    }
    domain.append(".com");
    String email = LOCAL_PART + "@" + domain;
    if (email.length() != length) {
      throw new IllegalStateException("built an email of " + email.length() + ", not " + length);
    }
    return email;
  }

  private static String json(String name, String email, String address) {
    return "{\"name\":\""
        + name
        + "\",\"email\":\""
        + email
        + "\",\"address\":\""
        + address
        + "\",\"dateOfBirth\":\"1990-01-01\",\"registeredDate\":\"2024-01-01\"}";
  }
}
