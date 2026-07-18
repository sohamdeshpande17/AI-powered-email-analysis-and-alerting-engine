package com.meridian.circular.domain;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite primary key for {@link Role}: a role id is unique only within its
 * owning department, so the key is {@code (tenantId, id)}.
 */
public class RoleId implements Serializable {

    public Integer tenantId;
    public String id;

    public RoleId() {}

    public RoleId(Integer tenantId, String id) {
        this.tenantId = tenantId;
        this.id = id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RoleId other)) return false;
        return Objects.equals(tenantId, other.tenantId) && Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenantId, id);
    }
}
