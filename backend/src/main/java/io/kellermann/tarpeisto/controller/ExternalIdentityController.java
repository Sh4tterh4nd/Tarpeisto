package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import io.kellermann.tarpeisto.service.ExternalIdentityService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Owner-only external-identity administration (implementation plan section 3.2: "Owner-managed
 * external-identity linking"): list a user's linked OIDC identities, approve/create a link, and
 * unlink one. As with {@link UserController}, every endpoint here is reachable only by an
 * authenticated user; the "must be Owner" decision, the same-organization check, and the
 * last-usable-Owner-authentication-method protection are all enforced in {@link
 * ExternalIdentityService}, not here.
 */
@RestController
@RequestMapping("/api/v1/users/{userId}/external-identities")
public class ExternalIdentityController {

    private final ExternalIdentityService externalIdentityService;

    public ExternalIdentityController(ExternalIdentityService externalIdentityService) {
        this.externalIdentityService = externalIdentityService;
    }

    @GetMapping
    public List<ExternalIdentityResponse> list(
            @AuthenticationPrincipal TarpeistoPrincipal principal, @PathVariable UUID userId) {
        return externalIdentityService.listIdentities(principal, userId).stream()
                .map(ExternalIdentityResponse::from)
                .toList();
    }

    @PostMapping
    public ResponseEntity<ExternalIdentityResponse> create(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID userId,
            @Valid @RequestBody CreateExternalIdentityLinkRequest request) {
        var created = externalIdentityService.createLink(principal, userId, request.issuer(), request.subject());
        return ResponseEntity.status(HttpStatus.CREATED).body(ExternalIdentityResponse.from(created));
    }

    @DeleteMapping("/{externalIdentityId}")
    public ResponseEntity<Void> unlink(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID userId,
            @PathVariable UUID externalIdentityId) {
        externalIdentityService.unlink(principal, userId, externalIdentityId);
        return ResponseEntity.noContent().build();
    }
}
