package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import io.kellermann.tarpeisto.service.UserService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Owner-only permanent-account administration (implementation plan section 3.2): list, create,
 * change role, enable/disable. Every endpoint here is reachable only by an authenticated user (the
 * default {@code anyRequest().authenticated()} rule in {@code SecurityConfiguration}); the actual
 * "must be Owner" decision is enforced in {@link UserService}, not here - this controller carries
 * no authorization shortcut, per docs/DEVELOPMENT_POLICIES.md section 5.1.
 *
 * <p>There is no {@code organizationId} request parameter, path variable, or body field anywhere
 * below: every operation is scoped to {@code principal.organizationId()}, the server-side
 * active-organization context, which a client cannot influence.
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public List<UserResponse> list(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") boolean includeArchived) {
        return userService.listUsers(principal, includeArchived).stream()
                .map(UserResponse::from)
                .toList();
    }

    @PostMapping
    public ResponseEntity<UserResponse> create(
            @AuthenticationPrincipal TarpeistoPrincipal principal, @Valid @RequestBody CreateUserRequest request) {
        var created = userService.createUser(
                principal,
                request.username(),
                request.password(),
                request.displayName(),
                request.email(),
                request.role());
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(created));
    }

    @PutMapping("/{userId}/role")
    public ResponseEntity<Void> changeRole(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID userId,
            @Valid @RequestBody ChangeRoleRequest request) {
        userService.changeRole(principal, userId, request.role());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{userId}/enabled")
    public ResponseEntity<Void> setEnabled(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID userId,
            @Valid @RequestBody SetEnabledRequest request) {
        userService.setEnabled(principal, userId, request.enabled());
        return ResponseEntity.noContent().build();
    }
}
