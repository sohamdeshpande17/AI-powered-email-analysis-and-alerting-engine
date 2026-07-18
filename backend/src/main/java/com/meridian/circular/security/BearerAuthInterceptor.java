package com.meridian.circular.security;

import com.nimbusds.jwt.JWTClaimsSet;
import com.meridian.circular.domain.AppUser;
import com.meridian.circular.domain.PlatformAdmin;
import com.meridian.circular.domain.Tenant;
import com.meridian.circular.repo.AppUserRepository;
import com.meridian.circular.repo.PlatformAdminRepository;
import com.meridian.circular.repo.TenantRepository;
import com.meridian.circular.service.AuthTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Authenticates every compliance API request from the {@code Authorization:
 * Bearer} session token. The verified {@link AppUser} is stashed on the request
 * so the {@link CurrentUserArgumentResolver} can supply it to {@code @Actor}
 * handler params. Login and the super-admin surface are excluded in
 * {@link com.meridian.circular.config.WebConfig}.
 *
 * <p>God-mode: a super admin's <em>platform</em> token (claim {@code platform =
 * true}) is also accepted here when it carries an {@code X-Acting-Tenant} header
 * naming the department to act in. The interceptor then routes to that
 * department's schema and supplies the reserved {@link SuperAdminActor} so every
 * existing tenant-scoped service works unchanged.
 */
@Component
public class BearerAuthInterceptor implements HandlerInterceptor {

    /** Request attribute under which the authenticated user is stored. */
    public static final String ACTOR_ATTRIBUTE = "circular.actor";

    /** Header a super admin sends to choose the department to act in (god-mode). */
    public static final String ACTING_TENANT_HEADER = "X-Acting-Tenant";

    private final AuthTokenService tokens;
    private final AppUserRepository users;
    private final PlatformAdminRepository platformAdmins;
    private final TenantRepository tenants;

    public BearerAuthInterceptor(AuthTokenService tokens, AppUserRepository users,
                                 PlatformAdminRepository platformAdmins, TenantRepository tenants) {
        this.tokens = tokens;
        this.users = users;
        this.platformAdmins = platformAdmins;
        this.tenants = tenants;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws IOException {
        // CORS preflight carries no Authorization header — let it through.
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return deny(response, HttpStatus.UNAUTHORIZED, "Sign-in required (missing bearer token).");
        }

        JWTClaimsSet claims;
        try {
            claims = tokens.verify(header.substring(7).trim());
        } catch (Exception e) {
            return deny(response, HttpStatus.UNAUTHORIZED, "Invalid or expired session token.");
        }

        // God-mode: a super admin acting inside a department via X-Acting-Tenant.
        if (isPlatformToken(claims)) {
            return handlePlatformActing(request, response, claims);
        }

        AppUser user;
        try {
            user = users.findById(UUID.fromString(claims.getSubject())).orElse(null);
        } catch (Exception e) {
            return deny(response, HttpStatus.UNAUTHORIZED, "Invalid session token.");
        }
        if (user == null) {
            return deny(response, HttpStatus.UNAUTHORIZED, "Not authorised for Circular Analyser.");
        }
        if (!user.isActive) {
            return deny(response, HttpStatus.FORBIDDEN, "This account is disabled.");
        }

        // Workspace schema from the verified token routes per-tenant DB access
        // (Hibernate search_path); tenant_id scopes the shared public tables.
        String schema = null;
        try {
            schema = claims.getStringClaim("schema");
        } catch (Exception ignored) {
            // missing/old token claim — leave null (resolves to public)
        }
        request.setAttribute(ACTOR_ATTRIBUTE, user);
        TenantContext.set(user.tenantId, schema);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // Clear the per-request tenant so a pooled thread never leaks it.
        TenantContext.clear();
    }

    private static boolean isPlatformToken(JWTClaimsSet claims) {
        try {
            return Boolean.TRUE.equals(claims.getBooleanClaim("platform"));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Authenticate a super admin acting inside a department: the platform token
     * must still resolve to an active {@link PlatformAdmin}, and the request must
     * name an existing department in the {@value #ACTING_TENANT_HEADER} header.
     * Sets the reserved super-admin actor + that department's schema.
     */
    private boolean handlePlatformActing(HttpServletRequest request, HttpServletResponse response,
                                         JWTClaimsSet claims) throws IOException {
        PlatformAdmin admin;
        try {
            admin = platformAdmins.findById(UUID.fromString(claims.getSubject())).orElse(null);
        } catch (Exception e) {
            return deny(response, HttpStatus.UNAUTHORIZED, "Invalid session token.");
        }
        if (admin == null || !admin.isActive) {
            return deny(response, HttpStatus.FORBIDDEN, "This super-admin account is disabled.");
        }

        String raw = request.getHeader(ACTING_TENANT_HEADER);
        if (raw == null || raw.isBlank()) {
            return deny(response, HttpStatus.BAD_REQUEST,
                    "Select a department to act in (" + ACTING_TENANT_HEADER + " header).");
        }
        Tenant tenant;
        try {
            tenant = tenants.findById(Integer.valueOf(raw.trim())).orElse(null);
        } catch (NumberFormatException e) {
            return deny(response, HttpStatus.BAD_REQUEST, "Invalid acting department.");
        }
        if (tenant == null || !tenant.isActive) {
            return deny(response, HttpStatus.BAD_REQUEST, "Unknown or inactive department.");
        }

        String schema = tenant.code == null ? null : tenant.code.toLowerCase();
        request.setAttribute(ACTOR_ATTRIBUTE, SuperAdminActor.synthetic(tenant.tenantId));
        TenantContext.set(tenant.tenantId, schema);
        return true;
    }

    private boolean deny(HttpServletResponse response, HttpStatus status, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + message.replace("\"", "'") + "\"}");
        return false;
    }
}
