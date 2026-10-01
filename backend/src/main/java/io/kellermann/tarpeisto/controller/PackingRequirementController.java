package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import io.kellermann.tarpeisto.service.PackingContentsService;
import io.kellermann.tarpeisto.service.PackingRequirementService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class PackingRequirementController {
    private final PackingRequirementService service;
    private final PackingContentsService contents;

    public PackingRequirementController(PackingRequirementService service, PackingContentsService contents) {
        this.service = service;
        this.contents = contents;
    }

    @GetMapping("/assets/{assetId}/packing-contents")
    @io.swagger.v3.oas.annotations.Operation(operationId = "packingContents")
    public PackingContentsResponse contents(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID assetId,
            @RequestParam(required = false) String cursor) {
        return PackingContentsResponse.from(contents.get(principal, assetId, cursor));
    }

    @GetMapping("/assets/{containerAssetId}/packing-requirements")
    public List<PackingRequirementResponse> list(
            @AuthenticationPrincipal TarpeistoPrincipal p, @PathVariable UUID containerAssetId) {
        return service.list(p, containerAssetId).stream()
                .map(PackingRequirementResponse::from)
                .toList();
    }

    @PostMapping("/assets/{containerAssetId}/packing-preview")
    public PackingPreviewResponse preview(
            @AuthenticationPrincipal TarpeistoPrincipal p,
            @PathVariable UUID containerAssetId,
            @RequestBody(required = false) PackingPreviewRequest request) {
        return PackingPreviewResponse.from(service.preview(
                p, containerAssetId, request == null ? java.util.Map.of() : request.observedConsumableQuantities()));
    }

    @PostMapping("/assets/{containerAssetId}/packing-requirements")
    public ResponseEntity<PackingRequirementResponse> add(
            @AuthenticationPrincipal TarpeistoPrincipal p,
            @PathVariable UUID containerAssetId,
            @RequestBody PackingRequirementRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(PackingRequirementResponse.from(service.add(
                        p,
                        containerAssetId,
                        r.type(),
                        r.assetModelId(),
                        r.specificAssetId(),
                        r.specificAssetReference(),
                        r.requiredQuantity(),
                        Boolean.TRUE.equals(r.assignToContainer()),
                        r.expectedAssetVersion())));
    }

    @GetMapping("/packing-requirements/{id}/reservation-impact")
    public List<UUID> reservationImpact(@AuthenticationPrincipal TarpeistoPrincipal p, @PathVariable UUID id) {
        return service.reservationImpact(p, id);
    }

    @PostMapping("/packing-requirements/{id}/archive")
    public ResponseEntity<Void> archive(
            @AuthenticationPrincipal TarpeistoPrincipal p,
            @PathVariable UUID id,
            @Valid @RequestBody ArchivePackingRequirementRequest r) {
        service.archive(p, id, r.expectedVersion(), Boolean.TRUE.equals(r.confirmAffectedBookings()));
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/packing-requirements/{id}")
    public PackingRequirementResponse update(
            @AuthenticationPrincipal TarpeistoPrincipal p,
            @PathVariable UUID id,
            @Valid @RequestBody PackingRequirementMutationRequest request) {
        PackingRequirementRequest r = request.requirement();
        return PackingRequirementResponse.from(service.update(
                p,
                id,
                request.expectedVersion(),
                r.type(),
                r.assetModelId(),
                r.referenceWithoutAssignment(),
                r.requiredQuantity(),
                Boolean.TRUE.equals(request.confirmAffectedBookings())));
    }

    @PostMapping("/packing-requirements/{id}/restore")
    public PackingRequirementResponse restore(
            @AuthenticationPrincipal TarpeistoPrincipal p,
            @PathVariable UUID id,
            @RequestBody LocationVersionRequest r) {
        return PackingRequirementResponse.from(service.restore(p, id, r.expectedVersion()));
    }

    @GetMapping("/packing-templates")
    public List<PackingTemplateResponse> templates(
            @AuthenticationPrincipal TarpeistoPrincipal p,
            @RequestParam(defaultValue = "false") boolean includeArchived) {
        return service.listTemplates(p, includeArchived).stream()
                .map(PackingTemplateResponse::from)
                .toList();
    }

    @PostMapping("/packing-templates")
    public ResponseEntity<PackingTemplateResponse> createTemplate(
            @AuthenticationPrincipal TarpeistoPrincipal p, @Valid @RequestBody PackingTemplateRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(PackingTemplateResponse.from(service.createTemplate(p, r.name(), r.description())));
    }

    @PutMapping("/packing-templates/{templateId}")
    public PackingTemplateResponse updateTemplate(
            @AuthenticationPrincipal TarpeistoPrincipal p,
            @PathVariable UUID templateId,
            @Valid @RequestBody PackingTemplateMutationRequest request) {
        return PackingTemplateResponse.from(service.updateTemplate(
                p, templateId, request.expectedVersion(), request.name(), request.description()));
    }

    @PostMapping("/packing-templates/{templateId}/{action:archive|restore}")
    public PackingTemplateResponse setTemplateArchived(
            @AuthenticationPrincipal TarpeistoPrincipal p,
            @PathVariable UUID templateId,
            @PathVariable String action,
            @RequestBody LocationVersionRequest request) {
        return PackingTemplateResponse.from(
                service.setTemplateArchived(p, templateId, request.expectedVersion(), action.equals("archive")));
    }

    @PostMapping("/packing-templates/{templateId}/requirements")
    public PackingTemplateResponse addTemplateRequirement(
            @AuthenticationPrincipal TarpeistoPrincipal p,
            @PathVariable UUID templateId,
            @RequestBody PackingRequirementRequest request) {
        return PackingTemplateResponse.from(service.addTemplateRequirement(
                p,
                templateId,
                request.type(),
                request.assetModelId(),
                request.referenceWithoutAssignment(),
                request.requiredQuantity()));
    }

    @PutMapping("/packing-template-requirements/{id}")
    public PackingTemplateResponse updateTemplateRequirement(
            @AuthenticationPrincipal TarpeistoPrincipal p,
            @PathVariable UUID id,
            @Valid @RequestBody PackingRequirementMutationRequest request) {
        PackingRequirementRequest r = request.requirement();
        return PackingTemplateResponse.from(service.updateTemplateRequirement(
                p,
                id,
                request.expectedVersion(),
                r.type(),
                r.assetModelId(),
                r.referenceWithoutAssignment(),
                r.requiredQuantity()));
    }

    @PostMapping("/packing-template-requirements/{id}/{action:archive|restore}")
    public PackingTemplateResponse setTemplateRequirementArchived(
            @AuthenticationPrincipal TarpeistoPrincipal p,
            @PathVariable UUID id,
            @PathVariable String action,
            @RequestBody LocationVersionRequest request) {
        return PackingTemplateResponse.from(
                service.setTemplateRequirementArchived(p, id, request.expectedVersion(), action.equals("archive")));
    }

    @PostMapping("/assets/{containerAssetId}/packing-templates/{templateId}/apply")
    public List<PackingRequirementResponse> apply(
            @AuthenticationPrincipal TarpeistoPrincipal p,
            @PathVariable UUID containerAssetId,
            @PathVariable UUID templateId) {
        return service.applyTemplate(p, containerAssetId, templateId).stream()
                .map(PackingRequirementResponse::from)
                .toList();
    }
}
