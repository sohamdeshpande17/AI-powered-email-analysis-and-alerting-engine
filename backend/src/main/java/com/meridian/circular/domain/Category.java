package com.meridian.circular.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Circular category master — ids match what the LLM emits (e.g. {@code regulatory},
 * {@code compliance}). A <strong>global</strong> taxonomy shared by every
 * workspace (multi-tenancy: the LLM output is shared, so this is not
 * tenant-scoped — extends {@link Audited}, not {@link TenantAudited}).
 *
 * <p>v3: table renamed {@code category → circular_category}, {@code code → id}.
 */
@Entity
@Table(name = "circular_category")
public class Category extends Audited {

    /** PK — category id as emitted by the LLM (e.g. {@code regulatory}). */
    @Id
    public String id;

    /** Human-readable category name. */
    public String name;

    /** Description of what the category covers. */
    public String description;

    /** Whether the category is active/selectable. */
    public boolean isActive = true;
}
