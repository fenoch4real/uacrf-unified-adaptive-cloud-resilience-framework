package org.uacrf.connections;

/** IMPLEMENT HERE: the real Azure collector. Constants are placeholders to replace. */
final class AzureCollector implements ProviderCollector {
  /** IMPLEMENT: tenant and subscription of a Reader-role service principal or workload identity. */
  static final String TENANT_ID = "<AZURE_TENANT_ID>";

  static final String SUBSCRIPTION_ID = "<AZURE_SUBSCRIPTION_ID>";
  /** IMPLEMENT: Azure Monitor metrics query. Kind: azure.monitor.metrics. */
  static final String METRICS_API = "Microsoft.Insights/metrics";
  /** IMPLEMENT: Resource Graph query, paged with $skipToken. Kind: azure.resourcegraph.rows. */
  static final String INVENTORY_API = "Microsoft.ResourceGraph/resources";

  @Override
  public ConnectionsRequests.Provider provider() {
    return ConnectionsRequests.Provider.AZURE;
  }

  @Override
  public Batch collect(Request request) {
    // IMPLEMENT: authenticate with TENANT_ID, query METRICS_API / INVENTORY_API, map
    // HTTP 429 -> RATE_LIMITED (honour Retry-After), 403 -> PERMISSION_DENIED, 5xx ->
    // SOURCE_UNAVAILABLE, $skipToken -> nextCheckpoint.
    return Batch.notImplemented("Azure collector is not implemented");
  }
}
