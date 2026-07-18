package com.meridian.circular.security;

import com.nimbusds.jwt.JWTClaimsSet;
import com.meridian.circular.domain.PlatformAdmin;
import com.meridian.circular.repo.PlatformAdminRepository;
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
 * Authenticates super-admin requests to {@code /api/admin/**} from the
 * {@code Authorization: Bearer} platform token. Requires the token's
 * {@code platform = true} claim and loads the active {@link PlatformAdmin};
 * the verified admin is stashed on the request for {@link PlatformActor}
 * parameters. This is real backend authorization — a compliance user's session
 * token (no {@code platform} claim) is rejected with 403.
 *
 * <p>{@code /api/admin/login} is excluded in
 * {@link com.meridian.circular.config.WebConfig}.
 */
@Component
public class PlatformAdminInterceptor implements HandlerInterceptor {

    /** Request attribute under which the authenticated super admin is stored. */
    public static final String PLATFORM_ACTOR_ATTRIBUTE = "circular.platformActor";

    private final AuthTokenService tokens;
    private final PlatformAdminRepository admins;

    public PlatformAdminInterceptor(AuthTokenService tokens, PlatformAdminRepository admins) {
        this.tokens = tokens;
        this.admins = admins;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return deny(response, HttpStatus.UNAUTHORIZED, "Super-admin sign-in required.");
        }

        JWTClaimsSet claims;
        try {
            claims = tokens.verify(header.substring(7).trim());
        } catch (Exception e) {
            return deny(response, HttpStatus.UNAUTHORIZED, "Invalid or expired session token.");
        }

        if (!isPlatformToken(claims)) {
            return deny(response, HttpStatus.FORBIDDEN, "Super-admin access required.");
        }

        PlatformAdmin admin;
        try {
            admin = admins.findById(UUID.fromString(claims.getSubject())).orElse(null);
        } catch (Exception e) {
            return deny(response, HttpStatus.UNAUTHORIZED, "Invalid session token.");
        }
        if (admin == null) {
            return deny(response, HttpStatus.UNAUTHORIZED, "Not a recognised super admin.");
        }
        if (!admin.isActive) {
            return deny(response, HttpStatus.FORBIDDEN, "This super-admin account is disabled.");
        }

        request.setAttribute(PLATFORM_ACTOR_ATTRIBUTE, admin);
        return true;
    }

    /** A platform token carries the {@code platform = true} claim. */
    private static boolean isPlatformToken(JWTClaimsSet claims) {
        try {
            return Boolean.TRUE.equals(claims.getBooleanClaim("platform"));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean deny(HttpServletResponse response, HttpStatus status, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + message.replace("\"", "'") + "\"}");
        return false;
    }
}
