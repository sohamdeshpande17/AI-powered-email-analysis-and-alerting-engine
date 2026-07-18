package com.meridian.circular.web;

import com.meridian.circular.domain.AppUser;
import com.meridian.circular.domain.PlatformAdmin;
import com.meridian.circular.domain.Tenant;
import com.meridian.circular.dto.Dtos.CreateAdminRequest;
import com.meridian.circular.dto.Dtos.CreateDepartmentRequest;
import com.meridian.circular.dto.Dtos.CreateRoleRequest;
import com.meridian.circular.dto.Dtos.CreateUserRequest;
import com.meridian.circular.dto.Dtos.DepartmentDto;
import com.meridian.circular.dto.Dtos.PlatformAdminDto;
import com.meridian.circular.dto.Dtos.RoleDto;
import com.meridian.circular.dto.Dtos.UpdateAdminRequest;
import com.meridian.circular.dto.Dtos.UpdateDepartmentRequest;
import com.meridian.circular.dto.Dtos.UpdateRoleRequest;
import com.meridian.circular.dto.Dtos.UpdateUserRequest;
import com.meridian.circular.dto.Dtos.UserDto;
import com.meridian.circular.repo.TenantRepository;
import com.meridian.circular.security.PlatformActor;
import com.meridian.circular.security.SuperAdminActor;
import com.meridian.circular.security.TenantContext;
import com.meridian.circular.service.DepartmentService;
import com.meridian.circular.service.PlatformAdminService;
import com.meridian.circular.service.RoleService;
import com.meridian.circular.service.UserService;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Super-admin platform administration (guarded by the platform interceptor):
 * departments (tenants), the global role catalog, users in any department, and
 * other super admins. God-mode workflow acting is not here — the super admin
 * uses the normal {@code /api/...} endpoints with an acting-department header.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final DepartmentService departments;
    private final RoleService roles;
    private final PlatformAdminService admins;
    private final UserService users;
    private final TenantRepository tenants;

    public AdminController(DepartmentService departments, RoleService roles,
                           PlatformAdminService admins, UserService users,
                           TenantRepository tenants) {
        this.departments = departments;
        this.roles = roles;
        this.admins = admins;
        this.users = users;
        this.tenants = tenants;
    }

    // ---- departments -------------------------------------------------------

    @GetMapping("/departments")
    public List<DepartmentDto> listDepartments() {
        return departments.list();
    }

    @PostMapping("/departments")
    public DepartmentDto createDepartment(@RequestBody CreateDepartmentRequest req,
                                          @PlatformActor PlatformAdmin actor) {
        return departments.create(req, actor);
    }

    @PatchMapping("/departments/{id}")
    public DepartmentDto updateDepartment(@PathVariable Integer id,
                                          @RequestBody UpdateDepartmentRequest req,
                                          @PlatformActor PlatformAdmin actor) {
        return departments.update(id, req, actor);
    }

    // ---- roles (per department) -------------------------------------------

    @GetMapping("/departments/{tenantId}/roles")
    public List<RoleDto> listRoles(@PathVariable Integer tenantId,
                                   @PlatformActor PlatformAdmin actor) {
        requireDepartment(tenantId);
        return roles.list(tenantId);
    }

    @PostMapping("/departments/{tenantId}/roles")
    public RoleDto createRole(@PathVariable Integer tenantId, @RequestBody CreateRoleRequest req,
                              @PlatformActor PlatformAdmin actor) {
        requireDepartment(tenantId);
        return roles.create(tenantId, req, actor);
    }

    @PatchMapping("/departments/{tenantId}/roles/{id}")
    public RoleDto updateRole(@PathVariable Integer tenantId, @PathVariable String id,
                              @RequestBody UpdateRoleRequest req,
                              @PlatformActor PlatformAdmin actor) {
        requireDepartment(tenantId);
        return roles.update(tenantId, id, req, actor);
    }

    // ---- users in a department --------------------------------------------

    @GetMapping("/departments/{tenantId}/users")
    public List<UserDto> listDepartmentUsers(@PathVariable Integer tenantId,
                                             @PlatformActor PlatformAdmin actor) {
        return inDepartment(tenantId, syntheticActor -> users.list());
    }

    @PostMapping("/departments/{tenantId}/users")
    public UserDto createDepartmentUser(@PathVariable Integer tenantId,
                                        @RequestBody CreateUserRequest req,
                                        @PlatformActor PlatformAdmin actor) {
        return inDepartment(tenantId, syntheticActor -> users.create(req, syntheticActor));
    }

    @PatchMapping("/departments/{tenantId}/users/{userId}")
    public UserDto updateDepartmentUser(@PathVariable Integer tenantId,
                                        @PathVariable UUID userId,
                                        @RequestBody UpdateUserRequest req,
                                        @PlatformActor PlatformAdmin actor) {
        return inDepartment(tenantId, syntheticActor -> users.update(userId, req, syntheticActor));
    }

    // ---- super admins ------------------------------------------------------

    @GetMapping("/admins")
    public List<PlatformAdminDto> listAdmins() {
        return admins.list();
    }

    @PostMapping("/admins")
    public PlatformAdminDto createAdmin(@RequestBody CreateAdminRequest req,
                                        @PlatformActor PlatformAdmin actor) {
        return admins.create(req, actor);
    }

    @PatchMapping("/admins/{id}")
    public PlatformAdminDto updateAdmin(@PathVariable UUID id,
                                        @RequestBody UpdateAdminRequest req,
                                        @PlatformActor PlatformAdmin actor) {
        return admins.update(id, req, actor);
    }

    // ---- helpers -----------------------------------------------------------

    /** Validate the target department exists (404 otherwise). */
    private void requireDepartment(Integer tenantId) {
        if (tenants.findById(tenantId).isEmpty()) {
            throw ApiException.notFound("Department");
        }
    }

    /**
     * Run a tenant-scoped operation against a target department, as the
     * super-admin sentinel actor: sets {@link TenantContext} (id + schema) so the
     * reused tenant services route correctly, then clears it. Platform requests
     * do not pass through the bearer interceptor, so we own the cleanup.
     */
    private <T> T inDepartment(Integer tenantId, Function<AppUser, T> work) {
        Tenant t = tenants.findById(tenantId)
                .orElseThrow(() -> ApiException.notFound("Department"));
        String schema = t.code == null ? null : t.code.toLowerCase();
        try {
            TenantContext.set(tenantId, schema);
            return work.apply(SuperAdminActor.synthetic(tenantId));
        } finally {
            TenantContext.clear();
        }
    }
}
