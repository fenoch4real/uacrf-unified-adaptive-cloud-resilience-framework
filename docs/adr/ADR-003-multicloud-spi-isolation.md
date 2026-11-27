# ADR-003: Provider-Neutral Multi-Cloud Discovery & Bounded Execution SPI (`GCP`, `AWS`, `Azure`, `OCI`)

- **Status:** Accepted
- **Summary Description:** Decouples UACRF core resilience engines from cloud vendor SDKs using explicit read-only ingestion (`TelemetryAdapter`) and single-use action (`ProviderActionAdapter`) interfaces with strict pre-storage PII/PHI scrubbing.

## Context
Regulated enterprises operate hybrid workloads simultaneously across GCP, AWS, Azure, OCI, and Kubernetes. Hardcoding vendor control-plane logic into core decision engines introduces vendor lock-in and risks unsafe broad-privilege credentials.

## Decision
1. **Read-Only Discovery SPI (`S03`):** Cloud collectors (`AwsTelemetryAdapter`, `GcpTelemetryAdapter`, `AzureTelemetryAdapter`, `OciTelemetryAdapter`, `OtelReceiverAdapter`) implement `TelemetryAdapter` using read-only federated workload identities while `SampleLocalScenarioGenerator` remains explicitly tagged `SAMPLE_LOCAL` / `SAMPLE_ONLY`.
2. **Pre-Storage Privacy Boundary (`PiiPhiScrubber`):** Emails, SSNs, PCI card numbers, and secrets are scrubbed before persistence or event publication.
3. **Bounded Action SPI (`S11`, `P3`):** Cloud and Kubernetes remediation runs exclusively inside Durable Temporal workflows (`RecoveryWorkflow`) using allowlisted `ProviderActionAdapter` implementations gated by audience-locked, single-use RS256 tokens and validated across all 8 layers (`CrossLayerValidator`) before completion.

## Consequences
Multi-cloud adoption scales by implementing lightweight provider adapters without weakening core fail-closed invariants, correlation-domain analysis (`P5`), or HIPAA/GDPR/PCI data minimization.
