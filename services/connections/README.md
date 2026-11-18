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
collect CloudWatch metrics. AWS remains `UNACCEPTED` until live read-only conformance is complete;
Azure and GCP collectors remain unimplemented. Synthetic sample fixtures are never scheduled as
provider data.

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
