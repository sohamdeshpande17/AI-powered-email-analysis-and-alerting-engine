package com.meridian.circular.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller-method parameter resolved to the authenticated
 * {@link com.meridian.circular.domain.PlatformAdmin} (super admin) for
 * {@code /api/admin/**} endpoints. The {@link PlatformAdminInterceptor} has
 * already authenticated the request, so the actor is always present.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface PlatformActor {
}
