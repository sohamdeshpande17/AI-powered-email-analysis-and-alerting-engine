package com.meridian.circular.web;

import com.meridian.circular.domain.PlatformAdmin;
import com.meridian.circular.dto.Dtos.AdminAuthResponse;
import com.meridian.circular.dto.Dtos.AdminLoginRequest;
import com.meridian.circular.dto.Dtos.ChangePasswordRequest;
import com.meridian.circular.security.PlatformActor;
import com.meridian.circular.service.PlatformAdminService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Super-admin identity endpoints. {@code POST /login} is public (username +
 * password → platform bearer token); {@code POST /change-password} is guarded by
 * the {@link com.meridian.circular.security.PlatformAdminInterceptor}.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminAuthController {

    private final PlatformAdminService service;

    public AdminAuthController(PlatformAdminService service) {
        this.service = service;
    }

    /** Username/password sign-in — returns a platform token + the admin. */
    @PostMapping("/login")
    public AdminAuthResponse login(@RequestBody AdminLoginRequest req) {
        return service.login(req);
    }

    /** Change the signed-in super admin's password (clears the must-change flag). */
    @PostMapping("/change-password")
    public void changePassword(@RequestBody ChangePasswordRequest req,
                               @PlatformActor PlatformAdmin actor) {
        service.changePassword(actor.adminId, req);
    }
}
