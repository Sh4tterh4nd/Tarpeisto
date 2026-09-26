package io.kellermann.bigcontainers;

import io.kellermann.bigcontainers.config.ApplicationProperties;
import io.kellermann.bigcontainers.config.AuthenticationProperties;
import io.kellermann.bigcontainers.config.LoginRateLimitProperties;
import io.kellermann.bigcontainers.config.SeedProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** Application entry point. See {@code docs/adr/} for the accepted architecture decisions. */
@SpringBootApplication
@EnableConfigurationProperties({
    ApplicationProperties.class,
    SeedProperties.class,
    LoginRateLimitProperties.class,
    AuthenticationProperties.class
})
public class BigContainersApplication {

    public static void main(String[] args) {
        SpringApplication.run(BigContainersApplication.class, args);
    }
}
