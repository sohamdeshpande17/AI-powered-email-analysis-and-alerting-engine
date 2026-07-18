package com.meridian.circular.repo;

import com.meridian.circular.domain.Category;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Data access for the global circular {@link Category} taxonomy (PK = the id the
 * LLM emits). Shared across all workspaces, so no tenant scoping applies.
 */
public interface CategoryRepository extends JpaRepository<Category, String> {
}
