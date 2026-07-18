package com.meridian.circular.service;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.meridian.circular.config.AuthTokenConfig;
import com.meridian.circular.domain.AppUser;
import com.meridian.circular.web.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Issues and verifies the backend session JWT (HS256). The token carries the
 * acting user's identity and roles so every API call can authenticate and
 * authorise from the bearer token alone:
 * <ul>
 *   <li>{@code sub} / {@code userId} — the AppUser id</li>
 *   <li>{@code email}, {@code name}</li>
 *   <li>{@code roles} — list of role codes</li>
 *   <li>{@code iss}, {@code iat}, {@code exp} — standard claims</li>
 * </ul>
 */
@Service
public class AuthTokenService {

    private final AuthTokenConfig config;
    private final long ttlMinutes;

    public AuthTokenService(AuthTokenConfig config) {
        this.config = config;
        this.ttlMinutes = config.ttlMinutes() > 0 ? config.ttlMinutes() : 720; // 12h default
        if (config.secret() == null || config.secret().getBytes().length < 32) {
            throw new IllegalStateException(
                    "app.auth.secret must be set and at least 32 characters (256 bits).");
        }
    }

    /**
     * Mint a signed session token for a freshly authenticated user. {@code schema}
     * is the user's workspace schema (lower(tenant.code)); it is carried as a
     * claim so the bearer interceptor can route DB access without a per-request
     * tenant lookup.
     */
    public String issue(AppUser user, String schema) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(user.userId.toString())
                .issuer(config.issuer())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofMinutes(ttlMinutes))))
                .claim("userId", user.userId.toString())
                .claim("email", user.email)
                .claim("name", user.displayName)
                .claim("roles", List.of(user.roleId))
                .claim("tenantId", user.tenantId)
                .claim("schema", schema)
                .build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(config.secret()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to issue auth token", e);
        }
    }

    /**
     * Mint a signed token for a super admin ({@code platform_admin}). Carries a
     * {@code platform = true} claim and NO tenant/schema claim; the
     * {@code PlatformAdminInterceptor} requires the flag, and god-mode acting
     * supplies the acting department via a request header instead.
     */
    public String issuePlatform(com.meridian.circular.domain.PlatformAdmin admin) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(admin.adminId.toString())
                .issuer(config.issuer())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofMinutes(ttlMinutes))))
                .claim("adminId", admin.adminId.toString())
                .claim("username", admin.username)
                .claim("name", admin.displayName)
                .claim("platform", true)
                .build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(config.secret()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to issue platform token", e);
        }
    }

    /**
     * Verify a bearer token's signature and expiry and return its claims.
     * Throws {@link ApiException} (401) on any failure.
     */
    public JWTClaimsSet verify(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!jwt.verify(new MACVerifier(config.secret()))) {
                throw unauthorized();
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Date exp = claims.getExpirationTime();
            if (exp == null || exp.before(new Date())) {
                throw unauthorized();
            }
            return claims;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw unauthorized();
        }
    }

    private static ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "Invalid or expired session token.");
    }
}
