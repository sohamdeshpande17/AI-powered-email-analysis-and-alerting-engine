package com.meridian.circular.service;

import com.meridian.circular.domain.AppUser;
import com.meridian.circular.dto.Dtos.CreateUserRequest;
import com.meridian.circular.dto.Dtos.DirectoryRecipient;
import com.meridian.circular.dto.Dtos.DirectoryUser;
import com.meridian.circular.dto.Dtos.UpdateUserRequest;
import com.meridian.circular.dto.Dtos.UserDto;
import com.meridian.circular.repo.AppUserRepository;
import com.meridian.circular.repo.RoleRepository;
import com.meridian.circular.security.TenantContext;
import com.meridian.circular.web.ApiException;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Provisioning of Compliance-team users (BRD FR-USER). */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final AppUserRepository users;
    private final RoleRepository roles;
    private final AuditService audit;
    private final GraphDirectoryService graphDirectory;

    /**
     * Sample directory used as a fallback when Microsoft Graph is disabled or
     * unreachable (e.g. the app registration lacks {@code User.Read.All}). The
     * primary path is {@link GraphDirectoryService}; see {@link #searchDirectory}.
     */
    private static final List<DirectoryUser> DIRECTORY = List.of(
            new DirectoryUser("oid-asha", "Asha Rao", "asha.rao@meridiancapital.com"),
            new DirectoryUser("oid-neha", "Neha Kapoor", "neha.kapoor@meridiancapital.com"),
            new DirectoryUser("oid-rohan", "Rohan Verma", "rohan.verma@meridiancapital.com"),
            new DirectoryUser("oid-vikram", "Vikram Shah", "vikram.shah@meridiancapital.com"),
            new DirectoryUser("oid-priya", "Priya Iyer", "priya.iyer@meridiancapital.com"),
            new DirectoryUser("oid-karan", "Karan Mehta", "karan.mehta@meridiancapital.com"),
            new DirectoryUser("oid-divya", "Divya Nair", "divya.nair@meridiancapital.com"));

    public UserService(AppUserRepository users, RoleRepository roles, AuditService audit,
                       GraphDirectoryService graphDirectory) {
        this.users = users;
        this.roles = roles;
        this.audit = audit;
        this.graphDirectory = graphDirectory;
    }

    public List<UserDto> list() {
        return users.findAllByTenantId(TenantContext.get()).stream()
                .sorted(Comparator.comparing(u -> u.displayName))
                .map(UserService::toDto)
                .toList();
    }

    public static UserDto toDto(AppUser u) {
        return new UserDto(u.userId, u.displayName, u.email, u.roleId,
                u.isActive, u.lastLoginAt);
    }

    /**
     * Microsoft Graph directory search — excludes already-provisioned users so
     * the picker only offers people who can still be added. Uses the real Graph
     * directory when configured; falls back to the sample {@link #DIRECTORY}
     * when Graph is disabled or the call fails (so dev provisioning still works).
     */
    public List<DirectoryUser> searchDirectory(String query) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            return List.of();
        }

        List<DirectoryUser> candidates;
        if (graphDirectory.isEnabled()) {
            try {
                candidates = graphDirectory.search(q);
            } catch (Exception e) {
                log.warn("Graph directory search failed — falling back to sample directory: {}",
                        e.getMessage());
                candidates = mockSearch(q);
            }
        } else {
            candidates = mockSearch(q);
        }

        return candidates.stream()
                .filter(d -> !users.existsByEmailIgnoreCase(d.email()))
                .toList();
    }

    /**
     * Directory search for forward-Cc candidates — people AND distribution
     * lists / groups (item: team default Cc). Unlike {@link #searchDirectory}
     * this does NOT exclude provisioned users, since a Cc recipient need not be
     * an app user. Falls back to the sample directory when Graph is off/unreachable.
     */
    public List<DirectoryRecipient> searchRecipients(String query) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            return List.of();
        }
        if (graphDirectory.isEnabled()) {
            try {
                return graphDirectory.searchRecipients(q);
            } catch (Exception e) {
                log.warn("Graph recipient search failed — falling back to sample directory: {}",
                        e.getMessage());
            }
        }
        return mockSearch(q).stream()
                .map(d -> new DirectoryRecipient(d.oid(), d.name(), d.email(), "USER"))
                .toList();
    }

    /** Substring match over the built-in sample directory (fallback path). */
    private List<DirectoryUser> mockSearch(String query) {
        String q = query.toLowerCase();
        return DIRECTORY.stream()
                .filter(d -> d.name().toLowerCase().contains(q)
                        || d.email().toLowerCase().contains(q))
                .toList();
    }

    @Transactional
    public UserDto create(CreateUserRequest req, AppUser actor) {
        if (req.email() == null || req.email().isBlank()
                || req.name() == null || req.name().isBlank()) {
            throw ApiException.badRequest("Name and email are required.");
        }
        String role = req.role() == null ? null : req.role().toLowerCase();
        Integer tenantId = TenantContext.get();
        if (role == null || tenantId == null || !roles.existsByTenantIdAndId(tenantId, role)) {
            throw ApiException.badRequest("Unknown role: " + req.role());
        }
        if (users.existsByEmailIgnoreCase(req.email())) {
            throw ApiException.badRequest("A user with this email already exists.");
        }
        AppUser u = new AppUser();
        u.azureOid = req.oid();
        u.displayName = req.name().trim();
        u.email = req.email().trim();
        u.roleId = role;
        users.save(u);
        audit.record(actor, "USER_CREATE", "user", u.userId.toString(),
                "Created user " + u.displayName + " (" + u.roleId + ")");
        return toDto(u);
    }

    @Transactional
    public UserDto update(UUID userId, UpdateUserRequest req, AppUser actor) {
        AppUser u = users.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User"));
        if (req.role() != null) {
            String role = req.role().toLowerCase();
            if (!roles.existsByTenantIdAndId(u.tenantId, role)) {
                throw ApiException.badRequest("Unknown role: " + req.role());
            }
            u.roleId = role;
        }
        if (req.isActive() != null) {
            u.isActive = req.isActive();
        }
        users.save(u);
        audit.record(actor, "USER_UPDATE", "user", userId.toString(),
                "Updated user " + u.displayName);
        return toDto(u);
    }
}
