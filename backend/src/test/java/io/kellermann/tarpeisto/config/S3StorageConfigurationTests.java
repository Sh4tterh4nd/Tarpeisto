package io.kellermann.tarpeisto.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import software.amazon.awssdk.services.s3.S3Configuration;

/** S3 deployment settings bind to the AWS SDK configuration used by the storage client. */
class S3StorageConfigurationTests {

    @Test
    void applicationDefaultEnablesChunkedEncoding() throws Exception {
        S3Properties properties = propertiesFromApplicationYaml(Map.of());

        assertThat(properties.chunkedEncoding()).isTrue();
    }

    @Test
    void anExplicitFalseValueDisablesChunkedEncoding() throws Exception {
        S3Properties properties = propertiesFromApplicationYaml(Map.of("TARPEISTO_S3_CHUNKED_ENCODING", "false"));

        assertThat(properties.chunkedEncoding()).isFalse();
    }

    @Test
    void sdkConfigurationPreservesPathStyleAndConfiguresChunkedEncoding() {
        S3Properties properties = properties(true, false);

        S3Configuration configuration = S3StorageConfiguration.s3Configuration(properties);

        assertThat(configuration.pathStyleAccessEnabled()).isTrue();
        assertThat(configuration.chunkedEncodingEnabled()).isFalse();
    }

    private static S3Properties propertiesFromApplicationYaml(Map<String, Object> overrides) throws Exception {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("overrides", overrides));
        environment
                .getPropertySources()
                .addLast(new YamlPropertySourceLoader()
                        .load("application.yml", new ClassPathResource("application.yml"))
                        .getFirst());
        return Binder.get(environment)
                .bind("tarpeisto.s3", Bindable.of(S3Properties.class))
                .orElseThrow(() -> new IllegalStateException("S3 properties must bind"));
    }

    private static S3Properties properties(boolean pathStyleAccess, boolean chunkedEncoding) {
        return new S3Properties(
                true,
                URI.create("https://object-storage.example.org"),
                "test-region",
                "test-bucket",
                "test-access-key",
                "test-secret-key",
                pathStyleAccess,
                chunkedEncoding,
                null,
                1);
    }
}
