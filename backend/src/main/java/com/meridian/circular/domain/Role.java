package com.meridian.circular.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * One of the Compliance roles (BRD FR-RBAC-01) — a <strong>per-department</strong>
 * catalog: every workspace (tenant) owns its own set of roles, keyed by
 * {@code (tenant_id, id)}. The standard ids ({@code admin}, {@code compliance_head},
 * {@code compliance_officer}, {@code auditor_readonly}) are seeded into every
 * department so the code's RBAC works everywhere; a department may add its own
 * extra roles. A user's role must belong to that user's department.
 *
 * <p>Composite key via {@link RoleId}. The table lives in {@code public} (with a
 * {@code tenant_id} column, like {@code team}/{@code app_config}) rather than a
 * per-tenant schema, because cross-tenant {@link AppUser} references it.
 */
@Entity
@Table(name = "role")
@IdClass(RoleId.class)
public class Role extends Audited {

    /** Owning department (PK part). */
    @Id
    public Integer tenantId;

    /** Lowercase role id, e.g. {@code admin} (PK part, unique within a tenant). */
    @Id
    public String id;

    /** Human-readable role name. */
    public String name;

    /** Description of the role's responsibilities/permissions. */
    public String description;
}
