package org.pms.common.web.correlation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

  private final CorrelationIdFilter filter = new CorrelationIdFilter();

  @Test
  void reusesWellFormedInboundId() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(CorrelationIdFilter.HEADER, "abc-123_x.y");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> seenInMdc = new AtomicReference<>();

    filter.doFilter(
        request, response, (req, res) -> seenInMdc.set(MDC.get(CorrelationIdFilter.MDC_KEY)));

    assertThat(seenInMdc.get()).isEqualTo("abc-123_x.y");
    assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("abc-123_x.y");
  }

  @Test
  void generatesIdWhenHeaderMissing() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(new MockHttpServletRequest(), response, new MockFilterChain());

    String generated = response.getHeader(CorrelationIdFilter.HEADER);
    assertThat(UUID.fromString(generated)).isNotNull();
  }

  @Test
  void replacesMalformedIdInsteadOfEchoingIt() throws Exception {
    for (String malformed : new String[] {"evil\r\nX-Injected: 1", "a".repeat(65), "", "a b"}) {
      MockHttpServletRequest request = new MockHttpServletRequest();
      request.addHeader(CorrelationIdFilter.HEADER, malformed);
      MockHttpServletResponse response = new MockHttpServletResponse();

      filter.doFilter(request, response, new MockFilterChain());

      String returned = response.getHeader(CorrelationIdFilter.HEADER);
      assertThat(returned).isNotEqualTo(malformed);
      assertThat(UUID.fromString(returned)).isNotNull();
    }
  }

  @Test
  void clearsMdcAfterRequestEvenWhenChainThrows() {
    MockHttpServletRequest request = new MockHttpServletRequest();

    assertThatThrownBy(
            () ->
                filter.doFilter(
                    request,
                    new MockHttpServletResponse(),
                    (req, res) -> {
                      throw new ServletException("boom");
                    }))
        .isInstanceOf(ServletException.class);

    assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
  }
}
