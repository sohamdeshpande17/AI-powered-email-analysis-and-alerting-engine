package com.meridian.circular.web;

import com.nimbusds.jwt.JWTClaimsSet;
import com.meridian.circular.domain.AppUser;
import com.meridian.circular.dto.Dtos.AuthResponse;
import com.meridian.circular.dto.Dtos.LoginRequest;
import com.meridian.circular.dto.Dtos.SsoAuthResponse;
import com.meridian.circular.dto.Dtos.SsoExchangeRequest;
import com.meridian.circular.dto.Dtos.SsoLoginRequest;
import com.meridian.circular.dto.Dtos.UserDto;
import com.meridian.circular.repo.AppUserRepository;
import com.meridian.circular.repo.TenantRepository;
import com.meridian.circular.security.Actor;
import com.meridian.circular.service.AuthTokenService;
import com.meridian.circular.service.EntraSsoExchangeService;
import com.meridian.circular.service.EntraSsoExchangeService.TokenSet;
import com.meridian.circular.service.SsoTokenValidator;
import com.meridian.circular.service.UserService;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Identity endpoint. Both sign-in paths mint a backend session JWT that the SPA
 * sends as {@code Authorization: Bearer} on every subsequent API call (BRD
 * FR-AUTH):
 * <ul>
 *   <li><b>Microsoft SSO</b> ({@code POST /sso}) — the SPA authenticates against
 *       Entra ID via MSAL and posts the ID token; validated, then exchanged for
 *       a session token. Production path.</li>
 *   <li><b>Temporary direct login</b> ({@code POST /login}) — the SPA picks a
 *       provisioned user; exchanged for a session token. Scaffolding removed
 *       once SSO is signed off.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(AuthController.class);

    private final AppUserRepository users;
    private final TenantRepository tenants;
    private final SsoTokenValidator ssoValidator;
    private final AuthTokenService authTokens;
    private final EntraSsoExchangeService ssoExchange;

    public AuthController(AppUserRepository users, TenantRepository tenants,
                          SsoTokenValidator ssoValidator, AuthTokenService authTokens,
                          EntraSsoExchangeService ssoExchange) {
        this.users = users;
        this.tenants = tenants;
        this.ssoValidator = ssoValidator;
        this.authTokens = authTokens;
        this.ssoExchange = ssoExchange;
    }

    /** Returns the signed-in user resolved from the bearer token (FR-AUTH-04). */
    @GetMapping("/me")
    public UserDto me(@Actor AppUser actor) {
        return UserService.toDto(actor);
    }

    /**
     * Provisioned, active users for the temporary login picker. Unauthenticated
     * (no token exists before sign-in) — dev scaffolding, removed with the
     * direct-login flow once SSO is the only path.
     */
    @GetMapping("/login-options")
    public java.util.List<UserDto> loginOptions() {
        return users.findAll().stream()
                .filter(u -> u.isActive)
                .map(UserService::toDto)
                .toList();
    }

    /**
     * Temporary direct login — exchange a provisioned user id for a session
     * token. No password (dev scaffolding); the user must be active and
     * provisioned. Removed once SSO is the only sign-in path.
     */
    @PostMapping("/login")
    public AuthResponse login(@RequestBody LoginRequest req) {
        if (req == null || req.userId() == null) {
            throw ApiException.badRequest("A user id is required to sign in.");
        }
        AppUser user = users.findById(req.userId())
                .filter(u -> u.isActive)
                .orElseThrow(() -> ApiException.forbidden(
                        "Not provisioned for the Circular Analyser, or the account is disabled."));
        return issueFor(user);
    }

    /**
     * Resolve a Microsoft (Entra ID) SSO account to a provisioned AppUser.
     * Only users added by an Admin or Compliance Head may sign in — an account
     * with no matching active user is rejected (403). Matches on the stable
     * Azure object id first, then email; the oid is backfilled on first match.
     */
    @PostMapping("/sso")
    public AuthResponse sso(@RequestBody SsoLoginRequest req) {
        String oid = req.oid();
        String email = req.email();

        // When validation is enabled, the identity comes from the verified token
        // claims — never from the unauthenticated request body.
        if (ssoValidator.isEnabled()) {
            JWTClaimsSet claims = ssoValidator.validate(req.token());
            oid = stringClaim(claims, "oid");
            email = stringClaim(claims, "preferred_username");
            if (email == null) {
                email = stringClaim(claims, "email");
            }
        }

        return issueFor(resolveUser(oid, email));
    }

    /**
     * Microsoft (Entra ID) SSO sign-in via the server-side authorization-code
     * flow. The SPA posts the single-use {@code code} (plus the PKCE verifier);
     * the backend redeems it for tokens using the client secret, validates the
     * resulting id token, resolves the provisioned AppUser, then returns the
     * backend session token <b>and</b> the Microsoft Graph access token (for the
     * Graph-backed search feature).
     */
    @PostMapping("/sso/exchange")
    public SsoAuthResponse ssoExchange(@RequestBody SsoExchangeRequest req) {
        if (req == null || req.code() == null || req.code().isBlank()) {
            throw ApiException.badRequest("Missing authorization code.");
        }

        // 1) Redeem the code server-side (confidential client + PKCE).
        TokenSet tokens = ssoExchange.redeem(req.code(), req.redirectUri(), req.codeVerifier());

        // 2) Identify the user from the id token. When validation is enabled the
        //    identity comes from the verified token claims (issuer/audience/sig);
        //    in dev (validation off) we read the claims unverified.
        JWTClaimsSet claims = ssoValidator.isEnabled()
                ? ssoValidator.validate(tokens.idToken())
                : parseUnverified(tokens.idToken());
        String oid = stringClaim(claims, "oid");
        String email = stringClaim(claims, "preferred_username");
        if (email == null) {
            email = stringClaim(claims, "email");
        }

        // 3) Resolve the provisioned user and mint the session token.
        AppUser user = resolveUser(oid, email);
        AuthResponse session = issueFor(user);

        // 4) Hand back both tokens: session token for app APIs, Graph access
        //    token for the search feature.
        return new SsoAuthResponse(session.token(), tokens.accessToken(),
                tokens.expiresInSeconds(), session.user());
    }

    /**
     * Resolve a Microsoft account (Azure object id and/or email) to a
     * provisioned, active AppUser. Matches on the stable oid first, then email;
     * backfills the oid on first match. Rejects unprovisioned/disabled accounts
     * (403).
     */
    private AppUser resolveUser(String oid, String email) {
        boolean hasOid = oid != null && !oid.isBlank();
        boolean hasEmail = email != null && !email.isBlank();
        if (!hasOid && !hasEmail) {
            throw ApiException.badRequest(
                    "Microsoft sign-in did not return an account identifier.");
        }

        AppUser user = null;
        if (hasOid) {
            user = users.findByAzureOid(oid).orElse(null);
        }
        if (user == null && hasEmail) {
            user = users.findByEmailIgnoreCase(email).orElse(null);
        }
        if (user == null || !user.isActive) {
            throw ApiException.forbidden(
                    "This Microsoft account is not provisioned for the Circular "
                    + "Analyser. Ask an administrator to add you first.");
        }

        // Backfill the Azure object id the first time a user signs in via SSO so
        // later logins resolve by the stable oid rather than a mutable email.
        if (hasOid && (user.azureOid == null || user.azureOid.isBlank())) {
            user.azureOid = oid;
        }
        return user;
    }

    /** Parse id-token claims WITHOUT signature verification (dev-only path). */
    private static JWTClaimsSet parseUnverified(String idToken) {
        try {
            return com.nimbusds.jwt.SignedJWT.parse(idToken).getJWTClaimsSet();
        } catch (Exception e) {
            throw ApiException.badRequest("Could not read the Microsoft sign-in token.");
        }
    }

    /** Stamp the login time, mint a session token, and return it with the user. */
    private AuthResponse issueFor(AppUser user) {
        user.lastLoginAt = Instant.now();
        users.save(user);
        // Resolve the user's workspace schema (lower(tenant.code)) so the token
        // can route DB access without a per-request tenant lookup.
        String schema = tenants.findById(user.tenantId)
                .map(t -> t.code == null ? null : t.code.toLowerCase())
                .orElse(null);
        log.info("Session token issued — user={} role={} tenant={} schema={} id={}",
                user.email, user.roleId, user.tenantId, schema, user.userId);
        return new AuthResponse(authTokens.issue(user, schema), UserService.toDto(user));
    }

    /** Read a string claim, tolerating a missing claim or parse error. */
    private static String stringClaim(JWTClaimsSet claims, String name) {
        try {
            return claims.getStringClaim(name);
        } catch (Exception e) {
            return null;
        }
    }
}
