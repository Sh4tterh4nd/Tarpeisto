package io.kellermann.bigcontainers.security;

import io.kellermann.bigcontainers.model.OrganizationRole;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The transient, password-hash-bearing {@link UserDetails} used only while {@code
 * DaoAuthenticationProvider} performs the password check. {@link SessionAuthenticationService}
 * reads {@link #userId()}/{@link #displayName()}/{@link #organizationId()}/{@link #role()} off of
 * this object exactly once, immediately after a successful authentication, to build the
 * password-free {@link BigContainersPrincipal} that actually gets persisted into the session; this
 * object itself is discarded and never serialized.
 */
final class BigContainersUserDetails implements UserDetails {

    private final UUID userId;
    private final String username;
    private final String passwordHash;
    private final String displayName;
    private final UUID organizationId;
    private final OrganizationRole role;
    private final boolean enabled;

    BigContainersUserDetails(
            UUID userId,
            String username,
            String passwordHash,
            String displayName,
            UUID organizationId,
            OrganizationRole role,
            boolean enabled) {
        this.userId = userId;
        this.username = username;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.organizationId = organizationId;
        this.role = role;
        this.enabled = enabled;
    }

    UUID userId() {
        return userId;
    }

    String displayName() {
        return displayName;
    }

    UUID organizationId() {
        return organizationId;
    }

    OrganizationRole role() {
        return role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
