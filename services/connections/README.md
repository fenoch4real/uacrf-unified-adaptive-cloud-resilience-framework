# Connections ingestion

Observation IDs are unique per tenant, environment and connection. Intake serializes requests for
the same ID across source bindings, and accepts a replay as a duplicate only when all retained
fields, including the original nanosecond-precision observation time, match.

Observations accepted before migration V3 have only PostgreSQL's microsecond-precision timestamp.
Their original precision cannot be recovered: any subsequent replay of one of those IDs is
quarantined as `REPLAY_CONFLICT`, even if it might have been identical. Their read time remains
the retained, rounded timestamp. New observations retain and return their exact original instant.
Quarantine is not qualification; S04 remains the evidence authority.

Deploy V3 before the updated service and avoid mixed old/new ingestion instances: older instances
neither retain exact times nor acquire the new ID lock. Rollback to older intake also loses these
replay and concurrency guarantees.

The opt-in collection runner claims active source bindings through persisted checkpoints with
expiring leases, invokes a registered provider collector, and sends collected observations through
the same intake. It uses a dedicated OAuth client-credentials identity and a short-lived S01
delegation limited to `connections.observation.write`; it stores no signing key. The runner is
disabled by default and requires its client secret to be configured outside the repository.
Provider-reported retry times are honored, intake failures do not advance checkpoints, and a
partially accepted batch is persisted and replayed unchanged on retry.

AWS connections use an IAM role ARN and receive a unique `uacrf-<UUID>` ExternalId, exposed by
the authorized connection read endpoint for the target role's trust policy. The first AWS source
is `aws/<region>/config/inventory`; it uses STS `AssumeRole` and AWS Config
`SelectResourceConfig`, processing at most one 100-item page per collection and retaining
`NextToken` as the checkpoint. The collector uses the AWS SDK default credential chain only for
the service-side STS call; no AWS credentials are stored in the repository. This slice does not
collect CloudWatch metrics. AWS remains `UNACCEPTED` until live read-only conformance is complete.
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
does not define a resource-level ARN for this action). The AWS account/region must have AWS
Config inventory available. The runtime principal also needs permission to assume the target role.
Replace the placeholders with deployment-specific values; do not use customer-controlled
ExternalIds or store long-lived AWS access keys in the repository.

The first Azure slice collects regional inventory from Azure Resource Graph. Register the
subscription GUID as `read_only_identity_ref`, then bind a source such as
`azure/westeurope/resourcegraph/inventory`. The service authenticates through Azure
`DefaultAzureCredential`; use a managed/workload identity or another supported credential source
configured outside the repository. That identity needs read access to the selected subscription
and `Microsoft.ResourceGraph/resources/read`. The query returns resource ID, name, type, location,
subscription ID and resource group only, in pages of at most 100 rows using `$skipToken`.
Azure Monitor metrics are not part of this slice. Azure connections remain `UNACCEPTED` until the
live read-only conformance suite passes; this implementation does not require or use a live Azure
account during local tests.

The first Google Cloud slice collects project-wide resource inventory from Cloud Asset Inventory.
Register the Google Cloud project ID as `read_only_identity_ref` and bind a source such as
`gcp/europe-west1/assetinventory/inventory`. The region segment identifies the source but does not
filter the project-wide asset results. The service uses Google Application Default Credentials
(ADC) with the `cloud-platform` scope; local ADC can be configured with
`gcloud auth application-default login`. The identity needs `cloudasset.assets.listResource`,
normally through `roles/cloudasset.viewer`, and `serviceusage.services.use`, normally through
`roles/serviceusage.serviceUsageConsumer`. Each request reads at most 100 assets and retains
`nextPageToken` as the checkpoint. Observation payloads contain only asset name, type, and update
time, not resource data. Cloud Monitoring metrics are not part of this slice. GCP connections
remain `UNACCEPTED` until live read-only conformance passes; no live Google Cloud account is used
by local tests.

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
| GCP | Project ID and source region | Project-wide Cloud Asset Inventory, including global assets; the source region is not a filter |

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
