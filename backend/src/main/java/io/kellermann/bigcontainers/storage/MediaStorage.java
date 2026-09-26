package io.kellermann.bigcontainers.storage;

import java.io.InputStream;

/** The sole production object-storage contract; implemented only through the S3 API. */
public interface MediaStorage {
    void put(String objectKey, String contentType, long contentLength, InputStream inputStream);

    StoredMedia get(String objectKey);

    void delete(String objectKey);

    record StoredMedia(InputStream inputStream, String contentType, long contentLength) {}
}
