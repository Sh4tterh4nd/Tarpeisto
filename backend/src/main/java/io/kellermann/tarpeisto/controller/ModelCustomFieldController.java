package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import io.kellermann.tarpeisto.service.ModelCustomFieldService;
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
 * Model-defined unit field administration (specification section 7), nested under its owning
 * asset model. Authorization ("must be Owner or Deputy to mutate") is enforced in {@link
 * ModelCustomFieldService}, per docs/DEVELOPMENT_POLICIES.md section 5.1.
 */
@RestController
@RequestMapping("/api/v1/asset-models/{assetModelId}/custom-fields")
public class ModelCustomFieldController {

    private final ModelCustomFieldService modelCustomFieldService;

    public ModelCustomFieldController(ModelCustomFieldService modelCustomFieldService) {
        this.modelCustomFieldService = modelCustomFieldService;
    }

    @GetMapping
    public List<ModelCustomFieldResponse> list(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID assetModelId,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") boolean includeArchived) {
        return modelCustomFieldService.list(principal, assetModelId, includeArchived).stream()
                .map(ModelCustomFieldResponse::from)
                .toList();
    }

    @PostMapping
    public ResponseEntity<ModelCustomFieldResponse> create(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID assetModelId,
            @Valid @RequestBody CreateModelCustomFieldRequest request) {
        var created = modelCustomFieldService.create(principal, assetModelId, request.name(), request.dataType());
        return ResponseEntity.status(HttpStatus.CREATED).body(ModelCustomFieldResponse.from(created));
    }

    @PutMapping("/{fieldId}")
    public ModelCustomFieldResponse rename(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID assetModelId,
            @PathVariable UUID fieldId,
            @Valid @RequestBody RenameModelCustomFieldRequest request) {
        return ModelCustomFieldResponse.from(
                modelCustomFieldService.rename(principal, assetModelId, fieldId, request.name()));
    }

    @PutMapping("/{fieldId}/data-type")
    public ModelCustomFieldResponse changeDataType(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID assetModelId,
            @PathVariable UUID fieldId,
            @Valid @RequestBody ChangeModelCustomFieldDataTypeRequest request) {
        return ModelCustomFieldResponse.from(
                modelCustomFieldService.changeDataType(principal, assetModelId, fieldId, request.dataType()));
    }

    @PutMapping("/{fieldId}/display-order")
    public ModelCustomFieldResponse reorder(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID assetModelId,
            @PathVariable UUID fieldId,
            @RequestBody ReorderRequest request) {
        return ModelCustomFieldResponse.from(
                modelCustomFieldService.reorder(principal, assetModelId, fieldId, request.displayOrder()));
    }

    @PostMapping("/{fieldId}/archive")
    public ResponseEntity<Void> archive(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID assetModelId,
            @PathVariable UUID fieldId) {
        modelCustomFieldService.archive(principal, assetModelId, fieldId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{fieldId}/restore")
    public ResponseEntity<Void> restore(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID assetModelId,
            @PathVariable UUID fieldId) {
        modelCustomFieldService.restore(principal, assetModelId, fieldId);
        return ResponseEntity.noContent().build();
    }
}
