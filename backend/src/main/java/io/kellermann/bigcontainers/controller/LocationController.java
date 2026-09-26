package io.kellermann.bigcontainers.controller;

import io.kellermann.bigcontainers.security.BigContainersPrincipal;
import io.kellermann.bigcontainers.service.LocationService;
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
    public List<LocationResponse> list(@AuthenticationPrincipal BigContainersPrincipal p) {
        return service.list(p).stream().map(LocationResponse::from).toList();
    }

    @GetMapping("/{locationId}")
    public LocationResponse get(@AuthenticationPrincipal BigContainersPrincipal p, @PathVariable UUID locationId) {
        return LocationResponse.from(service.get(p, locationId));
    }

    @PostMapping
    public ResponseEntity<LocationResponse> create(
            @AuthenticationPrincipal BigContainersPrincipal p, @Valid @RequestBody LocationRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(LocationResponse.from(service.create(p, r.name(), r.description(), r.parentLocationId())));
    }

    @PutMapping("/{locationId}")
    public LocationResponse update(
            @AuthenticationPrincipal BigContainersPrincipal p,
            @PathVariable UUID locationId,
            @Valid @RequestBody LocationRequest r) {
        if (r.expectedVersion() == null) {
            throw new io.kellermann.bigcontainers.exception.ValidationFailedException("expectedVersion is required.");
        }
        return LocationResponse.from(
                service.update(p, locationId, r.name(), r.description(), r.parentLocationId(), r.expectedVersion()));
    }

    @PostMapping("/{locationId}/archive")
    public ResponseEntity<Void> archive(
            @AuthenticationPrincipal BigContainersPrincipal p,
            @PathVariable UUID locationId,
            @RequestBody LocationVersionRequest request) {
        service.archive(p, locationId, request.expectedVersion());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{locationId}/restore")
    public ResponseEntity<Void> restore(
            @AuthenticationPrincipal BigContainersPrincipal p,
            @PathVariable UUID locationId,
            @RequestBody LocationVersionRequest request) {
        service.restore(p, locationId, request.expectedVersion());
        return ResponseEntity.noContent().build();
    }
}
