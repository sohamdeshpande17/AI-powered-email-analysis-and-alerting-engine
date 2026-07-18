package com.meridian.circular.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A department workspace (Compliance, Legal, Regulatory). Shared master in
 * {@code public.tenant}. {@code lower(code)} is the workspace's schema name
 * (e.g. {@code COMPLIANCE} -> schema {@code compliance}); the login flow
 * resolves it from the signed-in user's {@code tenant_id} and stamps it into
 * the session token so requests route to the right schema.
 */
@Entity
@Table(name = "tenant")
public class Tenant {

    @Id
    public Integer tenantId;

    public String code;
    public String name;
    public boolean isActive = true;
}
