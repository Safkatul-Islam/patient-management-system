package org.pms.common.web.correlation;

import io.micrometer.context.ThreadLocalAccessor;
import org.slf4j.MDC;

/**
 * Lets Micrometer context propagation move the correlation ID between the Reactor context (where
 * {@link CorrelationIdWebFilter} writes it) and the SLF4J MDC of the thread currently running.
 */
public class MdcCorrelationIdAccessor implements ThreadLocalAccessor<String> {

  @Override
  public Object key() {
    return CorrelationIds.MDC_KEY;
  }

  @Override
  public String getValue() {
    return MDC.get(CorrelationIds.MDC_KEY);
  }

  @Override
  public void setValue(String value) {
    MDC.put(CorrelationIds.MDC_KEY, value);
  }

  @Override
  public void setValue() {
    MDC.remove(CorrelationIds.MDC_KEY);
  }
}
