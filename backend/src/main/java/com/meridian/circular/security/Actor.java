package com.meridian.circular.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller-method parameter that should be resolved to the
 * signed-in {@code AppUser} from the {@code X-User-Id} request header.
 *
 * <p>Production sign-in is Azure AD SSO (BRD FR-AUTH-01); for this dev build the
 * frontend supplies the authenticated user's id in a header. Swap the resolver
 * for a real OIDC token principal later — controllers stay unchanged.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface Actor {
    /** When true (default), a missing/unknown user yields HTTP 401. */
    boolean required() default true;
}
