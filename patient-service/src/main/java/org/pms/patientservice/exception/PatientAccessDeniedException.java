package org.pms.patientservice.exception;

/**
 * The caller may not access the requested patient record (a PATIENT asking for someone else's). The
 * message is fixed on purpose: which record was requested, and by whom, must not reach logs.
 */
public class PatientAccessDeniedException extends RuntimeException {
  public PatientAccessDeniedException() {
    super("Caller may not access the requested patient record");
  }
}
