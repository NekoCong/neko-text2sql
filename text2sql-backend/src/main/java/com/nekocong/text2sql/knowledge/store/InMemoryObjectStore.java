package com.nekocong.text2sql.knowledge.store;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link ObjectStore} for unit tests and for running without MinIO.
 *
 * <p>Presigned URLs are opaque tokens that resolve back to the stored bytes, so a test can assert
 * that a preview URL serves exactly the object it was signed for.
 */
public class InMemoryObjectStore implements ObjectStore {

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private final Map<String, String> contentTypes = new ConcurrentHashMap<>();
    private final Map<String, String> presigned = new ConcurrentHashMap<>();

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        objects.put(key, Arrays.copyOf(bytes, bytes.length));
        contentTypes.put(key, contentType == null ? "application/octet-stream" : contentType);
    }

    @Override
    public byte[] get(String key) {
        byte[] bytes = objects.get(key);
        if (bytes == null) {
            throw new ObjectStoreException("对象不存在：" + key);
        }
        return Arrays.copyOf(bytes, bytes.length);
    }

    @Override
    public boolean exists(String key) {
        return objects.containsKey(key);
    }

    @Override
    public void delete(String key) {
        objects.remove(key);
        contentTypes.remove(key);
    }

    @Override
    public void deletePrefix(String prefix) {
        objects.keySet().removeIf(key -> key.startsWith(prefix));
        contentTypes.keySet().removeIf(key -> key.startsWith(prefix));
    }

    @Override
    public String presignGet(String key, java.time.Duration ttl) {
        if (!objects.containsKey(key)) {
            throw new ObjectStoreException("对象不存在：" + key);
        }
        String token = "memory://" + java.util.UUID.randomUUID() + "/" + key;
        presigned.put(token, key);
        return token;
    }

    @Override
    public boolean available() {
        return true;
    }

    /**
     * Resolves a URL previously returned by {@link #presignGet(String, java.time.Duration)}.
     *
     * @param url the presigned URL
     * @return the object bytes
     * @throws ObjectStoreException when the token is unknown
     */
    public byte[] readPresigned(String url) {
        String key = presigned.get(url);
        if (key == null) {
            throw new ObjectStoreException("预览地址无效或已过期");
        }
        return get(key);
    }

    /**
     * Returns the content type recorded for a key.
     *
     * @param key the object key
     * @return the media type, or {@code null} when absent
     */
    public String contentType(String key) {
        return contentTypes.get(key);
    }

    /**
     * Returns the number of stored objects.
     *
     * @return the object count
     */
    public int size() {
        return objects.size();
    }
}
