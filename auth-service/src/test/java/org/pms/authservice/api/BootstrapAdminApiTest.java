package org.pms.authservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;

/**
 * The shared context starts with bootstrap variables set and an empty database, so the runner has
 * already created the admin by the time tests run. Skip-when-unset is covered by the unit test.
 */
class BootstrapAdminApiTest extends AbstractAuthApiTest {

  @Autowired private ApplicationRunner bootstrapAdminRunner;

  @Test
  @DisplayName("Bootstrap admin was created on startup and can log in as ADMIN")
  void bootstrapAdminCreated() throws Exception {
    String accessToken =
        JsonPath.read(login(BOOTSTRAP_ADMIN_EMAIL, BOOTSTRAP_ADMIN_PASSWORD), "$.accessToken");

    assertThat(SignedJWT.parse(accessToken).getJWTClaimsSet().getStringListClaim("roles"))
        .containsExactly("ADMIN");
  }

  @Test
  @DisplayName("Running the bootstrap again does not create a second account")
  void bootstrapIsIdempotent() throws Exception {
    bootstrapAdminRunner.run(new DefaultApplicationArguments());

    Integer accounts =
        jdbcTemplate.queryForObject(
            "select count(*) from users where email = ?", Integer.class, BOOTSTRAP_ADMIN_EMAIL);
    assertThat(accounts).isEqualTo(1);
  }
}
