package com.meridian.circular.repo;

import com.meridian.circular.domain.AppConfig;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Data access for the per-workspace {@link AppConfig} key/value store. The PK is
 * the composite {@link AppConfig.Key} {@code (tenantId, configKey)};
 * {@link #findAllByTenantId} backs the workspace-scoped Config listing.
 */
public interface AppConfigRepository extends JpaRepository<AppConfig, AppConfig.Key> {

    /** All config entries for the given workspace. */
    List<AppConfig> findAllByTenantId(Integer tenantId);
}
