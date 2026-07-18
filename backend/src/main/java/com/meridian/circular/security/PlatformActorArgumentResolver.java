package com.meridian.circular.security;

import com.meridian.circular.domain.PlatformAdmin;
import com.meridian.circular.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Supplies a {@link PlatformActor}-annotated {@link PlatformAdmin} parameter
 * from the super admin authenticated by {@link PlatformAdminInterceptor}.
 */
@Component
public class PlatformActorArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(PlatformActor.class)
                && parameter.getParameterType().equals(PlatformAdmin.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {
        HttpServletRequest req = webRequest.getNativeRequest(HttpServletRequest.class);
        Object actor = req == null ? null
                : req.getAttribute(PlatformAdminInterceptor.PLATFORM_ACTOR_ATTRIBUTE);
        if (actor instanceof PlatformAdmin admin) {
            return admin;
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED, "Super-admin sign-in required.");
    }
}
