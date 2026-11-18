package org.uacrf.connections;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.uacrf.connections.ProviderCollector.Batch;
import org.uacrf.connections.ProviderCollector.Observation;
import org.uacrf.connections.ProviderCollector.Request;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.config.ConfigClient;
import software.amazon.awssdk.services.config.model.SelectResourceConfigRequest;
import software.amazon.awssdk.services.config.model.SelectResourceConfigResponse;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
final class AwsCollector implements ProviderCollector {
  private static final Pattern SOURCE_REF =
      Pattern.compile("^aws/([a-z]{2}(?:-[a-z0-9]+)+-[0-9]+)/config/inventory$");
  private static final String CONFIG_QUERY =
      "SELECT resourceId, resourceType, awsRegion, configurationItemCaptureTime";
  private static final int PAGE_SIZE = 100;
  private static final String KIND = "aws.config.resources";

  private final InventorySessionFactory sessions;
  private final ObjectMapper objectMapper;

  @Autowired
  AwsCollector(ObjectMapper objectMapper) {
    this(AwsCollector::openSession, objectMapper);
  }

  AwsCollector(InventorySessionFactory sessions, ObjectMapper objectMapper) {
    this.sessions = sessions;
    this.objectMapper = objectMapper;
  }

  @Override
  public ConnectionsRequests.Provider provider() {
    return ConnectionsRequests.Provider.AWS;
  }

  @Override
  public Batch collect(Request request) {
    Matcher source = SOURCE_REF.matcher(request.sourceRef());
    if (!source.matches()) {
      throw new IllegalArgumentException(
          "AWS inventory source_ref must use aws/<region>/config/inventory.");
    }
    if (request.readOnlyIdentityRef() == null
        || request.readOnlyExternalId() == null
        || request.readOnlyExternalId().isBlank()) {
      throw new IllegalArgumentException("AWS role ARN and ExternalId are required.");
    }
    String region = source.group(1);
    try (InventorySession session =
        sessions.open(region, request.readOnlyIdentityRef(), request.readOnlyExternalId())) {
      SelectResourceConfigResponse response = session.select(CONFIG_QUERY, request.checkpoint());
      List<Observation> observations = new ArrayList<>();
      long sequence = request.lastSequence();
      for (String result : response.results()) {
        JsonNode item = objectMapper.readTree(result);
        JsonNode capturedAt = item.get("configurationItemCaptureTime");
        if (capturedAt == null || !capturedAt.isString()) {
          throw new IllegalStateException(
              "AWS Config inventory result is missing configurationItemCaptureTime.");
        }
        String payload = objectMapper.writeValueAsString(item);
        sequence = Math.addExact(sequence, 1);
        observations.add(
            new Observation(
                observationId(request.sourceRef(), sequence),
                sequence,
                Instant.parse(capturedAt.stringValue()),
                KIND,
                payload));
      }
      return new Batch(
          Batch.Status.COLLECTED, observations, response.nextToken(), null);
    } catch (AwsServiceException error) {
      int statusCode = error.statusCode();
      if (error.isThrottlingException() || statusCode == 429) {
        return new Batch(Batch.Status.RATE_LIMITED, List.of(), request.checkpoint(), null);
      }
      String errorCode =
          error.awsErrorDetails() == null ? "" : error.awsErrorDetails().errorCode();
      if (errorCode != null && errorCode.toLowerCase(java.util.Locale.ROOT).contains("accessdenied")) {
        return new Batch(Batch.Status.PERMISSION_DENIED, List.of(), request.checkpoint(), null);
      }
      if (statusCode >= 500) {
        return new Batch(Batch.Status.SOURCE_UNAVAILABLE, List.of(), request.checkpoint(), null);
      }
      throw error;
    } catch (SdkClientException error) {
      return new Batch(Batch.Status.SOURCE_UNAVAILABLE, List.of(), request.checkpoint(), null);
    } catch (JacksonException error) {
      throw new IllegalStateException("AWS Config returned invalid inventory JSON.", error);
    }
  }

  private static InventorySession openSession(String regionName, String roleArn, String externalId) {
    Region region = Region.of(regionName);
    StsClient sts = StsClient.builder().region(region).build();
    StsAssumeRoleCredentialsProvider credentials = null;
    ConfigClient config = null;
    try {
      credentials =
          StsAssumeRoleCredentialsProvider.builder()
              .stsClient(sts)
              .refreshRequest(
                  AssumeRoleRequest.builder()
                      .roleArn(roleArn)
                      .roleSessionName("uacrf-s03-collection")
                      .externalId(externalId)
                      .durationSeconds(900)
                      .build())
              .build();
      config =
          ConfigClient.builder()
              .region(region)
              .credentialsProvider(credentials)
              .build();
      StsAssumeRoleCredentialsProvider assumedCredentials = credentials;
      ConfigClient configClient = config;
      return new InventorySession() {
        @Override
        public SelectResourceConfigResponse select(String expression, String nextToken) {
          SelectResourceConfigRequest.Builder request =
              SelectResourceConfigRequest.builder().expression(expression).limit(PAGE_SIZE);
          if (nextToken != null) {
            request.nextToken(nextToken);
          }
          return configClient.selectResourceConfig(request.build());
        }

        @Override
        public void close() {
          configClient.close();
          assumedCredentials.close();
          sts.close();
        }
      };
    } catch (RuntimeException error) {
      if (config != null) {
        config.close();
      }
      if (credentials != null) {
        credentials.close();
      }
      sts.close();
      throw error;
    }
  }

  private static String observationId(String sourceRef, long sequence) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest((sourceRef + ":" + sequence).getBytes(StandardCharsets.UTF_8));
      return "aws-config-" + HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is required by the runtime.", error);
    }
  }

  interface InventorySessionFactory {
    InventorySession open(String region, String roleArn, String externalId);
  }

  interface InventorySession extends AutoCloseable {
    SelectResourceConfigResponse select(String expression, String nextToken);

    @Override
    void close();
  }
}
