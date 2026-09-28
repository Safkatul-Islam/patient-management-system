package org.pms.apigateway.error;

import org.pms.common.web.correlation.CorrelationIds;
import org.pms.common.web.problem.Problems;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes every error the gateway itself produces as the system's one RFC 7807 shape (see {@link
 * Problems}), carrying the correlation ID that {@code CorrelationIdWebFilter} put on the response.
 */
public class ProblemResponseWriter {

  private final JsonMapper jsonMapper;

  public ProblemResponseWriter(JsonMapper jsonMapper) {
    this.jsonMapper = jsonMapper;
  }

  public Mono<Void> write(
      ServerWebExchange exchange, HttpStatus status, String title, String detail) {
    ServerHttpResponse response = exchange.getResponse();
    ProblemDetail problem =
        Problems.create(
            status,
            title,
            detail,
            exchange.getRequest().getPath().value(),
            correlationId(exchange));
    byte[] body = jsonMapper.writeValueAsBytes(Problems.toBody(problem));
    response.setStatusCode(status);
    response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
    response.getHeaders().setContentLength(body.length);
    DataBuffer buffer = response.bufferFactory().wrap(body);
    return response.writeWith(Mono.just(buffer));
  }

  /** The validated ID echoed on the response; never the raw, unvalidated request header. */
  public static String correlationId(ServerWebExchange exchange) {
    return exchange.getResponse().getHeaders().getFirst(CorrelationIds.HEADER);
  }
}
