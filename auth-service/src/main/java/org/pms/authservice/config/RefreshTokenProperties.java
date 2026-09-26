package org.pms.authservice.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Refresh-token settings.
 *
 * @param ttl lifetime of a refresh token from the moment it is issued
 */
@Validated
@ConfigurationProperties("pms.auth.refresh-token")
public record RefreshTokenProperties(@NotNull Duration ttl) {}
