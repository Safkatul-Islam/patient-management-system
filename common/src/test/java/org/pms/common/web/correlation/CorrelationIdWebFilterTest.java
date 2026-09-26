package org.pms.common.web.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.context.ContextRegistry;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

class CorrelationIdWebFilterTest {

  private final CorrelationIdWebFilter filter = new CorrelationIdWebFilter();

  @AfterEach
  void resetPropagation() {
    Hooks.disableAutomaticContextPropagation();
  }

  @Test
  void reusesWellFormedInboundIdOnRequestResponseAndContext() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/x").header(CorrelationIds.HEADER, "abc-123_x.y"));
    AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
    AtomicReference<String> inContext = new AtomicReference<>();

    filter
        .filter(
            exchange,
            ex -> {
              forwarded.set(ex);
              return Mono.deferContextual(
                  context -> {
                    inContext.set(context.get(CorrelationIds.MDC_KEY));
                    return Mono.empty();
                  });
            })
        .block();

    assertThat(forwarded.get().getRequest().getHeaders().getFirst(CorrelationIds.HEADER))
        .isEqualTo("abc-123_x.y");
    assertThat(exchange.getResponse().getHeaders().getFirst(CorrelationIds.HEADER))
        .isEqualTo("abc-123_x.y");
    assertThat(inContext.get()).isEqualTo("abc-123_x.y");
  }

  @Test
  void replacesMalformedIdSoItIsNeverForwarded() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/x").header(CorrelationIds.HEADER, "evil value\t" + "a"));
    AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

    filter.filter(exchange, ex -> Mono.fromRunnable(() -> forwarded.set(ex))).block();

    String sent = forwarded.get().getRequest().getHeaders().getFirst(CorrelationIds.HEADER);
    assertThat(UUID.fromString(sent)).isNotNull();
    assertThat(forwarded.get().getRequest().getHeaders().get(CorrelationIds.HEADER)).hasSize(1);
    assertThat(exchange.getResponse().getHeaders().getFirst(CorrelationIds.HEADER)).isEqualTo(sent);
  }

  @Test
  void generatesIdWhenMissing() {
    MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x"));

    filter.filter(exchange, ex -> Mono.empty()).block();

    assertThat(UUID.fromString(exchange.getResponse().getHeaders().getFirst(CorrelationIds.HEADER)))
        .isNotNull();
  }

  @Test
  void idReachesMdcOnAnotherThreadWithAutomaticPropagation() {
    Hooks.enableAutomaticContextPropagation();
    ContextRegistry.getInstance().registerThreadLocalAccessor(new MdcCorrelationIdAccessor());
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/x").header(CorrelationIds.HEADER, "propagated-id"));
    AtomicReference<String> mdcOnWorker = new AtomicReference<>();
    AtomicReference<String> workerThread = new AtomicReference<>();

    filter
        .filter(
            exchange,
            ex ->
                Mono.just(1)
                    .publishOn(Schedulers.boundedElastic())
                    .doOnNext(
                        ignored -> {
                          workerThread.set(Thread.currentThread().getName());
                          mdcOnWorker.set(MDC.get(CorrelationIds.MDC_KEY));
                        })
                    .then())
        .block();

    assertThat(workerThread.get()).isNotEqualTo(Thread.currentThread().getName());
    assertThat(mdcOnWorker.get()).isEqualTo("propagated-id");
    assertThat(MDC.get(CorrelationIds.MDC_KEY)).isNull();
  }
}
