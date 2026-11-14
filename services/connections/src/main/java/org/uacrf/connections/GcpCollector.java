package org.uacrf.connections;

/** IMPLEMENT HERE: the real Google Cloud collector. Constants are placeholders to replace. */
final class GcpCollector implements ProviderCollector {
  /** IMPLEMENT: project and a read-only service account (viewer roles only). */
  static final String PROJECT_ID = "<GCP_PROJECT_ID>";

  static final String SERVICE_ACCOUNT = "<READ_ONLY_SERVICE_ACCOUNT>@<GCP_PROJECT_ID>.iam.gserviceaccount.com";
  /** IMPLEMENT: Cloud Monitoring timeSeries.list. Kind: gcp.monitoring.timeseries. */
  static final String METRICS_API = "monitoring.googleapis.com/v3/timeSeries";
  /** IMPLEMENT: Cloud Asset Inventory assets.list, paged with nextPageToken. Kind: gcp.assetinventory.assets. */
  static final String INVENTORY_API = "cloudasset.googleapis.com/v1/assets";

  @Override
  public ConnectionsRequests.Provider provider() {
    return ConnectionsRequests.Provider.GCP;
  }

  @Override
  public Batch collect(Request request) {
    // IMPLEMENT: impersonate SERVICE_ACCOUNT, call METRICS_API / INVENTORY_API, map
    // RESOURCE_EXHAUSTED -> RATE_LIMITED, PERMISSION_DENIED -> PERMISSION_DENIED,
    // UNAVAILABLE -> SOURCE_UNAVAILABLE, nextPageToken -> nextCheckpoint.
    return Batch.notImplemented("GCP collector is not implemented");
  }
}
