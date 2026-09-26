package org.pms.patientservice.exception;

import java.util.Collection;

/** A list request asked to sort by a property that is not exposed for sorting. */
public class InvalidSortPropertyException extends RuntimeException {
  public InvalidSortPropertyException(Collection<String> sortableProperties) {
    // Built only from the server's allow-list, never from the rejected input.
    super(
        "Unsupported sort property. Sortable properties: " + String.join(", ", sortableProperties));
  }
}
