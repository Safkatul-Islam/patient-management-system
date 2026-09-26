package org.pms.authservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pms.authservice.service.TokenHashing;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;

class RefreshApiTest extends AbstractAuthApiTest {

  @Test
  @DisplayName("Refresh rotates: new pair issued, the old refresh token stops working")
  void refreshRotates() throws Exception {
    String oldRefresh = JsonPath.read(newDoctorSession(), "$.refreshToken");

    String rotated =
        postJson("/auth/refresh", refreshJson(oldRefresh))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$.accessToken").isString())
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String newRefresh = JsonPath.read(rotated, "$.refreshToken");

    assertThat(newRefresh).isNotEqualTo(oldRefresh);
    postJson("/auth/refresh", refreshJson(oldRefresh)).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("Reusing a rotated token is a 401 and also revokes the newer token")
  void reuseRevokesWholeFamily() throws Exception {
    String first = JsonPath.read(newDoctorSession(), "$.refreshToken");
    String second = refreshOk(first);

    postJson("/auth/refresh", refreshJson(first))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("The refresh token is invalid or expired."));
    assertThat(revocationReason(first)).isEqualTo("ROTATED");
    assertThat(revocationReason(second)).isEqualTo("REUSE_DETECTED");

    postJson("/auth/refresh", refreshJson(second))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("The refresh token is invalid or expired."));
  }

  @Test
  @DisplayName("Reuse revokes only the affected user's tokens")
  void reuseDoesNotAffectOtherUsers() throws Exception {
    String victim = JsonPath.read(newDoctorSession(), "$.refreshToken");
    String bystander = JsonPath.read(newDoctorSession(), "$.refreshToken");
    refreshOk(victim);

    postJson("/auth/refresh", refreshJson(victim)).andExpect(status().isUnauthorized());
    refreshOk(bystander);
  }

  @Test
  @DisplayName("An expired refresh token is a 401")
  void expiredTokenRejected() throws Exception {
    String email = uniqueEmail();
    String userId = createStaff(email, randomPassword(), "NURSE");
    String rawToken = "expired-" + UUID.randomUUID();
    jdbcTemplate.update(
        "insert into refresh_tokens (id, user_id, token_hash, expires_at, created_at)"
            + " values (?, ?::uuid, ?, now() - interval '1 minute', now() - interval '8 days')",
        UUID.randomUUID(),
        userId,
        TokenHashing.sha256Hex(rawToken));

    postJson("/auth/refresh", refreshJson(rawToken)).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("Refresh ignores a stale Authorization header (clients refresh because it expired)")
  void refreshIgnoresBearerHeader() throws Exception {
    String refresh = JsonPath.read(newDoctorSession(), "$.refreshToken");

    postJson("/auth/refresh", refreshJson(refresh), "expired.or.garbage")
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("An unknown refresh token is a 401")
  void unknownTokenRejected() throws Exception {
    postJson("/auth/refresh", refreshJson("never-issued-" + UUID.randomUUID()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("A deactivated user cannot refresh")
  void inactiveUserRejected() throws Exception {
    String email = uniqueEmail();
    String password = randomPassword();
    String userId = createStaff(email, password, "NURSE");
    String refresh = JsonPath.read(login(email, password), "$.refreshToken");
    deactivate(userId);

    postJson("/auth/refresh", refreshJson(refresh)).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("Two concurrent refreshes of one token: exactly one succeeds (row lock)")
  void concurrentRefreshSerializes() throws Exception {
    String token = JsonPath.read(newDoctorSession(), "$.refreshToken");
    CountDownLatch start = new CountDownLatch(1);
    Callable<MvcResult> refresh =
        () -> {
          start.await();
          return postJson("/auth/refresh", refreshJson(token)).andReturn();
        };

    ExecutorService pool = Executors.newFixedThreadPool(2);
    List<MvcResult> results = new ArrayList<>();
    try {
      Future<MvcResult> a = pool.submit(refresh);
      Future<MvcResult> b = pool.submit(refresh);
      start.countDown();
      results.add(a.get(30, TimeUnit.SECONDS));
      results.add(b.get(30, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }

    List<Integer> statuses =
        results.stream().map(r -> r.getResponse().getStatus()).sorted().toList();
    assertThat(statuses).containsExactly(200, 401);

    // The loser presented an already-revoked token, so the winner's new token is revoked too.
    MvcResult winner =
        results.stream().filter(r -> r.getResponse().getStatus() == 200).findFirst().orElseThrow();
    String winnerRefresh =
        JsonPath.read(winner.getResponse().getContentAsString(), "$.refreshToken");
    postJson("/auth/refresh", refreshJson(winnerRefresh)).andExpect(status().isUnauthorized());
  }

  private String refreshOk(String refreshToken) throws Exception {
    String body =
        postJson("/auth/refresh", refreshJson(refreshToken))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.refreshToken");
  }
}
