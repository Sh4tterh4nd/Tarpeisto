package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import io.kellermann.tarpeisto.service.ReviewService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Review endpoints intentionally expose findings but never mutate completed audit observations. */
@RestController
@RequestMapping("/api/v1")
public class ReviewController {
    private final ReviewService review;

    public ReviewController(ReviewService review) {
        this.review = review;
    }

    @GetMapping("/findings")
    public List<FindingReviewResponse> list(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @RequestParam(defaultValue = "true") boolean unresolvedOnly) {
        return review.list(principal, unresolvedOnly).stream()
                .map(FindingReviewResponse::from)
                .toList();
    }

    @GetMapping("/findings/{findingId}")
    public FindingReviewResponse get(
            @AuthenticationPrincipal TarpeistoPrincipal principal, @PathVariable UUID findingId) {
        return FindingReviewResponse.from(review.get(principal, findingId));
    }

    @PostMapping("/findings/{findingId}/resolutions")
    public FindingReviewResponse resolve(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID findingId,
            @Valid @RequestBody ResolveFindingRequest request) {
        return FindingReviewResponse.from(review.resolve(
                principal,
                findingId,
                request.operationId(),
                request.action(),
                request.note(),
                request.targetAssetId(),
                request.targetContainerAssetId(),
                request.repairReference()));
    }

    @GetMapping("/assets/{assetId}/repairs")
    public List<RepairResponse> repairs(
            @AuthenticationPrincipal TarpeistoPrincipal principal, @PathVariable UUID assetId) {
        return review.repairs(principal, assetId).stream()
                .map(RepairResponse::from)
                .toList();
    }

    @PostMapping("/assets/{assetId}/repairs")
    public ResponseEntity<RepairResponse> openRepair(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID assetId,
            @Valid @RequestBody OpenRepairRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(RepairResponse.from(review.openRepair(
                        principal, assetId, request.sourceFindingId(), request.referenceOrDescription())));
    }

    @PostMapping("/repairs/{repairId}/close")
    public RepairResponse closeRepair(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID repairId,
            @Valid @RequestBody CloseRepairRequest request) {
        return RepairResponse.from(review.closeRepair(principal, repairId, request.resultingCondition()));
    }
}
