package com.meridian.circular;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import com.meridian.circular.config.AuthTokenConfig;
import com.meridian.circular.config.GraphMailConfig;
import com.meridian.circular.config.SsoConfig;

/**
 * Entry point for the Circular Analyser backend.
 *
 * <p>A Compliance-team workflow platform. Packaged as a WAR — it runs as an
 * executable WAR ({@code java -jar circular-analyser.war}) and can also be
 * deployed to an external servlet container. Targets Java 21; persists to the
 * PostgreSQL schema in {@code database/schema.sql}. The built React SPA is
 * bundled under {@code classpath:/static} and served by the same WAR.
 */
@SpringBootApplication
@EnableConfigurationProperties({GraphMailConfig.class, SsoConfig.class, AuthTokenConfig.class})
public class CircularAnalyserApplication extends SpringBootServletInitializer {

    static {
        // The PostgreSQL JDBC driver sends the JVM's default time-zone ID as a
        // connection-startup parameter. Windows JVMs can resolve the India zone
        // to the legacy alias "Asia/Calcutta", which some PostgreSQL builds
        // reject ("invalid value for parameter TimeZone"). Pin the canonical
        // zone here — this static block runs at class load, before Spring
        // opens any datasource connection, so it fixes both `java -jar` and
        // external-container (WAR) deployments.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
    }

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
        return builder.sources(CircularAnalyserApplication.class);
    }

    public static void main(String[] args) {
        SpringApplication.run(CircularAnalyserApplication.class, args);
    }
}
