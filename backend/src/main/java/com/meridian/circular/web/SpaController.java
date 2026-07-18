package com.meridian.circular.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the React single-page app for client-side routes. A hard browser
 * load of any in-app route (e.g. {@code /inbox}, {@code /circulars/{id}}) is
 * forwarded to {@code /index.html} so React Router can take over. API routes
 * ({@code /api/**}) and static assets are handled before this controller.
 */
@Controller
public class SpaController {

    @GetMapping({
            "/",
            "/login",
            "/dashboard",
            "/inbox",
            "/compliance",
            "/audit",
            "/admin/{section}",
            "/circulars/{id}"
    })
    public String forwardToSpa() {
        return "forward:/index.html";
    }
}
