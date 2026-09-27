package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.service.InitialSetupService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Unauthenticated, one-time installation setup boundary. */
@RestController
@RequestMapping("/api/v1/setup")
public class InitialSetupController {

    private final InitialSetupService initialSetupService;

    public InitialSetupController(InitialSetupService initialSetupService) {
        this.initialSetupService = initialSetupService;
    }

    @GetMapping
    public InitialSetupStatusResponse status() {
        return new InitialSetupStatusResponse(initialSetupService.isSetupRequired());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public void completeInitialSetup(@Valid @RequestBody CompleteInitialSetupRequest request) {
        initialSetupService.complete(
                request.organizationName(),
                request.username(),
                request.password(),
                request.displayName(),
                request.email());
    }
}
