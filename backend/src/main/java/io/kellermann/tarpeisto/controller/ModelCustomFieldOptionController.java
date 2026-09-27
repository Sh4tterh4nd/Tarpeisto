package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import io.kellermann.tarpeisto.service.ModelCustomFieldOptionService;
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
 * Dropdown option administration for a model-defined unit field (specification section 7.2),
 * nested under its owning field. Authorization is enforced in {@link ModelCustomFieldOptionService},
 * per docs/DEVELOPMENT_POLICIES.md section 5.1.
 */
@RestController
@RequestMapping("/api/v1/asset-models/{assetModelId}/custom-fields/{fieldId}/options")
public class ModelCustomFieldOptionController {

    private final ModelCustomFieldOptionService optionService;

    public ModelCustomFieldOptionController(ModelCustomFieldOptionService optionService) {
        this.optionService = optionService;
    }

    @GetMapping
    public List<ModelCustomFieldOptionResponse> list(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID assetModelId,
            @PathVariable UUID fieldId) {
        return optionService.list(principal, assetModelId, fieldId).stream()
                .map(ModelCustomFieldOptionResponse::from)
                .toList();
    }

    @PostMapping
    public ResponseEntity<ModelCustomFieldOptionResponse> create(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID assetModelId,
            @PathVariable UUID fieldId,
            @Valid @RequestBody CreateModelCustomFieldOptionRequest request) {
        var created = optionService.create(principal, assetModelId, fieldId, request.value());
        return ResponseEntity.status(HttpStatus.CREATED).body(ModelCustomFieldOptionResponse.from(created));
    }

    @PutMapping("/{optionId}")
    public ModelCustomFieldOptionResponse rename(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID assetModelId,
            @PathVariable UUID fieldId,
            @PathVariable UUID optionId,
            @Valid @RequestBody RenameModelCustomFieldOptionRequest request) {
        return ModelCustomFieldOptionResponse.from(
                optionService.rename(principal, assetModelId, fieldId, optionId, request.value()));
    }

    @PutMapping("/{optionId}/display-order")
    public ModelCustomFieldOptionResponse reorder(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID assetModelId,
            @PathVariable UUID fieldId,
            @PathVariable UUID optionId,
            @RequestBody ReorderRequest request) {
        return ModelCustomFieldOptionResponse.from(
                optionService.reorder(principal, assetModelId, fieldId, optionId, request.displayOrder()));
    }

    @PostMapping("/{optionId}/archive")
    public ResponseEntity<Void> archive(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID assetModelId,
            @PathVariable UUID fieldId,
            @PathVariable UUID optionId) {
        optionService.archive(principal, assetModelId, fieldId, optionId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{optionId}/restore")
    public ResponseEntity<Void> restore(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID assetModelId,
            @PathVariable UUID fieldId,
            @PathVariable UUID optionId) {
        optionService.restore(principal, assetModelId, fieldId, optionId);
        return ResponseEntity.noContent().build();
    }
}
