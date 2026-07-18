package com.meridian.circular.config;

import com.meridian.circular.security.BearerAuthInterceptor;
import com.meridian.circular.security.CurrentUserArgumentResolver;
import com.meridian.circular.security.PlatformActorArgumentResolver;
import com.meridian.circular.security.PlatformAdminInterceptor;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers CORS for the React dev server, the {@code @Actor} /
 * {@code @PlatformActor} resolvers, and the two authentication interceptors:
 * the compliance bearer-token interceptor on {@code /api/**} and the super-admin
 * platform interceptor on {@code /api/admin/**}.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final CurrentUserArgumentResolver currentUserResolver;
    private final PlatformActorArgumentResolver platformActorResolver;
    private final BearerAuthInterceptor bearerAuthInterceptor;
    private final PlatformAdminInterceptor platformAdminInterceptor;

    @Value("${app.cors.allowed-origins}")
    private String[] allowedOrigins;

    public WebConfig(CurrentUserArgumentResolver currentUserResolver,
                     PlatformActorArgumentResolver platformActorResolver,
                     BearerAuthInterceptor bearerAuthInterceptor,
                     PlatformAdminInterceptor platformAdminInterceptor) {
        this.currentUserResolver = currentUserResolver;
        this.platformActorResolver = platformActorResolver;
        this.bearerAuthInterceptor = bearerAuthInterceptor;
        this.platformAdminInterceptor = platformAdminInterceptor;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Every compliance API call requires a valid bearer token, except the
        // login endpoints. The super-admin surface (/api/admin/**) is guarded
        // separately by the platform interceptor, so it is excluded here.
        // (v3: the ingest API is gone — the circular-processor pipeline writes
        // to the DB directly.)
        registry.addInterceptor(bearerAuthInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/login",
                        "/api/auth/login-options",
                        "/api/auth/sso",
                        "/api/auth/sso/exchange",
                        "/api/admin/**");

        // Super-admin endpoints require a platform token; /login is public.
        registry.addInterceptor(platformAdminInterceptor)
                .addPathPatterns("/api/admin/**")
                .excludePathPatterns("/api/admin/login");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserResolver);
        resolvers.add(platformActorResolver);
    }
}
