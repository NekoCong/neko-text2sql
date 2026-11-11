package com.nekocong.text2sql.knowledge.store;

import java.time.Duration;

/**
 * Binary object storage for knowledge originals and derived parse artifacts.
 *
 * <p>The bucket is private: callers never receive a permanent URL, only a short-lived presigned GET
 * URL that the backend signs after it has checked the caller's knowledge scope.
 *
 * <p>Keys follow the layout {@code originals/...}, {@code derived/{documentId}/...} and
 * {@code snapshots/{documentId}/...}; the database stores keys only.
 */
public interface ObjectStore {

    /**
     * Stores (or replaces) an object.
     *
     * @param key the object key
     * @param bytes the payload
     * @param contentType the media type recorded on the object
     * @throws ObjectStoreException when the store is unavailable or rejects the write
     */
    void put(String key, byte[] bytes, String contentType);

    /**
     * Reads an object.
     *
     * @param key the object key
     * @return the payload
     * @throws ObjectStoreException when the object is missing or the store is unavailable
     */
    byte[] get(String key);

    /**
     * Reports whether an object exists.
     *
     * @param key the object key
     * @return {@code true} when the object is present
     * @throws ObjectStoreException when the store is unavailable
     */
    boolean exists(String key);

    /**
     * Deletes one object. Missing objects are ignored.
     *
     * @param key the object key
     * @throws ObjectStoreException when the store is unavailable
     */
    void delete(String key);

    /**
     * Deletes every object under a prefix (used for cascading deletes).
     *
     * @param prefix the key prefix, without a trailing wildcard
     * @throws ObjectStoreException when the store is unavailable
     */
    void deletePrefix(String prefix);

    /**
     * Signs a short-lived GET URL.
     *
     * @param key the object key
     * @param ttl the lifetime of the URL
     * @return the presigned URL
     * @throws ObjectStoreException when the object is missing or the store is unavailable
     */
    String presignGet(String key, Duration ttl);

    /**
     * Reports whether the backing store is reachable.
     *
     * @return {@code true} when the store can serve requests
     */
    boolean available();
}
