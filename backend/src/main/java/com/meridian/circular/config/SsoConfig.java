package com.meridian.circular.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding for {@code app.sso.*} in application.yml — the Microsoft Entra
 * ID (Azure AD) sign-in token validation settings used by
 * {@link com.meridian.circular.service.SsoTokenValidator}.
 *
 * <p>The SPA authenticates the user and posts the ID token to
 * {@code POST /api/auth/sso}; the backend verifies the signature against the
 * tenant JWKS, checks the issuer and audience, then resolves the AppUser. The
 * audience is the SPA app registration's client id.
 *
 * <p>When {@code enabled = false} the endpoint falls back to trusting the
 * account fields posted by the client (dev / temporary mode).
 *
 * <p>The {@code clientId} / {@code clientSecret} / {@code tenantId} /
 * {@code scopes} fields drive the server-side authorization-code redemption
 * (see {@link com.meridian.circular.service.EntraSsoExchangeService}). The app
 * registration is the confidential-client ("Web") platform, so the secret is
 * required for code redemption and is supplied via the {@code APP_SSO_CLIENT_SECRET}
 * env var — it must never be checked in or exposed to the browser. {@code clientId}
 * equals {@code audience} (the app registration's client id).
 */
@ConfigurationProperties(prefix = "app.sso")
public record SsoConfig(
        String jwksUri,
        String issuer,
        String audience,
        boolean enabled,
        String tenantId,
        String clientId,
        String clientSecret,
        String scopes
) {}
