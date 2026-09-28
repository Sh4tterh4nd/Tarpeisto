package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import io.kellermann.tarpeisto.service.AuditService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audits")
public class AuditController {
    private final AuditService audits;

    public AuditController(AuditService audits) {
        this.audits = audits;
    }

    @GetMapping("/tasks/{taskId}")
    public ContainerAuditResponse get(
            @AuthenticationPrincipal TarpeistoPrincipal principal, @PathVariable UUID taskId) {
        return ContainerAuditResponse.from(audits.get(principal, taskId));
    }

    @PostMapping("/tasks/{taskId}/start")
    public ContainerAuditResponse start(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID taskId,
            @Valid @RequestBody StartAuditRequest request) {
        return ContainerAuditResponse.from(audits.start(principal, taskId, request.containerCode()));
    }

    @PostMapping("/containers/{containerId}/launch")
    public ContainerAuditResponse launch(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID containerId,
            @Valid @RequestBody LaunchContainerAuditRequest request) {
        return ContainerAuditResponse.from(
                audits.launchContainerAudit(principal, containerId, request.containerCode(), request.operationId()));
    }

    @PostMapping("/{auditId}/scans")
    public ContainerAuditResponse scan(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID auditId,
            @Valid @RequestBody ScanAuditRequest request) {
        return ContainerAuditResponse.from(audits.scan(principal, auditId, request.operationId(), request.code()));
    }

    @PostMapping("/{auditId}/scans/{scanId}/undo")
    public ContainerAuditResponse undo(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID auditId,
            @PathVariable UUID scanId,
            @Valid @RequestBody UndoAuditScanRequest request) {
        return ContainerAuditResponse.from(audits.undo(principal, auditId, scanId, request.operationId()));
    }

    @PostMapping("/{auditId}/move-scan-here")
    public ContainerAuditResponse move(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID auditId,
            @Valid @RequestBody MoveAuditScanRequest request) {
        return ContainerAuditResponse.from(
                audits.moveScanHere(principal, auditId, request.sourceScanId(), request.operationId()));
    }

    @PostMapping("/{auditId}/move-code-here")
    public ContainerAuditResponse moveCode(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID auditId,
            @Valid @RequestBody ScanAuditRequest request) {
        return ContainerAuditResponse.from(
                audits.moveCodeHere(principal, auditId, request.operationId(), request.code()));
    }

    @PostMapping("/{auditId}/findings")
    public ContainerAuditResponse finding(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID auditId,
            @Valid @RequestBody RecordAuditFindingRequest request) {
        return ContainerAuditResponse.from(audits.recordFinding(
                principal, auditId, request.operationId(), request.type(), request.assetId(), request.note()));
    }

    @PostMapping("/{auditId}/consumables/{expectedId}")
    public ContainerAuditResponse consumable(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID auditId,
            @PathVariable UUID expectedId,
            @Valid @RequestBody ObserveAuditConsumableRequest request) {
        return ContainerAuditResponse.from(audits.observeConsumable(
                principal,
                auditId,
                expectedId,
                request.operationId(),
                request.status(),
                request.observedQuantity(),
                request.reason()));
    }

    @PostMapping("/{auditId}/complete")
    public ContainerAuditResponse complete(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID auditId,
            @Valid @RequestBody CompleteAuditRequest request) {
        return ContainerAuditResponse.from(audits.complete(
                principal,
                auditId,
                request.operationId(),
                request.containerCode(),
                request.confirmMissing(),
                request.sealConfirmed()));
    }
}
