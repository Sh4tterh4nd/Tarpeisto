package io.kellermann.tarpeisto.service;

import io.kellermann.tarpeisto.exception.NotFoundException;
import io.kellermann.tarpeisto.exception.StaleLocationVersionException;
import io.kellermann.tarpeisto.exception.ValidationFailedException;
import io.kellermann.tarpeisto.model.Location;
import io.kellermann.tarpeisto.model.OrganizationRole;
import io.kellermann.tarpeisto.repository.AssetRepository;
import io.kellermann.tarpeisto.repository.ConsumableStockRepository;
import io.kellermann.tarpeisto.repository.LocationRepository;
import io.kellermann.tarpeisto.repository.OrganizationRepository;
import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import java.math.BigDecimal;
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

/** Location hierarchy operations; mutations serialize through the organization row. */
@Service
public class LocationService {
    private final LocationRepository locations;
    private final OrganizationRepository organizations;
    private final AssetRepository assets;
    private final ConsumableStockRepository stock;
    private final ActivityLogService activity;
    private final Clock clock;

    public LocationService(
            LocationRepository locations,
            OrganizationRepository organizations,
            AssetRepository assets,
            ConsumableStockRepository stock,
            ActivityLogService activity,
            Clock clock) {
        this.locations = locations;
        this.organizations = organizations;
        this.assets = assets;
        this.stock = stock;
        this.activity = activity;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<LocationView> list(TarpeistoPrincipal principal) {
        requireAuthenticated(principal);
        return views(principal.organizationId());
    }

    @Transactional(readOnly = true)
    public LocationView get(TarpeistoPrincipal principal, UUID id) {
        requireAuthenticated(principal);
        return view(require(principal.organizationId(), id), index(principal.organizationId()));
    }

    @Transactional
    public LocationView create(TarpeistoPrincipal principal, String name, String description, UUID parentId) {
        requireOwnerOrDeputy(principal);
        lockOrganization(principal.organizationId());
        validateParent(principal.organizationId(), null, parentId);
        Location location;
        try {
            location = new Location(
                    UUID.randomUUID(), principal.organizationId(), name, description, parentId, clock.instant());
        } catch (IllegalArgumentException exception) {
            throw new ValidationFailedException(exception.getMessage());
        }
        locations.save(location);
        activity.record(
                principal.organizationId(), principal.userId(), "LOCATION_CREATED", "LOCATION", location.getId(), null);
        return view(location, index(principal.organizationId()));
    }

    @Transactional
    public LocationView update(
            TarpeistoPrincipal principal,
            UUID id,
            String name,
            String description,
            UUID parentId,
            long expectedVersion) {
        requireOwnerOrDeputy(principal);
        lockOrganization(principal.organizationId());
        Location location = require(principal.organizationId(), id);
        if (location.getVersion() != expectedVersion) throw new StaleLocationVersionException();
        validateParent(principal.organizationId(), id, parentId);
        try {
            location.update(name, description, parentId, clock.instant());
        } catch (IllegalArgumentException exception) {
            throw new ValidationFailedException(exception.getMessage());
        }
        activity.record(principal.organizationId(), principal.userId(), "LOCATION_UPDATED", "LOCATION", id, null);
        locations.flush();
        return view(location, index(principal.organizationId()));
    }

    @Transactional
    public void archive(TarpeistoPrincipal principal, UUID id, long expectedVersion) {
        requireOwnerOrDeputy(principal);
        lockOrganization(principal.organizationId());
        Location location = require(principal.organizationId(), id);
        if (location.getVersion() != expectedVersion) throw new StaleLocationVersionException();
        if (locations.existsByOrganizationIdAndParentLocationId(principal.organizationId(), id)
                || assets.existsByOrganizationIdAndDirectLocationId(principal.organizationId(), id)
                || stock.existsByOrganizationIdAndLocationIdAndQuantityGreaterThan(
                        principal.organizationId(), id, BigDecimal.ZERO)) {
            throw new ValidationFailedException("A location with children or inventory cannot be archived.");
        }
        location.archive(clock.instant());
        activity.record(principal.organizationId(), principal.userId(), "LOCATION_ARCHIVED", "LOCATION", id, null);
    }

    @Transactional
    public void restore(TarpeistoPrincipal principal, UUID id, long expectedVersion) {
        requireOwnerOrDeputy(principal);
        lockOrganization(principal.organizationId());
        Location location = require(principal.organizationId(), id);
        if (location.getVersion() != expectedVersion) throw new StaleLocationVersionException();
        location.restore(clock.instant());
        activity.record(principal.organizationId(), principal.userId(), "LOCATION_RESTORED", "LOCATION", id, null);
        locations.flush();
    }

    private void validateParent(UUID organizationId, UUID locationId, UUID parentId) {
        if (parentId == null) return;
        if (parentId.equals(locationId)) throw new ValidationFailedException("A location cannot be its own parent.");
        Map<UUID, Location> byId = index(organizationId);
        Location parent = byId.get(parentId);
        if (parent == null) throw new NotFoundException("Parent location not found.");
        if (parent.isArchived())
            throw new ValidationFailedException("An archived location cannot be selected as a parent.");
        HashSet<UUID> seen = new HashSet<>();
        for (Location ancestor = parent; ancestor != null; ancestor = byId.get(ancestor.getParentLocationId())) {
            if (!seen.add(ancestor.getId())) throw new ValidationFailedException("Location hierarchy is invalid.");
            if (ancestor.getId().equals(locationId))
                throw new ValidationFailedException("A location cannot be moved into its descendant.");
        }
    }

    private void lockOrganization(UUID organizationId) {
        organizations
                .findWithLockById(organizationId)
                .orElseThrow(() -> new NotFoundException("Organization not found."));
    }

    private Location require(UUID organizationId, UUID id) {
        return locations
                .findByIdAndOrganizationId(id, organizationId)
                .orElseThrow(() -> new NotFoundException("Location not found."));
    }

    private List<LocationView> views(UUID organizationId) {
        Map<UUID, Location> index = index(organizationId);
        return index.values().stream()
                .map(location -> view(location, index))
                .sorted(java.util.Comparator.comparing(LocationView::effectivePath))
                .toList();
    }

    private Map<UUID, Location> index(UUID organizationId) {
        Map<UUID, Location> index = new HashMap<>();
        locations
                .findAllByOrganizationIdOrderByNameAsc(organizationId)
                .forEach(location -> index.put(location.getId(), location));
        return index;
    }

    private LocationView view(Location location, Map<UUID, Location> index) {
        List<String> names = new ArrayList<>();
        HashSet<UUID> seen = new HashSet<>();
        for (Location at = location; at != null; at = index.get(at.getParentLocationId())) {
            if (!seen.add(at.getId())) throw new IllegalStateException("Location hierarchy is invalid.");
            names.add(0, at.getName());
        }
        return new LocationView(
                location.getId(),
                location.getName(),
                location.getDescription(),
                location.getParentLocationId(),
                location.isArchived(),
                location.getVersion(),
                names,
                String.join(" / ", names),
                location.getCreatedAt(),
                location.getUpdatedAt());
    }

    private static void requireOwnerOrDeputy(TarpeistoPrincipal p) {
        if (p == null || (p.role() != OrganizationRole.OWNER && p.role() != OrganizationRole.DEPUTY))
            throw new AccessDeniedException("Owner or Deputy role required.");
    }

    private static void requireAuthenticated(TarpeistoPrincipal p) {
        if (p == null) throw new AccessDeniedException("Authentication required.");
    }
}
