package com.meridian.circular.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding for {@code app.auth.*} — the backend-issued session JWT used
 * for bearer authentication on every API call (see
 * {@link com.meridian.circular.service.AuthTokenService}).
 *
 * <p>{@code secret} signs the HS256 token and MUST be at least 32 characters
 * (256 bits); override it via env in any shared/prod environment.
 */
@ConfigurationProperties(prefix = "app.auth")
public record AuthTokenConfig(
        String secret,
        String issuer,
        long ttlMinutes
) {}
