# Connections ingestion

Observation IDs are unique per tenant, environment and connection. Intake serializes requests for
the same ID across source bindings, and accepts a replay as a duplicate only when all retained
fields, including the original nanosecond-precision observation time, match.

Observations accepted before migration V3 have only PostgreSQL's microsecond-precision timestamp.
Their original precision cannot be recovered: any subsequent replay of one of those IDs is
quarantined as `REPLAY_CONFLICT`, even if it might have been identical. Their read time remains
the retained, rounded timestamp. New observations retain and return their exact original instant.
Quarantine is not qualification; S04 remains the evidence authority.

## Read-only inventory projection

`GET /v1/connections/{id}/inventory-observations` returns a paginated, on-read
projection of retained AWS Config, Azure Resource Graph, and GCP Asset Inventory
observations. The internal EDGE01 route is
`/internal/connections/v1/connections/{id}/inventory-observations`. Both use
the existing `connections.connection.read` capability and tenant/environment
scope. `pageSize` is 1-100 (default 25); `cursor` and `next_cursor` work as
on the existing observations list. Non-inventory and quarantined records are
not returned.

Each item has `normalizer_version`, `source_class`, `provider`, `native_id`,
`native_type`, nullable `location`, `raw_observation_id`, `source_ref`,
`raw_kind`, `raw_payload_digest`, and `observed_at`. AWS and Azure locations
come from their payloads when present; GCP location is unknown because the
current asset payload does not supply it. The source reference's GCP region
is not a resource location. Native types and IDs remain provider-specific;
they are not a cross-provider taxonomy or globally unique resource key. An
item is one observation, not a deduplicated current-state inventory.

The original payload remains available on the existing observations endpoint;
the projection stores no second copy. Payload digest mismatches, malformed
inventory payloads, and connection/provider mismatches fail the request instead
of producing fabricated values. `SAMPLE_LOCAL` remains visible as `source_class`;
the provider name describes the simulated payload, not a live account.
Projection does not qualify evidence, discover relationships, promote graph
nodes, or accept providers. Existing retention and qualification policies are unchanged.

## Collection outcome report

`GET /v1/connections/{id}/collection-statuses` returns one paginated row per
source binding through the existing `connections.connection.read` capability
and tenant/environment scope; the EDGE01 route is
`/internal/connections/v1/connections/{id}/collection-statuses`. `pageSize` is
1-100 (default 25), with the standard `cursor` and `next_cursor` pagination.
Each row reports `last_status`, `last_detail`, whether a collection lease is
currently active, the last successful `last_collected_at`, and
`next_collection_at`. It never exposes page tokens, checkpoints, lease tokens,
or pending observation payloads. A `COLLECTED` status means the collection
batch was submitted to S03 intake; individual accepted, duplicate, or
quarantined observation outcomes are not summarized here. The timestamps are
reported as recorded and do not imply that a source is fresh or stale; no
freshness threshold is configured by this report.

The browser application provides a read-only connections list and inventory
observation view from **Current access**. It uses a selected tenant/environment
and a short-lived S03 delegation for `connections.connection.read`; the token
stays server-side. Configure `UACRF_CONNECTIONS_BASE_URL` to the EDGE01
internal connections base URL (default `http://localhost:8080/internal/connections`;
use the EDGE01 service address in a container). The page shows source class,
native identifiers, location when known, and raw observation references.
The inventory page also renders the paginated collection outcome report for the
connection, using a separate cursor from inventory pagination. It displays the
last status and detail, active-lease state, last successful collection, and
next scheduled time; missing and never-run values are called out explicitly.
The page clarifies that timestamps do not establish freshness or staleness and
that `COLLECTED` only means the batch was submitted to intake.
It visibly labels `SAMPLE_LOCAL` as synthetic and does not qualify or promote it.
The [sample fixture](../../sample-data/README.md#import-synthetic-inventory)
is not loaded automatically. An explicit browser import uses S01 owner
selection and S03 write delegations to load only the synthetic inventory
subset. Failed or quarantined operations stop the import and are reported.

Deploy V3 before the updated service and avoid mixed old/new ingestion instances: older instances
neither retain exact times nor acquire the new ID lock. Rollback to older intake also loses these
replay and concurrency guarantees.

The opt-in collection runner claims active source bindings through persisted checkpoints with
expiring leases, invokes a registered provider collector, and sends collected observations through
the same intake. It uses a dedicated OAuth client-credentials identity and a short-lived S01
delegation limited to `connections.observation.write`; it stores no signing key. The runner is
disabled by default. When enabled, it requires nonblank client ID and secret values and absolute
HTTP(S) token, delegation, and intake URLs; invalid configuration fails at startup. The secret
must be configured outside the repository, and this validation does not establish credential
validity. Its identity, delegation, and intake HTTP calls use bounded
2-second connect and 3-second response timeouts. Timeout failures do not advance checkpoints, and
any persisted batch is replayed unchanged on retry. Provider-reported retry times are honored,
intake failures do not advance checkpoints, and a partially accepted batch is persisted and
replayed unchanged on retry. Within a collection cycle, identity and delegation tokens are
reused only until five seconds before their reported expiry, measured from the start of the token
request using monotonic time; a response without `expires_in` is not cached, and an invalid
lifetime fails the attempt without advancing the checkpoint. The runner renews each database lease before provider collection and
before its token, delegation, and intake calls; renewal fails closed if another runner has replaced
the lease token. Failure to record one lost lease is logged without stopping unrelated claims in
the same collection cycle. It claims one source at a time, up to the configured cycle limit, so
queued sources do not hold expiring leases while earlier collections run.

The optional S03 outbox publisher delivers retained events to the S04 inbox using its own
client-credentials identity and narrow S01 delegation. When enabled, it requires nonblank client
identity values, absolute HTTP(S) token/delegation/inbox URLs, and a positive batch size; invalid
configuration fails at startup. Its HTTP calls share the runner's 2-second connect and 3-second
response timeouts. Errors and timeouts leave outbox rows pending for retry; S04's inbox
idempotency handles a retry when delivery succeeded but its response was lost. S03 requires the
response `event_id` to match the sent event before accepting `RECEIVED`, `DUPLICATE`, or
`QUARANTINED`; a missing or mismatched ID leaves the row pending.
Each published event includes the outbox row's `aggregate_type`. S04 scopes
aggregate sequencing by source service, aggregate type, and aggregate ID, so
connection, source-binding, and observation IDs cannot block one another when
their values coincide.
The `m2-live` process test also starts S03 and S04 with separate PostgreSQL databases and verifies
that a connection created through S03's HTTP API reaches S04's inbox through real local S01
admission, grant, membership, and delegation APIs. The directory, client-credentials endpoint,
and OIDC signing/JWKS are test fixtures; this does not establish production identity conformance.

AWS connections use an IAM role ARN and receive a unique `uacrf-<UUID>` ExternalId, exposed by
the authorized connection read endpoint for the target role's trust policy. The first AWS source
is `aws/<region>/config/inventory`; it uses STS `AssumeRole` and AWS Config
`SelectResourceConfig`, processing at most one 100-item page per collection and retaining
`NextToken` as the checkpoint. STS, Config, and CloudWatch SDK calls have a 10-second per-attempt
timeout and a 30-second total API-call timeout. This bounds provider failures but does not
establish live conformance. A bounded CloudWatch source is also available as
`aws/<region>/cloudwatch/sqs/<queue-name>`; it requests
`ApproximateAgeOfOldestMessage` (Maximum) and
`ApproximateNumberOfMessagesVisible` (Average) from namespace `AWS/SQS` in
five-minute periods. The requested interval is at most 24 hours, and metric
results are submitted through the same S03 intake as `aws.cloudwatch.metrics`
observations.

Other exact CloudWatch metrics can be selected with
`aws/<region>/cloudwatch/metric/<base64url-selector>`. The unpadded Base64URL
selector decodes to a compact JSON object with exactly four fields:
`namespace`, `metricName`, `statistic`, and `dimensions`. `dimensions` is an
object of CloudWatch dimension names and values; it may be empty and is limited
to 30 entries. The selector is limited to 337 UTF-8 bytes. Supported statistics
are `Average`, `Sum`, `Minimum`, `Maximum`, and `SampleCount`. For example, an
EC2 CPU metric selector is
`{"namespace":"AWS/EC2","metricName":"CPUUtilization","statistic":"Average","dimensions":{"InstanceId":"i-0123456789abcdef0"}}`.
That selector's source reference is
`aws/us-east-1/cloudwatch/metric/eyJuYW1lc3BhY2UiOiJBV1MvRUMyIiwibWV0cmljTmFtZSI6IkNQVVV0aWxpemF0aW9uIiwic3RhdGlzdGljIjoiQXZlcmFnZSIsImRpbWVuc2lvbnMiOnsiSW5zdGFuY2VJZCI6ImktMDEyMzQ1Njc4OWFiY2RlZjAifX0`.
Each source makes one exact metric query at five-minute periods; it does not
enumerate metrics. Collection windows default to five minutes and are capped at
24 hours, and returned points are filtered to the half-open request interval.
The selector and provider result labels, timestamps, and values are retained
in the `aws.cloudwatch.metrics` observation. Incomplete results, unexpected
continuation tokens, and payloads exceeding S03's 16,384-character limit fail
without checkpoint advancement. The metric and statistic must be valid for the
selected namespace and dimensions.

The target role needs `cloudwatch:GetMetricData` on `*` in addition to the AWS
Config inventory permission below
([CloudWatch IAM action reference](https://docs.aws.amazon.com/service-authorization/latest/reference/list_cloudwatch.html)).
AWS remains `UNACCEPTED` until live read-only conformance is complete.
Synthetic sample fixtures are never scheduled as provider data.

For target-account setup, first register the AWS connection and read its generated ExternalId.
This follows AWS's
[confused-deputy guidance](https://docs.aws.amazon.com/IAM/latest/UserGuide/confused-deputy.html):
the service generates the per-customer value and customers place it in the role trust policy.
Use that value in the target role's trust policy with the collector runtime's AWS principal:

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": { "AWS": "<collector-runtime-role-arn>" },
    "Action": "sts:AssumeRole",
    "Condition": {
      "StringEquals": { "sts:ExternalId": "<aws_external_id-from-connection-read>" }
    }
  }]
}
```

Attach a read-only permissions policy to the target role allowing
`config:SelectResourceConfig` on `*` (the [AWS Config service authorization reference](https://docs.aws.amazon.com/service-authorization/latest/reference/list_config.html)
does not define a resource-level ARN for this action). For SQS or selector-based
metric sources, also allow `cloudwatch:GetMetricData` on `*`. The AWS account/region
must have AWS Config inventory available for inventory sources; the selected
queue must emit the requested CloudWatch metrics for metric sources. The runtime
principal also needs permission to assume the target role.
Replace the placeholders with deployment-specific values; do not use customer-controlled
ExternalIds or store long-lived AWS access keys in the repository.

The first Azure slice collects regional inventory from Azure Resource Graph. Register the
subscription GUID as `read_only_identity_ref`, then bind a source such as
`azure/westeurope/resourcegraph/inventory`. The service authenticates through Azure
`DefaultAzureCredential`; use a managed/workload identity or another supported credential source
configured outside the repository. That identity needs read access to the selected subscription
and `Microsoft.ResourceGraph/resources/read`. The query returns resource ID, name, type, location,
subscription ID and resource group only, in pages of at most 100 rows using `$skipToken`.
For a resource metric, bind
`azure/monitor/<base64url-resource-id>/<metric-name>`; encode the full Azure
resource ID as unpadded Base64URL. The resource ID's subscription must match
`read_only_identity_ref`. The collector uses the Azure Monitor Metrics
[`2018-01-01` REST API](https://learn.microsoft.com/en-us/rest/api/monitor/metrics/list?view=rest-monitor-2018-01-01)
with the default metric namespace, `Average` aggregation, five-minute periods
and a positive window of at most 24 hours (five minutes by default). It filters
metric timestamps to the half-open requested interval and submits observations
as `azure.monitor.metrics`. The runtime identity needs
read access to the selected resource; the built-in
[Monitoring Reader role](https://learn.microsoft.com/en-us/azure/role-based-access-control/built-in-roles/monitor)
provides read-only access to metrics and can be assigned at the narrowest
resource scope. This slice does not enumerate metric definitions or query
non-default metric namespaces; the selected metric must expose `Average`.
Azure connections remain `UNACCEPTED` until the live read-only conformance
suite passes; no live Azure account is used by local collector tests.

The first Google Cloud slice collects project-wide resource inventory from Cloud Asset Inventory.
Register the Google Cloud project ID as `read_only_identity_ref` and bind a source such as
`gcp/europe-west1/assetinventory/inventory`. The region segment identifies the source but does not
filter the project-wide asset results. The service uses Google Application Default Credentials
(ADC) with the `cloud-platform` scope; local ADC can be configured with
`gcloud auth application-default login`. The identity needs `cloudasset.assets.listResource`,
normally through `roles/cloudasset.viewer`, and `serviceusage.services.use`, normally through
`roles/serviceusage.serviceUsageConsumer`. Each request reads at most 100 assets and retains
`nextPageToken` as the checkpoint. Observation payloads contain only asset name, type, and update
time, not resource data.

For Cloud Monitoring, bind
`gcp/monitoring/<base64url-metric-type>/<resource-type>`. The metric type is encoded as unpadded
Base64URL; the resource type remains a readable segment. The project is taken from
`read_only_identity_ref`, and the source does not impose a region filter. The collector calls
[`projects.timeSeries.list`](https://cloud.google.com/monitoring/api/ref_v3/rest/v3/projects.timeSeries/list)
with one metric type and one monitored-resource type, `view=FULL`, and at most 100 points per page.
It uses the request interval or defaults to the last five minutes, with a 24-hour maximum, and
filters points to the half-open interval. Continuation checkpoints retain the original interval as
well as the provider page token, so later pages resume the same query. Native metric/resource
labels and point values are submitted as `gcp.monitoring.timeseries` observations. Responses with
`executionErrors`, unreachable regions, or payloads larger than the S03 limit fail without
advancing the checkpoint. The identity needs `monitoring.timeSeries.list`, normally through the
read-only [`roles/monitoring.viewer`](https://docs.cloud.google.com/monitoring/access-control)
role. Metric descriptor enumeration and aggregation/reduction are not implemented. GCP
connections remain `UNACCEPTED` until live read-only conformance passes; local tests use no live
Google Cloud account.

## Deferred live provider checks

Live provider checks are deferred; development can continue with the explicitly synthetic
[sample fixtures](../../sample-data/README.md). VS Code sign-in does not necessarily make
credentials available to the collector process. The initial local readiness check could not
authenticate AWS, obtain an Azure credential, or find Google Cloud local ADC; Azure CLI and
Google Cloud CLI were not on the execution PATH. No provider inventory was queried.

These are prerequisites for a future **bounded, read-only smoke check**, not a claim that
provider conformance or a complete live integration is ready. Collect only from an explicitly
selected account, subscription, or project. An existing resource is useful to demonstrate
inventory; an empty estate can test access but cannot demonstrate actual resource collection or
pagination. No new VMs, databases, or other resources are required merely to attempt the check.
Enabling services, recording inventory, API use, or creating resources may incur charges; do not
enable or create anything without the account owner's approval.

| Provider | Required target parameters | Collection scope |
| --- | --- | --- |
| AWS | Target role ARN, AWS region, collector identity/profile and UACRF-generated ExternalId | AWS Config inventory in the selected account and region |
| Azure | Subscription GUID and Azure region | Resource Graph rows in that subscription and region |
| GCP | Project ID and source region | Project-wide Cloud Asset Inventory plus a selected Monitoring metric/resource type; the source region does not filter either query |

Never put access keys, bearer tokens, credential files, or client secrets in chat or source
control. The identifiers above are not credentials; share them only as appropriate for the
environment. Credentials must be available to the process performing the check, not just a
separate VS Code extension.

### AWS setup

1. Arrange an AWS SDK-compatible local identity. For IAM Identity Center, follow
   [AWS CLI SSO setup](https://docs.aws.amazon.com/cli/latest/reference/configure/sso.html):
   `aws configure sso`, then `aws sso login --profile YOUR_PROFILE`. Confirm locally with
   `aws sts get-caller-identity --profile YOUR_PROFILE`. Select that profile in the collector
   process, for example with `AWS_PROFILE`; verify Java SDK support for the chosen profile
   rather than assuming a CLI login alone proves it works.
2. Choose a target account and region where AWS Config is enabled and recording the relevant
   resources. An account administrator must arrange a target IAM role with
   `config:SelectResourceConfig` and allow the collector identity `sts:AssumeRole` on that role.
3. Register the target role ARN as `read_only_identity_ref`, obtain the ExternalId through the
   authorized connection read, and have the administrator configure the role trust policy
   using the actual collector IAM principal and that generated value. The role ARN is needed
   before registration; the ExternalId is generated at registration. Do not leave an interim
   role broadly assumable. The trust-policy example above is a template, not a deployed policy.
4. Record the region and source format `aws/<region>/config/inventory`. AWS Config availability,
   permissions, cross-account trust, and profile access still need live verification.

### Azure setup

1. Install the [Azure CLI](https://learn.microsoft.com/en-us/cli/azure/install-azure-cli-macos)
   if unavailable, then run `az login` and `az account list --output table`. Choose the intended
   subscription and a region containing resources.
2. Register the subscription GUID as `read_only_identity_ref`. The collector identity needs
   subscription read access and `Microsoft.ResourceGraph/resources/read`; an administrator can
   verify whether a subscription-scoped Reader role provides the required access.
3. Record the source as `azure/<region>/resourcegraph/inventory`. Confirm that
   `DefaultAzureCredential` used by the collector can obtain the intended identity; VS Code
   sign-in alone did not establish this in the prior readiness check.

### Google Cloud setup

1. Install the [Google Cloud CLI](https://cloud.google.com/sdk/docs/install), if unavailable.
   Run `gcloud auth login` and `gcloud auth application-default login`; the latter configures
   local ADC for client libraries. Use `gcloud projects list` to identify the intended project.
2. Have an administrator enable the Cloud Asset Inventory API in the appropriate project, if
   it is not already enabled. The collector identity needs `cloudasset.assets.listResource`
   on the target project, commonly granted by `roles/cloudasset.viewer`. ADC's quota project
   also needs `serviceusage.services.use`, commonly granted by
   `roles/serviceusage.serviceUsageConsumer`. See the
   [Cloud Asset Inventory setup guide](https://docs.cloud.google.com/asset-inventory/docs/list-assets).
3. Register the project ID as `read_only_identity_ref` and record a source such as
   `gcp/europe-west1/assetinventory/inventory`. Unlike Azure and AWS, the source region does
   not restrict this collector's project-wide results.

### Returning to live validation

- Confirm authentication, the selected non-secret target identifiers, least-privilege access,
  enabled inventory services, and owner approval before any cloud requests. Treat returned
  resource metadata as potentially sensitive; avoid publishing it or logging it unnecessarily.
- A bounded successful request, including an empty response, is only a smoke check. Meaningful
  pagination requires enough resources or a controlled test arrangement. Permission-denied,
  throttling, outage, checkpoint recovery, and out-of-order scenarios require separate
  controlled conformance tests; do not alter permissions or disrupt production to force them.
- An opt-in live test harness and complete conformance evidence are still needed. The current
  collector tests use mocks. Cloud connections remain `UNACCEPTED` and cannot be bound or
  scheduled by the normal runner; do not bypass that gate to run a smoke check.
- CloudWatch, Azure Monitor, and Google Cloud Monitoring collection, richer inventory and
  relationship mappings, deployment identity configuration, and end-to-end acceptance remain
  separate work. The connection parameters alone cannot make the full application live-ready.
