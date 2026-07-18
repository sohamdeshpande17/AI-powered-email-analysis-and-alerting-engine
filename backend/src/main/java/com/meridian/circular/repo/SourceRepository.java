package com.meridian.circular.repo;

import com.meridian.circular.domain.Source;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Data access for the global {@link Source} registry (PK = business source code).
 * Sources are shared infrastructure, so no tenant scoping applies; which
 * workspaces a source feeds is held in the {@code source_tenant} mapping.
 */
public interface SourceRepository extends JpaRepository<Source, String> {

    /** All sources, ordered by name (Config → Source registry view). */
    List<Source> findAllByOrderByNameAsc();
}
