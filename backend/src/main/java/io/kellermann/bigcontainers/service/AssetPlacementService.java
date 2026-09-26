package io.kellermann.bigcontainers.service;

import io.kellermann.bigcontainers.exception.NotFoundException;
import io.kellermann.bigcontainers.exception.StalePlacementVersionException;
import io.kellermann.bigcontainers.exception.ValidationFailedException;
import io.kellermann.bigcontainers.model.Asset;
import io.kellermann.bigcontainers.model.AssetModel;
import io.kellermann.bigcontainers.model.Location;
import io.kellermann.bigcontainers.model.OrganizationRole;
import io.kellermann.bigcontainers.repository.AssetModelRepository;
import io.kellermann.bigcontainers.repository.AssetRepository;
import io.kellermann.bigcontainers.repository.LocationRepository;
import io.kellermann.bigcontainers.repository.OrganizationRepository;
import io.kellermann.bigcontainers.security.BigContainersPrincipal;
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
    private final Clock clock;

    public AssetPlacementService(
            AssetRepository assets,
            AssetModelRepository models,
            LocationRepository locations,
            OrganizationRepository organizations,
            ActivityLogService activity,
            Clock clock) {
        this.assets = assets;
        this.models = models;
        this.locations = locations;
        this.organizations = organizations;
        this.activity = activity;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AssetPlacementView get(BigContainersPrincipal principal, UUID assetId) {
        requireAuthenticated(principal);
        return view(requireAsset(principal.organizationId(), assetId), principal.organizationId());
    }

    @Transactional(readOnly = true)
    public List<AssetPlacementView> contents(BigContainersPrincipal principal, UUID containerAssetId) {
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
            BigContainersPrincipal principal,
            UUID assetId,
            UUID locationId,
            UUID parentContainerAssetId,
            long expectedVersion) {
        requireOwnerOrDeputy(principal);
        if (locationId != null && parentContainerAssetId != null)
            throw new ValidationFailedException("Choose a location or a container, not both.");
        lockOrganization(principal.organizationId());
        Asset asset = assets.findWithLockByIdAndOrganizationId(assetId, principal.organizationId())
                .orElseThrow(() -> new NotFoundException("Asset not found."));
        if (asset.getVersion() != expectedVersion) throw new StalePlacementVersionException();
        if (locationId != null) {
            Location location = locations
                    .findByIdAndOrganizationId(locationId, principal.organizationId())
                    .orElseThrow(() -> new NotFoundException("Location not found."));
            if (location.isArchived())
                throw new ValidationFailedException("An archived location cannot hold inventory.");
        }
        if (parentContainerAssetId != null)
            validateContainerParent(principal.organizationId(), asset, parentContainerAssetId);
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
        // Flush makes the version included in this response usable for a consecutive move.
        assets.flush();
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

    private static void requireOwnerOrDeputy(BigContainersPrincipal p) {
        if (p == null || (p.role() != OrganizationRole.OWNER && p.role() != OrganizationRole.DEPUTY))
            throw new AccessDeniedException("Owner or Deputy role required.");
    }

    private static void requireAuthenticated(BigContainersPrincipal p) {
        if (p == null) throw new AccessDeniedException("Authentication required.");
    }
}
