package org.pms.common.web.correlation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds a correlation ID to every servlet request: reuses the caller's {@value #HEADER} when it is
 * well-formed, otherwise generates one (see {@link CorrelationIds}). The ID is put in the MDC under
 * {@value #MDC_KEY} (so structured logs carry it) and echoed back on the response.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

  public static final String HEADER = CorrelationIds.HEADER;
  public static final String MDC_KEY = CorrelationIds.MDC_KEY;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String correlationId = CorrelationIds.resolve(request.getHeader(HEADER));
    MDC.put(MDC_KEY, correlationId);
    response.setHeader(HEADER, correlationId);
    try {
      filterChain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }
}
