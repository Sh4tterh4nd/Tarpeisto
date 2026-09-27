package io.kellermann.tarpeisto.config;

import java.security.SecureRandom;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the single {@link SecureRandom} bean that public-asset-code generation depends on
 * (specification section 9.3: "Codes are generated using a cryptographically secure random
 * source"), via constructor injection rather than each caller constructing its own, mirroring how
 * {@link ClockConfiguration} centralizes the {@code Clock} bean. {@link SecureRandom} is
 * thread-safe, so one shared instance is sufficient.
 */
@Configuration(proxyBeanMethods = false)
public class SecureRandomConfiguration {

    @Bean
    public SecureRandom secureRandom() {
        return new SecureRandom();
    }
}
