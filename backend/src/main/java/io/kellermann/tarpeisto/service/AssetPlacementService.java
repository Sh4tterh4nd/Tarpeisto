package io.kellermann.tarpeisto.service;

import io.kellermann.tarpeisto.exception.NotFoundException;
import io.kellermann.tarpeisto.exception.StalePlacementVersionException;
import io.kellermann.tarpeisto.exception.ValidationFailedException;
import io.kellermann.tarpeisto.model.Asset;
import io.kellermann.tarpeisto.model.AssetModel;
import io.kellermann.tarpeisto.model.Location;
import io.kellermann.tarpeisto.model.OrganizationRole;
import io.kellermann.tarpeisto.repository.AssetModelRepository;
import io.kellermann.tarpeisto.repository.AssetRepository;
import io.kellermann.tarpeisto.repository.CheckoutManifestAssetRepository;
import io.kellermann.tarpeisto.repository.LocationRepository;
import io.kellermann.tarpeisto.repository.OrganizationRepository;
import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Serialized, tenant-safe asset placement and containment operations. */
@Service
public class AssetPlacementService {
    private final AssetRepository assets;
    private final AssetModelRepository models;
    private final LocationRepository locations;
    private final OrganizationRepository organizations;
    private final ActivityLogService activity;
    private final BookingImpactService bookingImpact;
    private final CheckoutManifestAssetRepository checkoutManifestAssets;
    private final Clock clock;
    private final AssetSealService seals;
    private final PackingFindingReconciliationService reconciliation;

    public AssetPlacementService(
            AssetRepository assets,
            AssetModelRepository models,
            LocationRepository locations,
            OrganizationRepository organizations,
            ActivityLogService activity,
            BookingImpactService bookingImpact,
            CheckoutManifestAssetRepository checkoutManifestAssets,
            AssetSealService seals,
            PackingFindingReconciliationService reconciliation,
            Clock clock) {
        this.assets = assets;
        this.models = models;
        this.locations = locations;
        this.organizations = organizations;
        this.activity = activity;
        this.bookingImpact = bookingImpact;
        this.checkoutManifestAssets = checkoutManifestAssets;
        this.clock = clock;
        this.seals = seals;
        this.reconciliation = reconciliation;
    }

    @Transactional(readOnly = true)
    public AssetPlacementView get(TarpeistoPrincipal principal, UUID assetId) {
        if (principal != null) principal.requirePermanent();
        requireAuthenticated(principal);
        return view(requireAsset(principal.organizationId(), assetId), principal.organizationId());
    }

    @Transactional(readOnly = true)
    public List<AssetPlacementView> contents(TarpeistoPrincipal principal, UUID containerAssetId) {
        if (principal != null) principal.requirePermanent();
        requireAuthenticated(principal);
        requireAsset(principal.organizationId(), containerAssetId);
        return assets
                .findAllByOrganizationIdAndParentContainerAssetIdOrderByUnitNumberAsc(
                        principal.organizationId(), containerAssetId)
                .stream()
                .map(asset -> view(asset, principal.organizationId()))
                .toList();
    }

    @Transactional
    public AssetPlacementView move(
            TarpeistoPrincipal principal,
            UUID assetId,
            UUID locationId,
            UUID parentContainerAssetId,
            long expectedVersion) {
        if (principal != null) principal.requirePermanent();
        requireOwnerOrDeputy(principal);
        if (locationId != null && parentContainerAssetId != null)
            throw new ValidationFailedException("Choose a location or a container, not both.");
        lockOrganization(principal.organizationId());
        Asset asset = assets.findWithLockByIdAndOrganizationId(assetId, principal.organizationId())
                .orElseThrow(() -> new NotFoundException("Asset not found."));
        if (checkoutManifestAssets.existsByOrganizationIdAndAssetIdAndAuditReleasedAtIsNull(
                principal.organizationId(), assetId)) {
            throw new ValidationFailedException(
                    "A checked-out asset cannot be moved until it is checked in and audited.");
        }
        if (asset.getVersion() != expectedVersion) throw new StalePlacementVersionException();
        if (locationId != null) {
            Location location = locations
                    .findByIdAndOrganizationId(locationId, principal.organizationId())
                    .orElseThrow(() -> new NotFoundException("Location not found."));
            if (location.isArchived())
                throw new ValidationFailedException("An archived location cannot hold inventory.");
        }
        if (parentContainerAssetId != null) {
            if (checkoutManifestAssets.existsByOrganizationIdAndAssetIdAndAuditReleasedAtIsNull(
                    principal.organizationId(), parentContainerAssetId)) {
                throw new ValidationFailedException("A checked-out container cannot have its packing changed.");
            }
            validateContainerParent(principal.organizationId(), asset, parentContainerAssetId);
        }
        UUID previousLocationId = asset.getDirectLocationId();
        UUID previousParentContainerAssetId = asset.getParentContainerAssetId();
        try {
            asset.moveTo(locationId, parentContainerAssetId, clock.instant());
        } catch (IllegalArgumentException e) {
            throw new ValidationFailedException(e.getMessage());
        }
        activity.record(
                principal.organizationId(),
                principal.userId(),
                "ASSET_MOVED",
                "ASSET",
                assetId,
                Map.of(
                        "previousLocationId",
                        String.valueOf(previousLocationId),
                        "previousParentContainerAssetId",
                        String.valueOf(previousParentContainerAssetId),
                        "locationId",
                        String.valueOf(locationId),
                        "parentContainerAssetId",
                        String.valueOf(parentContainerAssetId)));
        if (!java.util.Objects.equals(previousParentContainerAssetId, parentContainerAssetId)) {
            if (previousParentContainerAssetId != null)
                seals.invalidate(principal, previousParentContainerAssetId, "Direct contents moved.");
            if (parentContainerAssetId != null)
                seals.invalidate(principal, parentContainerAssetId, "Direct contents moved.");
        }
        // Flush makes the version included in this response usable for a consecutive move.
        assets.flush();
        reconciliation.schedule(principal, previousParentContainerAssetId);
        reconciliation.schedule(principal, parentContainerAssetId);
        bookingImpact.changed(principal);
        return view(asset, principal.organizationId());
    }

    private void validateContainerParent(UUID organizationId, Asset asset, UUID parentId) {
        Asset parent = assets.findWithLockByIdAndOrganizationId(parentId, organizationId)
                .orElseThrow(() -> new NotFoundException("Parent container not found."));
        if (!parent.isActive())
            throw new ValidationFailedException("An archived or inactive asset cannot be a container.");
        AssetModel parentModel = models.findByIdAndOrganizationId(parent.getAssetModelId(), organizationId)
                .orElseThrow(() -> new NotFoundException("Parent container not found."));
        if (!parentModel.isCanContainAssets())
            throw new ValidationFailedException("The selected parent asset is not container-capable.");
        HashSet<UUID> seen = new HashSet<>();
        for (Asset at = parent; at != null; ) {
            if (!seen.add(at.getId())) throw new ValidationFailedException("Containment hierarchy is invalid.");
            if (at.getId().equals(asset.getId()))
                throw new ValidationFailedException("A container cannot be moved into itself or a descendant.");
            UUID next = at.getParentContainerAssetId();
            at = next == null
                    ? null
                    : assets.findWithLockByIdAndOrganizationId(next, organizationId)
                            .orElseThrow(() -> new ValidationFailedException("Containment hierarchy is invalid."));
        }
    }

    private AssetPlacementView view(Asset asset, UUID org) {
        List<String> path = new ArrayList<>();
        Map<UUID, Location> locationIndex = new HashMap<>();
        locations.findAllByOrganizationIdOrderByNameAsc(org).forEach(l -> locationIndex.put(l.getId(), l));
        Asset outer = asset;
        HashSet<UUID> outerSeen = new HashSet<>();
        while (outer.getParentContainerAssetId() != null) {
            if (!outerSeen.add(outer.getId())) throw new IllegalStateException("Containment hierarchy is invalid.");
            outer = assets.findByIdAndOrganizationId(outer.getParentContainerAssetId(), org)
                    .orElseThrow(() -> new IllegalStateException("Containment hierarchy is invalid."));
        }
        if (outer != null && outer.getDirectLocationId() != null) {
            HashSet<UUID> locationSeen = new HashSet<>();
            for (Location l = locationIndex.get(outer.getDirectLocationId());
                    l != null;
                    l = locationIndex.get(l.getParentLocationId())) {
                if (!locationSeen.add(l.getId())) throw new IllegalStateException("Location hierarchy is invalid.");
                path.add(0, l.getName());
            }
        }
        List<Asset> ancestry = new ArrayList<>();
        HashSet<UUID> ancestrySeen = new HashSet<>();
        for (Asset at = asset; at != null; ) {
            if (!ancestrySeen.add(at.getId())) throw new IllegalStateException("Containment hierarchy is invalid.");
            ancestry.add(0, at);
            at = at.getParentContainerAssetId() == null
                    ? null
                    : assets.findByIdAndOrganizationId(at.getParentContainerAssetId(), org)
                            .orElseThrow(() -> new IllegalStateException("Containment hierarchy is invalid."));
        }
        for (Asset at : ancestry)
            path.add(at.getIndividualName() != null ? at.getIndividualName() : at.getPublicCode());
        return new AssetPlacementView(
                asset.getId(),
                asset.getDirectLocationId(),
                asset.getParentContainerAssetId(),
                asset.getVersion(),
                path,
                String.join(" / ", path));
    }

    private Asset requireAsset(UUID org, UUID id) {
        return assets.findByIdAndOrganizationId(id, org).orElseThrow(() -> new NotFoundException("Asset not found."));
    }

    private void lockOrganization(UUID org) {
        organizations.findWithLockById(org).orElseThrow(() -> new NotFoundException("Organization not found."));
    }

    private static void requireOwnerOrDeputy(TarpeistoPrincipal p) {
        if (p == null || (p.role() != OrganizationRole.OWNER && p.role() != OrganizationRole.DEPUTY))
            throw new AccessDeniedException("Owner or Deputy role required.");
    }

    private static void requireAuthenticated(TarpeistoPrincipal p) {
        if (p == null) throw new AccessDeniedException("Authentication required.");
    }
}
