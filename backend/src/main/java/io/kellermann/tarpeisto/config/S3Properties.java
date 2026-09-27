package io.kellermann.tarpeisto.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Validated S3-compatible object-store configuration (ADR-0001). */
@ConfigurationProperties(prefix = "tarpeisto.s3")
@Validated
public record S3Properties(
        boolean enabled,
        URI endpoint,
        String region,
        String bucket,
        String accessKey,
        String secretKey,
        boolean pathStyleAccess,
        boolean chunkedEncoding,
        String serverSideEncryption,
        @Min(1) long maxUploadBytes) {

    @AssertTrue(message = "Enabled S3 storage requires region, bucket, access key, and secret key") public boolean isCompleteWhenEnabled() {
        return !enabled || (notBlank(region) && notBlank(bucket) && notBlank(accessKey) && notBlank(secretKey));
    }

    @AssertTrue(message = "tarpeisto.s3.endpoint must be an absolute URL when set") public boolean isEndpointAbsoluteOrAbsent() {
        return endpoint == null || endpoint.isAbsolute();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
