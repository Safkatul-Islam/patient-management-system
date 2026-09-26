package org.pms.authservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.RSAKey;
import java.time.Clock;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pms.authservice.support.TestRsaKeys;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** A missing or unusable signing key must stop startup, never surface on the first request. */
class SigningKeyConfigTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
          .withUserConfiguration(PropertiesConfig.class, JwtConfig.class)
          .withPropertyValues(
              "pms.auth.jwt.issuer=http://auth-service:4005",
              "pms.auth.jwt.audience=pms-api",
              "pms.auth.jwt.access-token-ttl=15m");

  @Test
  @DisplayName("Valid key: context starts, public key is derived, kid applied")
  void validKeyStarts() {
    contextRunner
        .withPropertyValues(
            "pms.auth.jwt.key-id=kid-1",
            "pms.auth.jwt.private-key=" + TestRsaKeys.signingPrivateKeyBase64())
        .run(
            context -> {
              assertThat(context).hasNotFailed().hasSingleBean(JwtDecoder.class);
              RSAKey jwk = context.getBean(RSAKey.class);
              assertThat(jwk.getKeyID()).isEqualTo("kid-1");
              assertThat(jwk.toRSAPublicKey()).isEqualTo(TestRsaKeys.signingPublicKey());
            });
  }

  @Test
  @DisplayName("Missing key fails startup")
  void missingKeyFails() {
    contextRunner
        .withPropertyValues("pms.auth.jwt.key-id=kid-1", "pms.auth.jwt.private-key=")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  @DisplayName("Missing key id fails startup")
  void missingKeyIdFails() {
    contextRunner
        .withPropertyValues("pms.auth.jwt.private-key=" + TestRsaKeys.signingPrivateKeyBase64())
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  @DisplayName("Garbage key fails startup without echoing the value")
  void garbageKeyFails() {
    contextRunner
        .withPropertyValues("pms.auth.jwt.key-id=kid-1", "pms.auth.jwt.private-key=not-a-key!!")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .rootCause()
                  .hasMessageNotContaining("not-a-key");
            });
  }

  @Test
  @DisplayName("Loader rejects non-base64, non-PKCS#8, non-RSA and short keys")
  void loaderRejectsBadKeys() {
    String validBase64ButNotAKey = Base64.getEncoder().encodeToString("hello".getBytes());
    String ecKey = TestRsaKeys.base64Pkcs8(TestRsaKeys.generate("EC", 256));
    String shortRsaKey = TestRsaKeys.base64Pkcs8(TestRsaKeys.generate(1024));

    assertThatThrownBy(() -> SigningKeyLoader.load(" ")).hasMessageContaining("is not set");
    assertThatThrownBy(() -> SigningKeyLoader.load("%%%"))
        .hasMessageContaining("not valid single-line standard base64");
    assertThatThrownBy(() -> SigningKeyLoader.load(validBase64ButNotAKey))
        .hasMessageContaining("not a DER-encoded PKCS#8 RSA private key");
    assertThatThrownBy(() -> SigningKeyLoader.load(ecKey))
        .hasMessageContaining("not a DER-encoded PKCS#8 RSA private key");
    assertThatThrownBy(() -> SigningKeyLoader.load(shortRsaKey))
        .hasMessageContaining("shorter than 2048 bits");
  }

  @Test
  @DisplayName("Loader derives the matching public key")
  void loaderDerivesPublicKey() {
    SigningKeyLoader.RsaKeyPair keys = SigningKeyLoader.load(TestRsaKeys.signingPrivateKeyBase64());

    assertThat(keys.publicKey()).isEqualTo(TestRsaKeys.signingPublicKey());
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(JwtProperties.class)
  static class PropertiesConfig {

    @Bean
    Clock clock() {
      return Clock.systemUTC();
    }
  }
}
