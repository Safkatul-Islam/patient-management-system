package org.pms.authservice.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

/** The shipped configuration, not a test override: the driver's timeouts must stay explicit. */
class DatabaseTimeoutsTest {

  private static final String DRIVER_PROPERTY = "spring.datasource.hikari.data-source-properties";

  @Test
  @DisplayName(
      "PgJDBC socket and connect timeouts are set, so a hung database cannot block forever")
  void driverTimeoutsAreConfigured() throws IOException {
    Properties properties =
        PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));

    assertThat(properties.getProperty(DRIVER_PROPERTY + "[socketTimeout]")).isEqualTo("30");
    assertThat(properties.getProperty(DRIVER_PROPERTY + "[connectTimeout]")).isEqualTo("10");
  }
}
