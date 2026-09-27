package io.kellermann.tarpeisto.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Controls first-run seeding behavior. Bound from {@code tarpeisto.seed.*}.
 *
 * <p>No business code may hard-code an organization name, owner username, or identifier, so these
 * values are always externally configurable.
 *
 * @param defaultOrganizationName the name of the single default organization created on first
 *     run if no organization with this name exists yet
 * @param ownerUsername the local login username for the first-run Owner account; if blank (the
 *     default), {@code io.kellermann.tarpeisto.service.OwnerSeeder} creates no Owner and the
 *     operator must set this (and {@code ownerPassword}) explicitly to bootstrap access
 * @param ownerPassword the first-run Owner's initial local password; required alongside {@code
 *     ownerUsername} to bootstrap an Owner, never logged
 * @param ownerDisplayName the first-run Owner's display name
 * @param ownerEmail the first-run Owner's optional profile email
 */
@ConfigurationProperties(prefix = "tarpeisto.seed")
@Validated
public record SeedProperties(
        @NotBlank String defaultOrganizationName,
        String ownerUsername,
        String ownerPassword,
        String ownerDisplayName,
        String ownerEmail) {}
