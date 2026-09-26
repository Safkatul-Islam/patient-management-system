package org.pms.common.web.correlation;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The correlation-ID convention shared by the servlet filter and the reactive web filter: one
 * header name, one MDC key, one rule for which inbound values are accepted.
 */
public final class CorrelationIds {

  public static final String HEADER = "X-Correlation-Id";
  public static final String MDC_KEY = "correlationId";

  // Inbound values end up in logs and response headers, so anything outside this
  // conservative shape (e.g. CR/LF, very long values) is replaced, never echoed.
  private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  private CorrelationIds() {}

  /** Returns {@code candidate} when well-formed, otherwise a freshly generated ID. */
  public static String resolve(String candidate) {
    if (candidate != null && VALID_ID.matcher(candidate).matches()) {
      return candidate;
    }
    return UUID.randomUUID().toString();
  }
}
