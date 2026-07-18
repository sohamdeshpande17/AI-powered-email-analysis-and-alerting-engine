package com.meridian.circular.repo;

import com.meridian.circular.domain.Circular;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Data access for {@link Circular}. Maps the unqualified {@code circular} table,
 * which Hibernate resolves to the acting tenant's schema via the connection
 * {@code search_path} - so every query is automatically scoped to the workspace;
 * no explicit tenant filter is needed. PK is {@code circular_no}.
 */
public interface CircularRepository extends JpaRepository<Circular, String> {

    /** The acting workspace's copy of a raw circular - the URL-safe API identifier. */
    Optional<Circular> findByRawCircularId(UUID rawCircularId);
}
