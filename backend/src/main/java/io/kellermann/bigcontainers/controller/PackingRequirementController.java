package io.kellermann.bigcontainers.controller;

import io.kellermann.bigcontainers.security.BigContainersPrincipal;
import io.kellermann.bigcontainers.service.PackingRequirementService;
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

@RestController
@RequestMapping("/api/v1")
public class PackingRequirementController {
    private final PackingRequirementService service;

    public PackingRequirementController(PackingRequirementService service) {
        this.service = service;
    }

    @GetMapping("/assets/{containerAssetId}/packing-requirements")
    public List<PackingRequirementResponse> list(
            @AuthenticationPrincipal BigContainersPrincipal p, @PathVariable UUID containerAssetId) {
        return service.list(p, containerAssetId).stream()
                .map(PackingRequirementResponse::from)
                .toList();
    }

    @PostMapping("/assets/{containerAssetId}/packing-preview")
    public PackingPreviewResponse preview(
            @AuthenticationPrincipal BigContainersPrincipal p,
            @PathVariable UUID containerAssetId,
            @RequestBody(required = false) PackingPreviewRequest request) {
        return PackingPreviewResponse.from(service.preview(
                p, containerAssetId, request == null ? java.util.Map.of() : request.observedConsumableQuantities()));
    }

    @PostMapping("/assets/{containerAssetId}/packing-requirements")
    public ResponseEntity<PackingRequirementResponse> add(
            @AuthenticationPrincipal BigContainersPrincipal p,
            @PathVariable UUID containerAssetId,
            @RequestBody PackingRequirementRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(PackingRequirementResponse.from(service.add(
                        p,
                        containerAssetId,
                        r.type(),
                        r.assetModelId(),
                        r.specificAssetReference(),
                        r.requiredQuantity())));
    }

    @PostMapping("/packing-requirements/{id}/archive")
    public ResponseEntity<Void> archive(
            @AuthenticationPrincipal BigContainersPrincipal p,
            @PathVariable UUID id,
            @RequestBody LocationVersionRequest r) {
        service.archive(p, id, r.expectedVersion());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/packing-requirements/{id}")
    public PackingRequirementResponse update(
            @AuthenticationPrincipal BigContainersPrincipal p,
            @PathVariable UUID id,
            @Valid @RequestBody PackingRequirementMutationRequest request) {
        PackingRequirementRequest r = request.requirement();
        return PackingRequirementResponse.from(service.update(
                p,
                id,
                request.expectedVersion(),
                r.type(),
                r.assetModelId(),
                r.specificAssetReference(),
                r.requiredQuantity()));
    }

    @PostMapping("/packing-requirements/{id}/restore")
    public PackingRequirementResponse restore(
            @AuthenticationPrincipal BigContainersPrincipal p,
            @PathVariable UUID id,
            @RequestBody LocationVersionRequest r) {
        return PackingRequirementResponse.from(service.restore(p, id, r.expectedVersion()));
    }

    @GetMapping("/packing-templates")
    public List<PackingTemplateResponse> templates(@AuthenticationPrincipal BigContainersPrincipal p) {
        return service.listTemplates(p).stream()
                .map(PackingTemplateResponse::from)
                .toList();
    }

    @PostMapping("/packing-templates")
    public ResponseEntity<PackingTemplateResponse> createTemplate(
            @AuthenticationPrincipal BigContainersPrincipal p, @Valid @RequestBody PackingTemplateRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(PackingTemplateResponse.from(service.createTemplate(p, r.name(), r.description())));
    }

    @PutMapping("/packing-templates/{templateId}")
    public PackingTemplateResponse updateTemplate(
            @AuthenticationPrincipal BigContainersPrincipal p,
            @PathVariable UUID templateId,
            @Valid @RequestBody PackingTemplateMutationRequest request) {
        return PackingTemplateResponse.from(service.updateTemplate(
                p, templateId, request.expectedVersion(), request.name(), request.description()));
    }

    @PostMapping("/packing-templates/{templateId}/{action:archive|restore}")
    public PackingTemplateResponse setTemplateArchived(
            @AuthenticationPrincipal BigContainersPrincipal p,
            @PathVariable UUID templateId,
            @PathVariable String action,
            @RequestBody LocationVersionRequest request) {
        return PackingTemplateResponse.from(
                service.setTemplateArchived(p, templateId, request.expectedVersion(), action.equals("archive")));
    }

    @PostMapping("/packing-templates/{templateId}/requirements")
    public PackingTemplateResponse addTemplateRequirement(
            @AuthenticationPrincipal BigContainersPrincipal p,
            @PathVariable UUID templateId,
            @RequestBody PackingRequirementRequest request) {
        return PackingTemplateResponse.from(service.addTemplateRequirement(
                p,
                templateId,
                request.type(),
                request.assetModelId(),
                request.specificAssetReference(),
                request.requiredQuantity()));
    }

    @PutMapping("/packing-template-requirements/{id}")
    public PackingTemplateResponse updateTemplateRequirement(
            @AuthenticationPrincipal BigContainersPrincipal p,
            @PathVariable UUID id,
            @Valid @RequestBody PackingRequirementMutationRequest request) {
        PackingRequirementRequest r = request.requirement();
        return PackingTemplateResponse.from(service.updateTemplateRequirement(
                p,
                id,
                request.expectedVersion(),
                r.type(),
                r.assetModelId(),
                r.specificAssetReference(),
                r.requiredQuantity()));
    }

    @PostMapping("/packing-template-requirements/{id}/{action:archive|restore}")
    public PackingTemplateResponse setTemplateRequirementArchived(
            @AuthenticationPrincipal BigContainersPrincipal p,
            @PathVariable UUID id,
            @PathVariable String action,
            @RequestBody LocationVersionRequest request) {
        return PackingTemplateResponse.from(
                service.setTemplateRequirementArchived(p, id, request.expectedVersion(), action.equals("archive")));
    }

    @PostMapping("/assets/{containerAssetId}/packing-templates/{templateId}/apply")
    public List<PackingRequirementResponse> apply(
            @AuthenticationPrincipal BigContainersPrincipal p,
            @PathVariable UUID containerAssetId,
            @PathVariable UUID templateId) {
        return service.applyTemplate(p, containerAssetId, templateId).stream()
                .map(PackingRequirementResponse::from)
                .toList();
    }
}
