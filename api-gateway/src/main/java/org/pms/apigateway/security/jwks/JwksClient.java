package org.pms.apigateway.security.jwks;

import com.nimbusds.jose.jwk.JWKSet;
import io.netty.channel.ChannelOption;
import java.net.URI;
import java.text.ParseException;
import java.time.Duration;
import org.pms.apigateway.config.GatewayProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * Fetches the JWK set from the one configured URI, with explicit connect and response timeouts, an
 * overall deadline, a response size cap and no redirects. Only the public members of the keys are
 * kept.
 */
public class JwksClient {

  private final WebClient webClient;
  private final URI uri;
  private final Duration deadline;

  public JwksClient(GatewayProperties.Jwks settings) {
    HttpClient httpClient =
        HttpClient.create()
            .option(
                ChannelOption.CONNECT_TIMEOUT_MILLIS,
                Math.toIntExact(settings.connectTimeout().toMillis()))
            .responseTimeout(settings.responseTimeout())
            .followRedirect(false);
    this.webClient =
        WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .codecs(
                codecs ->
                    codecs
                        .defaultCodecs()
                        .maxInMemorySize(Math.toIntExact(settings.maxSize().toBytes())))
            .build();
    this.uri = settings.uri();
    // Guards against a slow trickle of body bytes, which the per-read response timeout allows.
    this.deadline = settings.connectTimeout().plus(settings.responseTimeout());
  }

  /** Emits the parsed public key set, or a {@link JwksUnavailableException}. */
  public Mono<JWKSet> fetch() {
    return webClient
        .get()
        .uri(uri)
        .accept(MediaType.APPLICATION_JSON)
        .retrieve()
        .bodyToMono(String.class)
        .timeout(deadline)
        .switchIfEmpty(Mono.error(() -> new JwksUnavailableException("Empty JWK set response")))
        .map(JwksClient::parsePublic)
        .onErrorMap(
            ex -> !(ex instanceof JwksUnavailableException),
            ex -> new JwksUnavailableException("JWK set fetch failed", ex));
  }

  private static JWKSet parsePublic(String body) {
    try {
      return JWKSet.parse(body).toPublicJWKSet();
    } catch (ParseException ex) {
      // The parser's message may quote the body; keep only the type.
      throw new JwksUnavailableException("Unparseable JWK set response");
    }
  }
}
