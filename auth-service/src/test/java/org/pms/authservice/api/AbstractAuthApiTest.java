package org.pms.authservice.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.pms.authservice.service.TokenHashing;
import org.pms.authservice.support.PostgresTestcontainerConfig;
import org.pms.authservice.support.TestRsaKeys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Shared setup for auth-service integration tests. Every subclass uses exactly this configuration,
 * so they all share one cached context and one Postgres container. Tests create their own users
 * with unique emails and never assume an empty table.
 *
 * <p>The bootstrap admin configured here is created on context start and is how tests obtain an
 * ADMIN token. Its password is random per JVM, so no credential appears in source.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestcontainerConfig.class)
abstract class AbstractAuthApiTest {

  protected static final String BOOTSTRAP_ADMIN_EMAIL = "bootstrap-admin@pms.test";
  protected static final String BOOTSTRAP_ADMIN_PASSWORD = randomPassword();
  protected static final String ISSUER = "http://auth-service:4005";
  protected static final String AUDIENCE = "pms-api";

  @Autowired protected MockMvc mockMvc;
  @Autowired protected JdbcTemplate jdbcTemplate;

  @DynamicPropertySource
  static void authProperties(DynamicPropertyRegistry registry) {
    registry.add("pms.auth.jwt.private-key", TestRsaKeys::signingPrivateKeyBase64);
    registry.add("pms.auth.jwt.key-id", () -> TestRsaKeys.KEY_ID);
    registry.add("pms.auth.bootstrap-admin.email", () -> BOOTSTRAP_ADMIN_EMAIL);
    registry.add("pms.auth.bootstrap-admin.password", () -> BOOTSTRAP_ADMIN_PASSWORD);
  }

  protected static String uniqueEmail() {
    return "user-" + UUID.randomUUID() + "@pms.test";
  }

  protected static String randomPassword() {
    return "Pw-" + UUID.randomUUID();
  }

  protected static String bearer(String accessToken) {
    return "Bearer " + accessToken;
  }

  protected ResultActions postJson(String path, String json) throws Exception {
    return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json));
  }

  protected ResultActions postJson(String path, String json, String accessToken) throws Exception {
    return mockMvc.perform(
        post(path)
            .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json));
  }

  protected static String loginJson(String email, String password) {
    return "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password);
  }

  protected static String refreshJson(String refreshToken) {
    return "{\"refreshToken\":\"%s\"}".formatted(refreshToken);
  }

  protected static String staffJson(String email, String password, String role) {
    return "{\"email\":\"%s\",\"password\":\"%s\",\"role\":\"%s\"}"
        .formatted(email, password, role);
  }

  protected static String patientAccountJson(String email, String password, UUID patientId) {
    return "{\"email\":\"%s\",\"password\":\"%s\",\"patientId\":\"%s\"}"
        .formatted(email, password, patientId);
  }

  /** Logs in and returns the raw token response body. */
  protected String login(String email, String password) throws Exception {
    return postJson("/auth/login", loginJson(email, password))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  protected String adminAccessToken() throws Exception {
    return JsonPath.read(login(BOOTSTRAP_ADMIN_EMAIL, BOOTSTRAP_ADMIN_PASSWORD), "$.accessToken");
  }

  /** Creates a staff account through the admin API and returns its id. */
  protected String createStaff(String email, String password, String role) throws Exception {
    String body =
        postJson("/auth/admin/users", staffJson(email, password, role), adminAccessToken())
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.id");
  }

  /** Creates a PATIENT account through the admin API and returns its id. */
  protected String createPatientAccount(String email, String password, UUID patientId)
      throws Exception {
    String body =
        postJson(
                "/auth/admin/patients",
                patientAccountJson(email, password, patientId),
                adminAccessToken())
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.id");
  }

  /** A fresh DOCTOR account's token response (logged in once). */
  protected String newDoctorSession() throws Exception {
    String email = uniqueEmail();
    String password = randomPassword();
    createStaff(email, password, "DOCTOR");
    return login(email, password);
  }

  /** The stored revocation reason of a raw refresh token, or null while it is active. */
  protected String revocationReason(String rawRefreshToken) {
    return jdbcTemplate.queryForObject(
        "select revocation_reason from refresh_tokens where token_hash = ?",
        String.class,
        TokenHashing.sha256Hex(rawRefreshToken));
  }

  protected void deactivate(String userId) {
    jdbcTemplate.update("update users set is_active = false where id = ?::uuid", userId);
  }
}
