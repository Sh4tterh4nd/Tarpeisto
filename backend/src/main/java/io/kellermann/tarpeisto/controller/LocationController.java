package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import io.kellermann.tarpeisto.service.LocationService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/locations")
public class LocationController {
    private final LocationService service;

    public LocationController(LocationService service) {
        this.service = service;
    }

    @GetMapping
    public List<LocationResponse> list(
            @AuthenticationPrincipal TarpeistoPrincipal p,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") boolean includeArchived) {
        return service.list(p, includeArchived).stream()
                .map(LocationResponse::from)
                .toList();
    }

    @GetMapping("/{locationId}")
    public LocationResponse get(@AuthenticationPrincipal TarpeistoPrincipal p, @PathVariable UUID locationId) {
        return LocationResponse.from(service.get(p, locationId));
    }

    @PostMapping
    public ResponseEntity<LocationResponse> create(
            @AuthenticationPrincipal TarpeistoPrincipal p, @Valid @RequestBody LocationRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(LocationResponse.from(service.create(p, r.name(), r.description(), r.parentLocationId())));
    }

    @PutMapping("/{locationId}")
    public LocationResponse update(
            @AuthenticationPrincipal TarpeistoPrincipal p,
            @PathVariable UUID locationId,
            @Valid @RequestBody LocationRequest r) {
        if (r.expectedVersion() == null) {
            throw new io.kellermann.tarpeisto.exception.ValidationFailedException("expectedVersion is required.");
        }
        return LocationResponse.from(
                service.update(p, locationId, r.name(), r.description(), r.parentLocationId(), r.expectedVersion()));
    }

    @PostMapping("/{locationId}/archive")
    public ResponseEntity<Void> archive(
            @AuthenticationPrincipal TarpeistoPrincipal p,
            @PathVariable UUID locationId,
            @RequestBody LocationVersionRequest request) {
        service.archive(p, locationId, request.expectedVersion());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{locationId}/restore")
    public ResponseEntity<Void> restore(
            @AuthenticationPrincipal TarpeistoPrincipal p,
            @PathVariable UUID locationId,
            @RequestBody LocationVersionRequest request) {
        service.restore(p, locationId, request.expectedVersion());
        return ResponseEntity.noContent().build();
    }
}
