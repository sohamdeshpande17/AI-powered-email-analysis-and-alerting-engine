package com.meridian.circular.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Exposes a BCrypt {@link PasswordEncoder} for the super-admin
 * ({@code platform_admin}) password login. This uses only the standalone
 * {@code spring-security-crypto} utilities — it does not enable Spring
 * Security's web filter chain, so the existing custom JWT bearer auth is
 * unaffected.
 */
@Configuration
public class SecurityCryptoConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
