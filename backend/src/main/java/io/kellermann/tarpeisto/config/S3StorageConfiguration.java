package io.kellermann.tarpeisto.config;

import io.kellermann.tarpeisto.storage.MediaStorage;
import io.kellermann.tarpeisto.storage.S3MediaStorage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;

/** S3 SDK wiring. Disabled storage deliberately has no filesystem fallback. */
@Configuration(proxyBeanMethods = false)
public class S3StorageConfiguration {
    @Bean
    MediaStorage mediaStorage(S3Properties properties) {
        if (!properties.enabled()) return new DisabledMediaStorage();
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(properties.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
                .serviceConfiguration(s3Configuration(properties));
        if (properties.endpoint() != null) builder.endpointOverride(properties.endpoint());
        return new S3MediaStorage(builder.build(), properties.bucket(), properties.serverSideEncryption());
    }

    static S3Configuration s3Configuration(S3Properties properties) {
        return S3Configuration.builder()
                .pathStyleAccessEnabled(properties.pathStyleAccess())
                .chunkedEncodingEnabled(properties.chunkedEncoding())
                .build();
    }

    private static final class DisabledMediaStorage implements MediaStorage {
        @Override
        public void put(String objectKey, String contentType, long contentLength, java.io.InputStream inputStream) {
            throw new IllegalStateException(
                    "Media storage is disabled. Configure TARPEISTO_S3_ENABLED and S3 credentials.");
        }

        @Override
        public StoredMedia get(String objectKey) {
            throw new IllegalStateException("Media storage is disabled.");
        }

        @Override
        public void delete(String objectKey) {
            throw new IllegalStateException("Media storage is disabled.");
        }
    }
}
