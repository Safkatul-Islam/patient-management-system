package org.pms.common.web.problem;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

class ProblemsTest {

  @Test
  void bodyHasStandardMembersFirstAndExtensionsFlattened() {
    ProblemDetail problem =
        Problems.create(HttpStatus.NOT_FOUND, "Not found", "Nothing here.", "/a/b", "corr-9");

    Map<String, Object> body = Problems.toBody(problem);

    assertThat(body)
        .containsExactly(
            Map.entry("type", "about:blank"),
            Map.entry("title", "Not found"),
            Map.entry("status", 404),
            Map.entry("detail", "Nothing here."),
            Map.entry("instance", "/a/b"),
            Map.entry(Problems.CORRELATION_ID_PROPERTY, "corr-9"));
  }

  @Test
  void correlationIdIsOmittedWhenUnknown() {
    Map<String, Object> body =
        Problems.toBody(Problems.create(HttpStatus.FORBIDDEN, "Forbidden", "No.", "/x", null));

    assertThat(body).doesNotContainKey(Problems.CORRELATION_ID_PROPERTY);
  }
}
