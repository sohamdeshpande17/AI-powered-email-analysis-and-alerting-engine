package com.meridian.circular.web;

import com.meridian.circular.domain.AppUser;
import com.meridian.circular.dto.Dtos.CreateUserRequest;
import com.meridian.circular.dto.Dtos.DirectoryRecipient;
import com.meridian.circular.dto.Dtos.DirectoryUser;
import com.meridian.circular.dto.Dtos.UpdateUserRequest;
import com.meridian.circular.dto.Dtos.UserDto;
import com.meridian.circular.security.Actor;
import com.meridian.circular.service.UserService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** User Management and the Graph-API directory search (BRD FR-USER). */
@RestController
@RequestMapping("/api")
public class UserController {

    private final UserService users;

    public UserController(UserService users) {
        this.users = users;
    }

    /** List provisioned users in the acting workspace. */
    @GetMapping("/users")
    public List<UserDto> list() {
        return users.list();
    }

    /** Provision a new user (admin / compliance-head). */
    @PostMapping("/users")
    public UserDto create(@RequestBody CreateUserRequest req, @Actor AppUser actor) {
        return users.create(req, actor);
    }

    /** Update a user's role and/or active flag. */
    @PatchMapping("/users/{id}")
    public UserDto update(@PathVariable UUID id,
                          @RequestBody UpdateUserRequest req,
                          @Actor AppUser actor) {
        return users.update(id, req, actor);
    }

    /** Mock Microsoft Graph directory search (BRD FR-USER-03). */
    @GetMapping("/directory/search")
    public List<DirectoryUser> searchDirectory(@RequestParam(required = false) String q) {
        return users.searchDirectory(q);
    }

    /**
     * Directory search for forward-Cc candidates — returns both people and
     * distribution lists / groups (team default Cc, 'CC users of DL').
     */
    @GetMapping("/directory/search-recipients")
    public List<DirectoryRecipient> searchRecipients(@RequestParam(required = false) String q) {
        return users.searchRecipients(q);
    }
}
