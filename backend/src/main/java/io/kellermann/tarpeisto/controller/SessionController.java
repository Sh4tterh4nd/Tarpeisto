package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.security.SessionAuthenticationService;
import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The session resource (ADR-0001/ADR-0003): a JSON API, not a server-rendered form login. {@code
 * POST}/{@code DELETE} are explicitly allowlisted (unauthenticated-reachable) in {@code
 * SecurityConfiguration}; {@code GET} falls under the default {@code anyRequest().authenticated()}
 * rule.
 *
 * <p>Every field of {@link SessionResponse} - including {@code organizationId} - comes from the
 * server-side {@link TarpeistoPrincipal} resolved at authentication time. There is no request
 * parameter or body field on any endpoint in this API that supplies an organization id; a client
 * cannot influence it.
 */
@RestController
@RequestMapping("/api/v1/session")
public class SessionController {

    private final SessionAuthenticationService sessionAuthenticationService;

    public SessionController(SessionAuthenticationService sessionAuthenticationService) {
        this.sessionAuthenticationService = sessionAuthenticationService;
    }

    @PostMapping
    public SessionResponse login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        TarpeistoPrincipal principal =
                sessionAuthenticationService.login(request.username(), request.password(), httpRequest, httpResponse);
        return SessionResponse.from(principal);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        sessionAuthenticationService.logout(httpRequest, httpResponse);
    }

    @GetMapping
    public ResponseEntity<SessionResponse> current(@AuthenticationPrincipal TarpeistoPrincipal principal) {
        return ResponseEntity.ok(SessionResponse.from(principal));
    }
}
