package com.meridian.circular.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Per-workspace key/value config store — an industry-standard tenant override
 * for tunable settings (e.g. reminder send window). The composite PK is
 * {@code (tenant_id, config_key)}, so each workspace has its own value for a
 * given key; the acting tenant is resolved from the authenticated session
 * ({@link com.meridian.circular.security.TenantContext}), not a default.
 *
 * <p>This entity manages only the {@code tenant_id} + audit-ish columns it needs
 * directly (it has a composite {@link Key} via {@link IdClass}); it does not
 * extend {@link TenantAudited}.
 */
@Entity
@Table(name = "app_config")
@IdClass(AppConfig.Key.class)
public class AppConfig {

    /** Owning workspace id ({@code tenant.tenant_id}); part of the composite PK. */
    @Id
    public Integer tenantId;

    /** Config key (e.g. {@code reminder.send_window}); part of the composite PK. */
    @Id
    public String configKey;

    /** Config value as JSONB — scalar, object, or array depending on the key. */
    @JdbcTypeCode(SqlTypes.JSON)
    public Object valueJson;

    /** Human-readable description of what the key controls. */
    public String description;

    /** Id of the user who last updated the value ({@code "user".user_id}); no FK. */
    public UUID updatedBy;

    /** Last-modification timestamp; refreshed on insert and update. */
    public Instant updatedOn;

    /** Refresh {@link #updatedOn} on persist and update. */
    @PrePersist
    @PreUpdate
    void touch() {
        updatedOn = Instant.now();
    }

    /** Composite key class for the {@code (tenantId, configKey)} primary key. */
    public static class Key implements Serializable {
        /** Owning workspace id. */
        public Integer tenantId;
        /** Config key. */
        public String configKey;

        public Key() {
        }

        public Key(Integer tenantId, String configKey) {
            this.tenantId = tenantId;
            this.configKey = configKey;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k
                    && Objects.equals(tenantId, k.tenantId)
                    && Objects.equals(configKey, k.configKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(tenantId, configKey);
        }
    }
}
