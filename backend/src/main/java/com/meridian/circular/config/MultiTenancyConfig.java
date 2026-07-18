package com.meridian.circular.config;

import javax.sql.DataSource;
import org.hibernate.cfg.AvailableSettings;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires Hibernate schema-per-tenant multitenancy: registers the
 * {@link SchemaMultiTenantConnectionProvider} (sets the connection
 * {@code search_path} per request) and the {@link SchemaTenantResolver} (reads
 * the acting schema from the verified bearer token). Presence of a
 * connection provider enables multitenancy in Hibernate 6; the schema strategy
 * follows from the provider switching {@code search_path} on the same connection.
 */
@Configuration
public class MultiTenancyConfig {

    @Bean
    public HibernatePropertiesCustomizer multiTenancyHibernateCustomizer(DataSource dataSource) {
        SchemaMultiTenantConnectionProvider connectionProvider =
                new SchemaMultiTenantConnectionProvider(dataSource);
        SchemaTenantResolver tenantResolver = new SchemaTenantResolver();
        return props -> {
            props.put(AvailableSettings.MULTI_TENANT_CONNECTION_PROVIDER, connectionProvider);
            props.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, tenantResolver);
        };
    }
}
