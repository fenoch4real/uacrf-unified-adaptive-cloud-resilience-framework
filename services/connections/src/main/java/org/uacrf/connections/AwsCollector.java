package org.uacrf.connections;

/**
 * IMPLEMENT HERE: the real AWS collector. Constants below are placeholders to replace; the
 * sample-data multi-cloud fixture shows the expected payload shapes and paging tokens.
 */
final class AwsCollector implements ProviderCollector {
  /** IMPLEMENT: read-only role ARN assumed via STS (no write permissions). */
  static final String ROLE_ARN = "arn:aws:iam::<ACCOUNT_ID>:role/<READ_ONLY_ROLE>";
  /** IMPLEMENT: CloudWatch GetMetricData for service health. Kind: aws.cloudwatch.metrics. */
  static final String METRICS_API = "cloudwatch:GetMetricData";
  /** IMPLEMENT: AWS Config SelectResourceConfig, paged with NextToken. Kind: aws.config.resources. */
  static final String INVENTORY_API = "config:SelectResourceConfig";
  /** IMPLEMENT: region list to collect. */
  static final String REGION = "<AWS_REGION>";

  @Override
  public ConnectionsRequests.Provider provider() {
    return ConnectionsRequests.Provider.AWS;
  }

  @Override
  public Batch collect(Request request) {
    // IMPLEMENT: assume ROLE_ARN, call METRICS_API / INVENTORY_API for the window, map
    // Throttling -> RATE_LIMITED, AccessDenied -> PERMISSION_DENIED, 5xx -> SOURCE_UNAVAILABLE,
    // NextToken -> nextCheckpoint, and number observations per source with a stable sequence.
    return Batch.notImplemented("AWS collector is not implemented");
  }
}
