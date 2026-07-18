package com.meridian.circular.security;

import com.meridian.circular.domain.AppUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import com.meridian.circular.web.ApiException;

/**
 * Supplies an {@link Actor}-annotated {@code AppUser} parameter from the user
 * authenticated by {@link BearerAuthInterceptor} (the verified bearer token).
 * The interceptor has already rejected unauthenticated requests, so by the time
 * a handler runs the actor attribute is present for any guarded endpoint.
 */
@Component
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(Actor.class)
                && parameter.getParameterType().equals(AppUser.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {
        Actor meta = parameter.getParameterAnnotation(Actor.class);
        boolean required = meta == null || meta.required();

        HttpServletRequest req = webRequest.getNativeRequest(HttpServletRequest.class);
        Object actor = req == null ? null : req.getAttribute(BearerAuthInterceptor.ACTOR_ATTRIBUTE);

        if (actor instanceof AppUser user) {
            return user;
        }
        if (required) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Sign-in required.");
        }
        return null;
    }
}
