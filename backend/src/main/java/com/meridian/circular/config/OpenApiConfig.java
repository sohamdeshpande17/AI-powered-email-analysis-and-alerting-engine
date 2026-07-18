package com.meridian.circular.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    /** Bearer JWT scheme — the session token from /api/auth/login or /sso. */
    private static final String BEARER_AUTH = "bearerAuth";

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Circular Analyser API")
                        .version("1.0.0")
                        .description("""
                                REST API for the Circular Analyser — Compliance workflow platform.

                                **Authentication:** every `/api/**` call needs a session JWT.
                                1. Call `POST /api/auth/login` (pick a user id from `GET /api/auth/login-options`) \
                                or `POST /api/auth/sso` to get a `token`.
                                2. Click **Authorize** (top right), paste the token into **bearerAuth**, and Authorize. \
                                The token is then sent as `Authorization: Bearer …` on every request."""))
                .components(new Components()
                        .addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .in(SecurityScheme.In.HEADER)
                                .name("Authorization")
                                .description("Session JWT from /api/auth/login or /api/auth/sso. "
                                        + "Paste just the token — Swagger adds the 'Bearer ' prefix.")))
                // Apply the bearer token to every operation by default so
                // "Try it out" sends it once you've authorized.
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
    }
}
