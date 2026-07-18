package com.meridian.circular.web;

import com.meridian.circular.domain.Category;
import com.meridian.circular.domain.Role;
import com.meridian.circular.repo.CategoryRepository;
import com.meridian.circular.repo.RoleRepository;
import com.meridian.circular.security.TenantContext;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Reference-data lookups — roles and categories. */
@RestController
@RequestMapping("/api")
public class MetaController {

    private final RoleRepository roles;
    private final CategoryRepository categories;

    public MetaController(RoleRepository roles, CategoryRepository categories) {
        this.roles = roles;
        this.categories = categories;
    }

    /** The role catalog for the caller's department (resolved from the token). */
    @GetMapping("/roles")
    public List<Role> roles() {
        Integer tenantId = TenantContext.get();
        return tenantId == null ? List.of() : roles.findByTenantId(tenantId);
    }

    /** The global circular-category taxonomy. */
    @GetMapping("/categories")
    public List<Category> categories() {
        return categories.findAll();
    }
}
