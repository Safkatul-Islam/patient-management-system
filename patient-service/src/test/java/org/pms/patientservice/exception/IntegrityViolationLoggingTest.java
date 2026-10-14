package org.pms.patientservice.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

/**
 * A database error message can quote the failing row. Whatever a non-email integrity violation
 * carries, the log line must name only the exception class and the constraint.
 */
@ExtendWith(OutputCaptureExtension.class)
class IntegrityViolationLoggingTest {

  private static final String NAME = "Marker Name 7f3a";
  private static final String EMAIL = "marker-7f3a@example.com";
  private static final String ADDRESS = "7f3a Marker Street";
  private static final String DATE_OF_BIRTH = "1971-02-03";
  private static final String CONSTRAINT = "patients_name_check";

  @Test
  @DisplayName("A non-email integrity violation logs its class and constraint, never field values")
  void nonEmailViolationLogsNoFieldValues(CapturedOutput output) {
    String failingRow =
        "Failing row contains ("
            + NAME
            + ", "
            + EMAIL
            + ", "
            + ADDRESS
            + ", "
            + DATE_OF_BIRTH
            + ")";
    SQLException sqlError = new SQLException("check constraint violated; " + failingRow, "23514");
    DataIntegrityViolationException violation =
        new DataIntegrityViolationException(
            "could not execute statement [" + failingRow + "]",
            new ConstraintViolationException(
                "could not execute statement; " + failingRow, sqlError, CONSTRAINT));

    ResponseEntity<Object> response =
        new GlobalHandlerException()
            .handleDataIntegrityViolation(
                violation, new ServletWebRequest(new MockHttpServletRequest()));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(output.getAll())
        .contains(CONSTRAINT)
        .contains(DataIntegrityViolationException.class.getName())
        .doesNotContain(NAME)
        .doesNotContain(EMAIL)
        .doesNotContain(ADDRESS)
        .doesNotContain(DATE_OF_BIRTH);
  }
}
