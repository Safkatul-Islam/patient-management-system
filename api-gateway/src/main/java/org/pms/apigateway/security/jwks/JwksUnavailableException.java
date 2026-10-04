package org.pms.apigateway.security.jwks;

/**
 * No signing key set could be obtained, so no token can be verified: an outage of the key source,
 * not a bad token. Rendered as 503, never 401. Messages are fixed strings; causes may name internal
 * hosts and so stay in server logs only.
 */
public class JwksUnavailableException extends RuntimeException {

  public JwksUnavailableException(String message) {
    super(message);
  }

  public JwksUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
