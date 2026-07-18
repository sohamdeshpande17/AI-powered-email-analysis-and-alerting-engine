package com.meridian.circular.service;

import com.meridian.circular.domain.PlatformAdmin;
import com.meridian.circular.domain.Role;
import com.meridian.circular.dto.Dtos.CreateRoleRequest;
import com.meridian.circular.dto.Dtos.RoleDto;
import com.meridian.circular.dto.Dtos.UpdateRoleRequest;
import com.meridian.circular.repo.RoleRepository;
import com.meridian.circular.web.ApiException;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-department role-catalog administration for super admins. Every operation
 * is scoped to a department ({@code tenantId}); a role id is unique only within
 * its department.
 *
 * <p>Known limitation: a role's effective privileges are enforced in code (and
 * the frontend), not from this catalog. A newly added role can be assigned to
 * users, but only carries real permissions once the application is taught about
 * it. The catalog is the assignable set of role ids/names, not a permission
 * editor.
 */
@Service
public class RoleService {

    private static final Logger log = LoggerFactory.getLogger(RoleService.class);
    private static final Pattern ID = Pattern.compile("^[a-z][a-z0-9_]{1,39}$");

    private final RoleRepository roles;

    public RoleService(RoleRepository roles) {
        this.roles = roles;
    }

    public List<RoleDto> list(Integer tenantId) {
        return roles.findByTenantId(tenantId).stream()
                .sorted(Comparator.comparing(r -> r.id))
                .map(RoleService::toDto)
                .toList();
    }

    @Transactional
    public RoleDto create(Integer tenantId, CreateRoleRequest req, PlatformAdmin actor) {
        if (req == null || req.id() == null || req.name() == null || req.name().isBlank()) {
            throw ApiException.badRequest("Role id and name are required.");
        }
        String id = req.id().trim().toLowerCase();
        if (!ID.matcher(id).matches()) {
            throw ApiException.badRequest(
                    "Role id must be 2-40 chars, start with a letter, and use only a-z, 0-9, _.");
        }
        if (roles.findByTenantIdAndId(tenantId, id).isPresent()) {
            throw ApiException.badRequest("A role with this id already exists in this department.");
        }
        Role r = new Role();
        r.tenantId = tenantId;
        r.id = id;
        r.name = req.name().trim();
        r.description = req.description();
        roles.save(r);
        log.info("Role created — tenant={} id={} by={}", tenantId, id, actor.username);
        return toDto(r);
    }

    @Transactional
    public RoleDto update(Integer tenantId, String id, UpdateRoleRequest req, PlatformAdmin actor) {
        Role r = roles.findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> ApiException.notFound("Role"));
        if (req.name() != null && !req.name().isBlank()) {
            r.name = req.name().trim();
        }
        if (req.description() != null) {
            r.description = req.description();
        }
        roles.save(r);
        log.info("Role updated — tenant={} id={} by={}", tenantId, id, actor.username);
        return toDto(r);
    }

    private static RoleDto toDto(Role r) {
        return new RoleDto(r.id, r.name, r.description);
    }
}
