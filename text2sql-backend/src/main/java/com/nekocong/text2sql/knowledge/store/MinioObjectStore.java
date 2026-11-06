package com.nekocong.text2sql.knowledge.store;

import com.nekocong.text2sql.config.KnowledgeProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import io.minio.messages.Item;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MinIO backed {@link ObjectStore}.
 *
 * <p>The bucket is created on startup and always stays private; callers only ever receive
 * short-lived presigned GET URLs.
 */
public class MinioObjectStore implements ObjectStore {

    private static final Logger log = LoggerFactory.getLogger(MinioObjectStore.class);

    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    /** MinIO ignores the region, but pinning it keeps presigning a purely local operation. */
    private static final String DEFAULT_REGION = "us-east-1";

    private final MinioClient client;

    /** Signs download URLs with the public endpoint when one is configured. */
    private final MinioClient presignClient;

    private final String bucket;

    private final int defaultPresignTtlSeconds;

    /**
     * Creates the store. Call {@link #initialize()} once before serving requests.
     *
     * @param properties the MinIO connection properties
     */
    public MinioObjectStore(KnowledgeProperties.Minio properties) {
        String endpoint = properties.getEndpoint();
        String accessKey = properties.getAccessKey();
        String secretKey = properties.getSecretKey();
        String configuredBucket = properties.getBucket();
        if (endpoint == null || endpoint.isBlank()) {
            throw new ObjectStoreException("对象存储 endpoint 未配置");
        }
        if (configuredBucket == null || configuredBucket.isBlank()) {
            throw new ObjectStoreException("对象存储 bucket 未配置");
        }
        MinioClient.Builder builder = MinioClient.builder().endpoint(endpoint).region(DEFAULT_REGION);
        if (accessKey != null && !accessKey.isBlank()) {
            if (secretKey == null || secretKey.isBlank()) {
                throw new ObjectStoreException("对象存储 secretKey 未配置");
            }
            builder.credentials(accessKey, secretKey);
        }
        this.client = builder.build();
        String publicEndpoint = properties.getPublicEndpoint();
        if (publicEndpoint == null || publicEndpoint.isBlank() || publicEndpoint.equals(endpoint)) {
            this.presignClient = client;
        } else {
            // The region must be pinned: without it the SDK may query the endpoint to discover the
            // bucket region, and the public address is not necessarily reachable from the container
            // (hairpin NAT). Presigning itself is pure computation.
            MinioClient.Builder presignBuilder =
                    MinioClient.builder().endpoint(publicEndpoint).region(DEFAULT_REGION);
            if (accessKey != null && !accessKey.isBlank()) {
                presignBuilder.credentials(accessKey, secretKey);
            }
            this.presignClient = presignBuilder.build();
        }
        this.bucket = configuredBucket;
        this.defaultPresignTtlSeconds = properties.getPresignTtlSeconds();
    }

    /**
     * Creates the bucket when it does not exist yet. Idempotent, never applies a public policy.
     *
     * @throws ObjectStoreException when the bucket cannot be created
     */
    public void initialize() {
        try {
            boolean present = client.bucketExists(
                    BucketExistsArgs.builder().bucket(bucket).build());
            if (!present) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("知识库对象存储桶已创建：{}", bucket);
            } else {
                log.info("知识库对象存储桶已就绪：{}", bucket);
            }
        } catch (Exception e) {
            throw new ObjectStoreException("初始化对象存储桶失败：" + bucket, e);
        }
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        try {
            putOnce(key, bytes, contentType);
        } catch (Exception e) {
            // MinIO may still be starting when the application is already up: create the bucket
            // lazily instead of failing the upload with NoSuchBucket.
            if (isNoSuchBucket(e)) {
                initialize();
                try {
                    putOnce(key, bytes, contentType);
                } catch (Exception retryFailure) {
                    throw new ObjectStoreException("写入对象失败：" + key, retryFailure);
                }
                return;
            }
            throw new ObjectStoreException("写入对象失败：" + key, e);
        }
    }

    private void putOnce(String key, byte[] bytes, String contentType) throws Exception {
        String mediaType =
                contentType == null || contentType.isBlank() ? DEFAULT_CONTENT_TYPE : contentType;
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(input, bytes.length, -1)
                    .contentType(mediaType)
                    .build());
        }
    }

    @Override
    public byte[] get(String key) {
        try (GetObjectResponse response = client.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(key)
                .build())) {
            return response.readAllBytes();
        } catch (ErrorResponseException e) {
            if (isNoSuchKey(e)) {
                throw new ObjectStoreException("对象不存在：" + key, e);
            }
            throw new ObjectStoreException("读取对象失败：" + key, e);
        } catch (Exception e) {
            throw new ObjectStoreException("读取对象失败：" + key, e);
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
            return true;
        } catch (ErrorResponseException e) {
            if (isNoSuchKey(e)) {
                return false;
            }
            throw new ObjectStoreException("对象存储不可用：" + readable(e), e);
        } catch (Exception e) {
            throw new ObjectStoreException("对象存储不可用：" + readable(e), e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (ErrorResponseException e) {
            if (isNoSuchKey(e)) {
                return;
            }
            throw new ObjectStoreException("删除对象失败：" + key, e);
        } catch (Exception e) {
            throw new ObjectStoreException("删除对象失败：" + key, e);
        }
    }

    @Override
    public void deletePrefix(String prefix) {
        List<String> keys = new ArrayList<>();
        try {
            for (Result<Item> result : client.listObjects(ListObjectsArgs.builder()
                    .bucket(bucket)
                    .prefix(prefix)
                    .recursive(true)
                    .build())) {
                keys.add(result.get().objectName());
            }
        } catch (Exception e) {
            throw new ObjectStoreException("列出待删除对象失败：" + prefix, e);
        }
        for (String key : keys) {
            delete(key);
        }
    }

    @Override
    public String presignGet(String key, Duration ttl) {
        if (!exists(key)) {
            throw new ObjectStoreException("对象不存在：" + key);
        }
        int seconds = ttl == null
                ? defaultPresignTtlSeconds
                : (int) Math.max(1, ttl.toSeconds());
        try {
            return presignClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(key)
                    .expiry(seconds, TimeUnit.SECONDS)
                    .build());
        } catch (Exception e) {
            throw new ObjectStoreException("生成预览地址失败：" + key + "（" + readable(e) + "）", e);
        }
    }

    @Override
    public boolean available() {
        try {
            return client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isNoSuchKey(ErrorResponseException e) {
        return e.errorResponse() != null && "NoSuchKey".equals(e.errorResponse().code());
    }

    private static boolean isNoSuchBucket(Exception e) {
        return e instanceof ErrorResponseException error
                && error.errorResponse() != null
                && "NoSuchBucket".equals(error.errorResponse().code());
    }

    private static String readable(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
