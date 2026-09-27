package io.kellermann.bigcontainers.storage;

import java.io.InputStream;
import java.util.Objects;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** AWS S3 SDK implementation shared by AWS S3 and compatible providers. */
public class S3MediaStorage implements MediaStorage {
    private final S3Client s3Client;
    private final String bucket;
    private final String serverSideEncryption;

    public S3MediaStorage(S3Client s3Client, String bucket, String serverSideEncryption) {
        this.s3Client = Objects.requireNonNull(s3Client);
        this.bucket = Objects.requireNonNull(bucket);
        this.serverSideEncryption = serverSideEncryption;
    }

    @Override
    public void put(String objectKey, String contentType, long contentLength, InputStream inputStream) {
        PutObjectRequest.Builder request =
                PutObjectRequest.builder().bucket(bucket).key(objectKey).contentType(contentType);
        if (serverSideEncryption != null && !serverSideEncryption.isBlank()) {
            request.serverSideEncryption(serverSideEncryption);
        }
        s3Client.putObject(request.build(), RequestBody.fromInputStream(inputStream, contentLength));
    }

    @Override
    public StoredMedia get(String objectKey) {
        ResponseInputStream<GetObjectResponse> response = s3Client.getObject(
                GetObjectRequest.builder().bucket(bucket).key(objectKey).build());
        long length = response.response().contentLength() == null
                ? -1
                : response.response().contentLength();
        return new StoredMedia(response, response.response().contentType(), length);
    }

    @Override
    public void delete(String objectKey) {
        s3Client.deleteObject(request -> request.bucket(bucket).key(objectKey));
    }
}
