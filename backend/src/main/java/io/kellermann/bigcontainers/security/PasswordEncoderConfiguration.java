package io.kellermann.bigcontainers.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Adaptive, versioned local-password hashing (ADR-0003, implementation plan section 3.2): {@link
 * PasswordEncoderFactories#createDelegatingPasswordEncoder()} prefixes every stored hash with an
 * encoding identifier (currently {@code {bcrypt}}), so the algorithm and cost can be upgraded
 * later without invalidating already-stored hashes - new hashes use the current default id, and
 * verification dispatches to whichever encoder matches the stored prefix.
 */
@Configuration(proxyBeanMethods = false)
public class PasswordEncoderConfiguration {

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
