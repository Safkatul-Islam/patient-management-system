package org.pms.common.web.problem;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.pms.common.web.correlation.CorrelationIdFilter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

class ProblemDetailsExceptionHandlerTest {

  private static final String CORRELATION_ID = "test-correlation-id";

  record Payload(@NotBlank(message = "name is required") String name) {}

  static class WidgetMissingException extends RuntimeException {}

  @RestController
  static class TestController {
    @PostMapping("/widgets")
    String create(@Valid @RequestBody Payload payload) {
      return payload.name();
    }

    @GetMapping("/boom")
    String boom() {
      throw new IllegalStateException("internal secret detail");
    }

    @GetMapping("/missing")
    String missing() {
      throw new WidgetMissingException();
    }
  }

  @RestControllerAdvice
  static class TestExceptionHandler extends ProblemDetailsExceptionHandler {
    @ExceptionHandler(WidgetMissingException.class)
    ResponseEntity<Object> handleMissing(WidgetMissingException ex, WebRequest request) {
      return respond(ex, HttpStatus.NOT_FOUND, "Widget not found", "No such widget.", request);
    }
  }

  private final MockMvc mockMvc =
      MockMvcBuilders.standaloneSetup(new TestController())
          .setControllerAdvice(new TestExceptionHandler())
          .addFilters(new CorrelationIdFilter())
          .build();

  @Test
  void validationFailureIsProblemWithFieldErrors() throws Exception {
    mockMvc
        .perform(
            post("/widgets")
                .header(CorrelationIdFilter.HEADER, CORRELATION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.title").value("Validation failed"))
        .andExpect(jsonPath("$.errors[0].field").value("name"))
        .andExpect(jsonPath("$.errors[0].message").value("name is required"))
        .andExpect(jsonPath("$.correlationId").value(CORRELATION_ID));
  }

  @Test
  void malformedBodyIsGeneric400() throws Exception {
    mockMvc
        .perform(post("/widgets").contentType(MediaType.APPLICATION_JSON).content("{not json"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Malformed request body"));
  }

  @Test
  void unexpectedExceptionIsGeneric500WithoutInternalDetail() throws Exception {
    mockMvc
        .perform(get("/boom"))
        .andExpect(status().isInternalServerError())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
        .andExpect(content().string(not(containsString("internal secret detail"))))
        .andExpect(content().string(not(containsString("IllegalStateException"))));
  }

  @Test
  void serviceSpecificHandlerUsesSameShape() throws Exception {
    mockMvc
        .perform(get("/missing").header(CorrelationIdFilter.HEADER, CORRELATION_ID))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Widget not found"))
        .andExpect(jsonPath("$.instance").value("/missing"))
        .andExpect(jsonPath("$.correlationId").value(CORRELATION_ID))
        .andExpect(header().string(CorrelationIdFilter.HEADER, CORRELATION_ID));
  }

  @Test
  void frameworkErrorsAlsoCarryCorrelationId() throws Exception {
    mockMvc
        .perform(post("/boom").header(CorrelationIdFilter.HEADER, CORRELATION_ID))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.correlationId").value(CORRELATION_ID));
  }
}
