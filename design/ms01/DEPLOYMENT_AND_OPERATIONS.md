# Deployment profiles and operating responsibilities

Version 1.0 | Architecture revision MS 01

## Environments and infrastructure

The slice development profile runs only the services needed by the current milestone, with real separate logical databases and service HTTP. The full integration profile runs all target services and workers plus PostgreSQL, Neo4j, Kafka, object storage, a test identity provider and telemetry. Production uses pinned images and approved managed state where available. It does not silently substitute a provider's event product for Kafka without conformance testing.

For AWS, Azure and GCP, independently qualify Kubernetes/workload identity, PostgreSQL configuration and restore, artifact versioning/retention, secret rotation, private networking, source collection and each selected mutating action. Region and account/subscription/project scope belong to the connection and execution-cell configuration. Cloud-provider icons in the UI do not imply equal behavior or completed integration.

Use one control-plane primary region initially. Deploy execution cells near their permitted resources, with an explicit data policy for regional model or artifact movement. Separate development, staging and production trust roots and credentials. Production artifacts are signed and promoted from tested images; source compilation does not happen inside a running production container.

## Capacity planning assumptions

An initial planning request of 250 millicores and 512 MiB per API process across sixteen components totals 4 vCPU and 8 GiB at one replica, or 8 vCPU and 16 GiB at two replicas. This arithmetic excludes workers, stateful systems, telemetry, orchestration overhead and spare failure capacity. It is not measured application sizing or a production guarantee.

Measure startup/steady-state memory, JVM heap/native overhead, CPU, connection pools, broker partitions, graph traversal load and retained byte volume in M1/M2 before setting production requests and limits. Scale worker concurrency from observed queue age and safe source/provider budgets. A complete local environment can be substantially heavier than the current single application; milestone profiles make development practical without pretending that services share one runtime.

## Ownership and operational procedures

Platform operations owns cluster, broker, secret, backup and telemetry configuration. Domain owners own their migrations, API/event contracts, data retention and application runbooks. Security owns trust roots, emergency access and independent review of privilege changes. Finance owns charging policy and commercial approval; it does not approve recovery actions by default.

Runbooks cover authorization outage, source/provider outage, projection mismatch, outbox/inbox backlog, dead-letter replay, lost worker lease, unknown provider effect, stuck validation, model-gateway uncertainty, notification failure, payment reconciliation and restored authority state. Every procedure identifies what may be retried, what needs source verification, which identity can perform the action and what evidence is retained.

## Backup and disaster recovery

Back up owner databases and immutable artifact metadata/bytes with declared retention and restore points. Kafka retention supports replay within its approved window but is not the sole backup of domain truth. Neo4j can be restored or rebuilt from accepted graph revisions; a rebuild remains ineligible until its actual digest/revision verifies.

After restore, hold new operational permission in AUTHORITY_RECOVERY mode until S01/S10 control epochs and retained trusted watermarks reconcile, or an approved re-admission procedure establishes safe current identities. Replaying old events cannot reactivate a revoked permission. Reconcile artifact availability, owner references, workflows, provider operation status, invoices and projection generations before resuming affected operations.

## Release and rollback

Apply expand-compatible schema changes first, deploy compatible consumers/producers, migrate data and contract old fields only after observed compatibility. Releasing one service should not require unrelated services to deploy simultaneously. A domain ownership cutover has exactly one writer and a retained epoch/watermark. A rollback after new writes needs data reconciliation, not just an earlier image or route setting.

Deployment acceptance records image/dependency hashes, configuration/trust versions, schema migrations, consumer compatibility, health probes, graceful shutdown, restart recovery and restoration evidence. Infrastructure creation and live provider access remain separate implementation actions; this design performs neither.
