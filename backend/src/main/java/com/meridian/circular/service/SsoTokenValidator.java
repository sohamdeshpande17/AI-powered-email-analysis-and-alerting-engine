package com.meridian.circular.service;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.meridian.circular.config.SsoConfig;
import com.meridian.circular.web.ApiException;
import java.net.URL;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Validates Microsoft Entra ID (Azure AD) ID tokens posted by the SPA on
 * sign-in. The organisation is single (MASK), so the validator verifies the
 * RS256 signature against the configured JWKS and checks the configured issuer
 * and audience (and exp/iat) from {@code app.sso.*}. A token from any other
 * Entra tenant fails the issuer check.
 */
@Service
public class SsoTokenValidator {

    private static final Logger log = LoggerFactory.getLogger(SsoTokenValidator.class);

    private final SsoConfig config;
    private volatile DefaultJWTProcessor<SecurityContext> processor;

    public SsoTokenValidator(SsoConfig config) {
        this.config = config;
    }

    /** Global SSO toggle; when off, the endpoint trusts posted account fields (dev). */
    public boolean isEnabled() {
        return config.enabled();
    }

    /**
     * Validate the token against the configured organisation and return its
     * claims. Throws {@link ApiException} (401) if the token is missing,
     * malformed, expired, or fails signature / issuer / audience checks.
     */
    public JWTClaimsSet validate(String token) {
        if (token == null || token.isBlank()) {
            throw unauthorized("Missing sign-in token.");
        }
        if (config.jwksUri() == null || config.issuer() == null || config.audience() == null) {
            log.warn("SSO is enabled but app.sso.jwksUri/issuer/audience are not fully configured");
            throw unauthorized("Sign-in is not configured.");
        }
        try {
            return processor().process(token, null);
        } catch (Exception e) {
            log.warn("SSO token validation failed: {}", e.getMessage());
            throw unauthorized("Invalid or expired Microsoft sign-in token.");
        }
    }

    /** Lazily build and cache the single Nimbus processor (the JWKS caches keys). */
    private DefaultJWTProcessor<SecurityContext> processor() throws Exception {
        DefaultJWTProcessor<SecurityContext> cached = processor;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (processor == null) {
                DefaultJWTProcessor<SecurityContext> p = new DefaultJWTProcessor<>();
                JWKSource<SecurityContext> keys = new RemoteJWKSet<>(new URL(config.jwksUri()));
                JWSKeySelector<SecurityContext> keySelector =
                        new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys);
                p.setJWSKeySelector(keySelector);
                p.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
                        config.audience(),
                        new JWTClaimsSet.Builder().issuer(config.issuer()).build(),
                        Set.of("sub", "exp", "iat")));
                processor = p;
            }
            return processor;
        }
    }

    private static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, message);
    }
}
