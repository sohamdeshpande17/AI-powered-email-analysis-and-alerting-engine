package com.meridian.circular.service;

import com.meridian.circular.domain.PlatformAdmin;
import com.meridian.circular.dto.Dtos.AdminAuthResponse;
import com.meridian.circular.dto.Dtos.AdminLoginRequest;
import com.meridian.circular.dto.Dtos.ChangePasswordRequest;
import com.meridian.circular.dto.Dtos.CreateAdminRequest;
import com.meridian.circular.dto.Dtos.PlatformAdminDto;
import com.meridian.circular.dto.Dtos.UpdateAdminRequest;
import com.meridian.circular.repo.PlatformAdminRepository;
import com.meridian.circular.web.ApiException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Super-admin ({@code platform_admin}) authentication and self-management.
 *
 * <p>Platform-administration actions are not written to the per-department
 * {@code audit_event} table (which lives only inside tenant schemas); they are
 * logged here instead. God-mode workflow actions audit naturally into the acting
 * department.
 */
@Service
public class PlatformAdminService {

    private static final Logger log = LoggerFactory.getLogger(PlatformAdminService.class);
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final PlatformAdminRepository admins;
    private final PasswordEncoder passwordEncoder;
    private final AuthTokenService tokens;

    public PlatformAdminService(PlatformAdminRepository admins, PasswordEncoder passwordEncoder,
                                AuthTokenService tokens) {
        this.admins = admins;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
    }

    // ---- authentication ----------------------------------------------------

    @Transactional
    public AdminAuthResponse login(AdminLoginRequest req) {
        if (req == null || req.username() == null || req.password() == null) {
            throw invalidCredentials();
        }
        PlatformAdmin admin = admins.findByUsernameIgnoreCase(req.username().trim())
                .filter(a -> a.isActive)
                .filter(a -> passwordEncoder.matches(req.password(), a.passwordHash))
                .orElseThrow(PlatformAdminService::invalidCredentials);
        admin.lastLoginAt = Instant.now();
        admins.save(admin);
        log.info("Super-admin login — username={} id={}", admin.username, admin.adminId);
        return new AdminAuthResponse(tokens.issuePlatform(admin), toDto(admin),
                admin.mustChangePassword);
    }

    @Transactional
    public void changePassword(UUID adminId, ChangePasswordRequest req) {
        PlatformAdmin admin = admins.findById(adminId)
                .orElseThrow(() -> ApiException.notFound("Super admin"));
        if (req == null || req.currentPassword() == null || req.newPassword() == null
                || !passwordEncoder.matches(req.currentPassword(), admin.passwordHash)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Current password is incorrect.");
        }
        validatePassword(req.newPassword());
        admin.passwordHash = passwordEncoder.encode(req.newPassword());
        admin.mustChangePassword = false;
        admins.save(admin);
        log.info("Super-admin password changed — id={}", adminId);
    }

    // ---- self-management ---------------------------------------------------

    public List<PlatformAdminDto> list() {
        return admins.findAll().stream()
                .sorted(Comparator.comparing(a -> a.username))
                .map(PlatformAdminService::toDto)
                .toList();
    }

    @Transactional
    public PlatformAdminDto create(CreateAdminRequest req, PlatformAdmin actor) {
        if (req.username() == null || req.username().isBlank()
                || req.displayName() == null || req.displayName().isBlank()) {
            throw ApiException.badRequest("Username and display name are required.");
        }
        String username = req.username().trim();
        if (admins.existsByUsernameIgnoreCase(username)) {
            throw ApiException.badRequest("A super admin with this username already exists.");
        }
        validatePassword(req.password());
        PlatformAdmin a = new PlatformAdmin();
        a.username = username;
        a.displayName = req.displayName().trim();
        a.passwordHash = passwordEncoder.encode(req.password());
        a.mustChangePassword = true;
        a.createdBy = actor.adminId;
        admins.save(a);
        log.info("Super-admin created — username={} by={}", a.username, actor.username);
        return toDto(a);
    }

    @Transactional
    public PlatformAdminDto update(UUID adminId, UpdateAdminRequest req, PlatformAdmin actor) {
        PlatformAdmin a = admins.findById(adminId)
                .orElseThrow(() -> ApiException.notFound("Super admin"));
        if (req.displayName() != null && !req.displayName().isBlank()) {
            a.displayName = req.displayName().trim();
        }
        if (req.isActive() != null && !req.isActive() && a.isActive) {
            // Never disable the last remaining active super admin (lockout guard).
            if (admins.countByIsActiveTrue() <= 1) {
                throw ApiException.badRequest("Cannot disable the last active super admin.");
            }
            a.isActive = false;
        } else if (req.isActive() != null && req.isActive()) {
            a.isActive = true;
        }
        if (req.resetPassword() != null) {
            validatePassword(req.resetPassword());
            a.passwordHash = passwordEncoder.encode(req.resetPassword());
            a.mustChangePassword = true;
        }
        a.updatedBy = actor.adminId;
        admins.save(a);
        log.info("Super-admin updated — id={} by={}", adminId, actor.username);
        return toDto(a);
    }

    // ---- helpers -----------------------------------------------------------

    private void validatePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw ApiException.badRequest(
                    "Password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
        }
    }

    public static PlatformAdminDto toDto(PlatformAdmin a) {
        return new PlatformAdminDto(a.adminId, a.username, a.displayName, a.isActive,
                a.mustChangePassword, a.lastLoginAt);
    }

    private static ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "Invalid username or password.");
    }
}
