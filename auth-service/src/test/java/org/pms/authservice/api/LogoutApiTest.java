package org.pms.authservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

class LogoutApiTest extends AbstractAuthApiTest {

  @Test
  @DisplayName("Logout revokes the caller's refresh token")
  void logoutRevokesOwnToken() throws Exception {
    String session = newDoctorSession();
    String access = JsonPath.read(session, "$.accessToken");
    String refresh = JsonPath.read(session, "$.refreshToken");

    postJson("/auth/logout", refreshJson(refresh), access).andExpect(status().isNoContent());

    postJson("/auth/refresh", refreshJson(refresh)).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("Refreshing a logged-out token is a 401 but the user's other sessions keep working")
  void loggedOutTokenIsNotTreatedAsTheft() throws Exception {
    String email = uniqueEmail();
    String password = randomPassword();
    createStaff(email, password, "DOCTOR");
    String phone = login(email, password);
    String laptopRefresh = JsonPath.read(login(email, password), "$.refreshToken");
    String phoneRefresh = JsonPath.read(phone, "$.refreshToken");

    postJson("/auth/logout", refreshJson(phoneRefresh), JsonPath.read(phone, "$.accessToken"))
        .andExpect(status().isNoContent());

    postJson("/auth/refresh", refreshJson(phoneRefresh))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("The refresh token is invalid or expired."));
    postJson("/auth/refresh", refreshJson(laptopRefresh)).andExpect(status().isOk());
    assertThat(revocationReason(phoneRefresh)).isEqualTo("LOGOUT");
  }

  @Test
  @DisplayName("Logout with someone else's refresh token is a 204 that revokes nothing")
  void logoutIgnoresForeignToken() throws Exception {
    String attackerAccess = JsonPath.read(newDoctorSession(), "$.accessToken");
    String victimRefresh = JsonPath.read(newDoctorSession(), "$.refreshToken");

    postJson("/auth/logout", refreshJson(victimRefresh), attackerAccess)
        .andExpect(status().isNoContent());

    postJson("/auth/refresh", refreshJson(victimRefresh)).andExpect(status().isOk());
  }

  @Test
  @DisplayName("Logout with an unknown refresh token is still a 204")
  void logoutWithUnknownTokenIsNoContent() throws Exception {
    String access = JsonPath.read(newDoctorSession(), "$.accessToken");

    postJson("/auth/logout", refreshJson("never-issued"), access).andExpect(status().isNoContent());
  }

  @Test
  @DisplayName("Logout without an access token is a 401 problem with a Bearer challenge")
  void logoutRequiresAccessToken() throws Exception {
    String refresh = JsonPath.read(newDoctorSession(), "$.refreshToken");

    postJson("/auth/logout", refreshJson(refresh))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(401))
        .andExpect(jsonPath("$.instance").value("/auth/logout"));

    postJson("/auth/refresh", refreshJson(refresh)).andExpect(status().isOk());
  }
}
