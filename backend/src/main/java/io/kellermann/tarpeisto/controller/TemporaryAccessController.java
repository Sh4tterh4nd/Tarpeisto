package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.model.TemporaryAccessInvitation;
import io.kellermann.tarpeisto.security.SessionAuthenticationService;
import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import io.kellermann.tarpeisto.security.TemporaryAccessRateLimiter;
import io.kellermann.tarpeisto.service.AssignedAuditTaskView;
import io.kellermann.tarpeisto.service.AuditService;
import io.kellermann.tarpeisto.service.TemporaryAccessService;
import io.kellermann.tarpeisto.service.TemporaryInvitationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/temporary-access")
public class TemporaryAccessController {
    private final TemporaryAccessService access;
    private final SessionAuthenticationService sessions;
    private final TemporaryAccessRateLimiter limiter;
    private final AuditService audits;
    private final TemporaryInvitationService invitations;

    public TemporaryAccessController(
            TemporaryAccessService access,
            SessionAuthenticationService sessions,
            TemporaryAccessRateLimiter limiter,
            AuditService audits,
            TemporaryInvitationService invitations) {
        this.access = access;
        this.sessions = sessions;
        this.limiter = limiter;
        this.audits = audits;
        this.invitations = invitations;
    }

    @PostMapping("/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public TemporaryInvitationResponse create(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @Valid @RequestBody CreateTemporaryInvitationRequest request) {
        var issued = invitations.create(principal, request.bookingId(), request.auditBatchId(), request.joinUrl());
        return new TemporaryInvitationResponse(
                issued.invitation(), issued.token(), issued.joinUrl(), issued.qrCodeDataUrl());
    }

    @GetMapping("/invitations")
    public List<TemporaryAccessInvitation> list(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @RequestParam(required = false) UUID bookingId,
            @RequestParam(required = false) UUID auditBatchId) {
        return access.list(principal, bookingId, auditBatchId);
    }

    @PostMapping("/invitations/{invitationId}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal TarpeistoPrincipal principal, @PathVariable UUID invitationId) {
        access.revoke(principal, invitationId);
    }

    @PostMapping("/redemptions")
    public SessionResponse redeem(
            @RequestBody RedeemTemporaryInvitationRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        limiter.attempt(request.getRemoteAddr(), body.token());
        var principal = access.redeem(body.token(), body.displayName(), body.operationId());
        sessions.establish(principal, request, response);
        return SessionResponse.from(principal);
    }

    @GetMapping("/tasks")
    public List<AssignedAuditTaskView> tasks(@AuthenticationPrincipal TarpeistoPrincipal principal) {
        return audits.assignedTasks(principal);
    }
}
