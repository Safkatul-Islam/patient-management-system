package org.pms.common.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pms.common.web.correlation.CorrelationIds;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import tools.jackson.databind.json.JsonMapper;

class SecurityProblemHandlersTest {

  private final SecurityProblemWriter writer =
      new SecurityProblemWriter(JsonMapper.builder().build());

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  void entryPointWritesProblemWithServiceChallengeAndDetail() throws Exception {
    MDC.put(CorrelationIds.MDC_KEY, "corr-1");
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/things/1");
    MockHttpServletResponse response = new MockHttpServletResponse();
    ProblemAuthenticationEntryPoint entryPoint =
        new ProblemAuthenticationEntryPoint(writer, ex -> "Bearer", "Identity required.");

    entryPoint.commence(request, response, new BadCredentialsException("secret reason"));

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
    String body = response.getContentAsString();
    assertThat(JsonPath.<String>read(body, "$.type")).isEqualTo("about:blank");
    assertThat(JsonPath.<String>read(body, "$.title")).isEqualTo("Unauthorized");
    assertThat(JsonPath.<Integer>read(body, "$.status")).isEqualTo(401);
    assertThat(JsonPath.<String>read(body, "$.detail")).isEqualTo("Identity required.");
    assertThat(JsonPath.<String>read(body, "$.instance")).isEqualTo("/things/1");
    assertThat(JsonPath.<String>read(body, "$.correlationId")).isEqualTo("corr-1");
    assertThat(body).doesNotContain("secret reason");
  }

  @Test
  void accessDeniedHandlerWritesForbiddenProblem() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();

    new ProblemAccessDeniedHandler(writer)
        .handle(
            new MockHttpServletRequest("DELETE", "/things/1"),
            response,
            new AccessDeniedException("internal rule name"));

    assertThat(response.getStatus()).isEqualTo(403);
    String body = response.getContentAsString();
    assertThat(JsonPath.<String>read(body, "$.title")).isEqualTo("Forbidden");
    assertThat(body).doesNotContain("internal rule name").doesNotContain("correlationId");
  }
}
