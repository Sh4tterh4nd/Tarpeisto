package io.kellermann.tarpeisto;

import io.kellermann.tarpeisto.config.ApplicationProperties;
import io.kellermann.tarpeisto.config.AssetCodeRateLimitProperties;
import io.kellermann.tarpeisto.config.AuthenticationProperties;
import io.kellermann.tarpeisto.config.LoginRateLimitProperties;
import io.kellermann.tarpeisto.config.S3Properties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** Application entry point. See {@code docs/adr/} for the accepted architecture decisions. */
@SpringBootApplication
@EnableConfigurationProperties({
    ApplicationProperties.class,
    LoginRateLimitProperties.class,
    AssetCodeRateLimitProperties.class,
    AuthenticationProperties.class,
    S3Properties.class
})
public class TarpeistoApplication {

    public static void main(String[] args) {
        SpringApplication.run(TarpeistoApplication.class, args);
    }
}
