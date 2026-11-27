package io.uacrf.evidence.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * Enterprise WORM Object Storage adapter backing MinIO / AWS S3 Object Lock / GCS / Azure / OCI Retention.
 */
@Component
public class ObjectLockWormClient {

    private final boolean enabled;
    private final String endpoint;
    private final String bucket;
    private final String accessKey;
    private final String secretKey;
    private final boolean objectLockEnabled;
    private volatile S3Client s3Client;

    public ObjectLockWormClient(
            @Value("${uacrf.evidence.s3.enabled:false}") boolean enabled,
            @Value("${uacrf.evidence.minio-endpoint:http://localhost:9000}") String endpoint,
            @Value("${uacrf.evidence.minio-bucket:uacrf-evidence}") String bucket,
            @Value("${uacrf.evidence.s3.access-key:uacrf_minio}") String accessKey,
            @Value("${uacrf.evidence.s3.secret-key:uacrf_minio_dev_secret}") String secretKey,
            @Value("${uacrf.evidence.s3.object-lock-enabled:true}") boolean objectLockEnabled) {
        this.enabled = enabled;
        this.endpoint = endpoint;
        this.bucket = bucket;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.objectLockEnabled = objectLockEnabled;
    }

    private S3Client client() {
        if (s3Client == null) {
            synchronized (this) {
                if (s3Client == null) {
                    s3Client = S3Client.builder()
                            .endpointOverride(URI.create(endpoint))
                            .region(Region.US_EAST_1)
                            .credentialsProvider(StaticCredentialsProvider.create(
                                    AwsBasicCredentials.create(accessKey, secretKey)))
                            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                            .build();
                }
            }
        }
        return s3Client;
    }

    public boolean storeWormObject(String key, String payloadJson, String sha256Digest) {
        if (!enabled) {
            return false;
        }
        S3Client client = client();
        ensureBucketExists(client);
        PutObjectRequest.Builder req = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType("application/json")
                .metadata(Map.of("x-uacrf-sha256", sha256Digest));
        if (objectLockEnabled) {
            req.objectLockMode(ObjectLockMode.GOVERNANCE)
                    .objectLockRetainUntilDate(Instant.now().plus(365, ChronoUnit.DAYS));
        }
        client.putObject(req.build(), RequestBody.fromString(payloadJson, StandardCharsets.UTF_8));
        return true;
    }

    private void ensureBucketExists(S3Client client) {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException ex) {
            client.createBucket(CreateBucketRequest.builder()
                    .bucket(bucket)
                    .objectLockEnabledForBucket(objectLockEnabled)
                    .build());
        }
    }
}
