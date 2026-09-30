package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.model.ArchiveKind;
import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import io.kellermann.tarpeisto.service.ArchiveService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ArchiveController {
    private final ArchiveService archiveService;

    public ArchiveController(ArchiveService archiveService) {
        this.archiveService = archiveService;
    }

    @PutMapping("/consumable-stock/{id}/archive")
    public ArchiveResponse changeStock(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody ChangeArchiveRequest request) {
        return change(principal, ArchiveKind.STOCK, id, request);
    }

    @PutMapping("/users/{id}/archive")
    public ArchiveResponse changeUser(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody ChangeArchiveRequest request) {
        return change(principal, ArchiveKind.USER, id, request);
    }

    @PutMapping("/bookings/{id}/archive")
    public ArchiveResponse changeBooking(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody ChangeArchiveRequest request) {
        return change(principal, ArchiveKind.BOOKING, id, request);
    }

    @PutMapping("/audits/{id}/archive")
    public ArchiveResponse changeAudit(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody ChangeArchiveRequest request) {
        return change(principal, ArchiveKind.AUDIT, id, request);
    }

    private ArchiveResponse change(
            TarpeistoPrincipal principal, ArchiveKind kind, UUID id, ChangeArchiveRequest request) {
        return ArchiveResponse.from(
                archiveService.change(principal, kind, id, request.archived(), request.expectedVersion()));
    }
}
