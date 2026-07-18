package com.meridian.circular.config;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;

/**
 * Schema-per-tenant connection provider. On checkout for a tenant it sets the
 * connection {@code search_path} to that workspace's schema (with {@code public}
 * as fallback for the shared masters/raw layer), so the per-tenant entities
 * resolve to the right schema and the shared tables fall through to public. The
 * tenant identifier is the schema name supplied by {@link SchemaTenantResolver}.
 *
 * <p>The schema name comes from {@code tenant.code} (trusted, DB-sourced) but is
 * still validated against a strict identifier pattern before being interpolated
 * into {@code SET search_path}; anything unexpected falls back to {@code public}.
 * On release the path is reset to {@code public} so a pooled connection never
 * carries a tenant's path to the next, unrelated checkout.
 */
public class SchemaMultiTenantConnectionProvider
        implements MultiTenantConnectionProvider<String> {

    private static final Pattern SAFE_SCHEMA = Pattern.compile("^[a-z][a-z0-9_]*$");
    private static final String PUBLIC = "public";

    private final DataSource dataSource;

    public SchemaMultiTenantConnectionProvider(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Connection getAnyConnection() throws SQLException {
        Connection c = dataSource.getConnection();
        setSearchPath(c, PUBLIC);
        return c;
    }

    @Override
    public void releaseAnyConnection(Connection connection) throws SQLException {
        setSearchPath(connection, PUBLIC);
        connection.close();
    }

    @Override
    public Connection getConnection(String tenantIdentifier) throws SQLException {
        Connection c = dataSource.getConnection();
        setSearchPath(c, safe(tenantIdentifier));
        return c;
    }

    @Override
    public void releaseConnection(String tenantIdentifier, Connection connection)
            throws SQLException {
        setSearchPath(connection, PUBLIC);
        connection.close();
    }

    private static String safe(String schema) {
        return schema != null && SAFE_SCHEMA.matcher(schema).matches() ? schema : PUBLIC;
    }

    private static void setSearchPath(Connection c, String schema) throws SQLException {
        try (Statement st = c.createStatement()) {
            if (PUBLIC.equals(schema)) {
                st.execute("SET search_path TO public");
            } else {
                st.execute("SET search_path TO \"" + schema + "\", public");
            }
        }
    }

    @Override
    public boolean supportsAggressiveRelease() {
        return false;
    }

    @Override
    public boolean isUnwrappableAs(Class<?> unwrapType) {
        return false;
    }

    @Override
    public <T> T unwrap(Class<T> unwrapType) {
        return null;
    }
}
