# S03, S04, and S06 Ingestion, Evidence & Knowledge Graph Specification

## Overview
Resilient decisions depend on trustworthy signals, tamper-evident proof, and an accurate structural map of dependencies across **GCP, AWS, Azure, OCI, and Kubernetes**. Together, `S03`, `S04`, and `S06` convert raw operational telemetry into qualified evidence and a canonical Neo4j dependency graph while preserving strict authority boundaries.

| Service | Module | Port | Owned Stores | Exclusive Write Authority |
|---|---|---|---|---|
| **S03** Discovery & Telemetry Adapter | `services/discovery-service` | `8084` | `uacrf_discovery` | Connector health, PII-scrubbed observations, discovered edges |
| **S04** Evidence Qualification & Storage | `services/evidence-service` | `8085` | `uacrf_evidence` + S3 Object Lock | Qualification verdicts, WORM object blobs, package readiness, AI checks |
| **S06** Knowledge Graph Projection & Query | `services/graph-service` | `8086` | `uacrf_graph` + **Neo4j 5.26** | Sole Neo4j writer, `58`-label / `55`-relationship enforcement, drift detection |

## Simple Requirements & Invariants
1. **Multi-Cloud Discovery SPI & Synthetic Isolation (`S03`):** Cloud collectors (`AwsTelemetryAdapter`, `GcpTelemetryAdapter`, `AzureTelemetryAdapter`, `OciTelemetryAdapter`, `OtelReceiverAdapter`) implement `TelemetryAdapter`. Local lab simulations live exclusively in `SampleLocalScenarioGenerator` tagged `SAMPLE_LOCAL` / `SAMPLE_ONLY`.
2. **Pre-Storage PII/PHI Sanitization (`S03`):** `PiiPhiScrubber` redacts email addresses, SSNs, PCI card numbers, Bearer tokens, and cloud access keys before observations enter PostgreSQL or the outbox bus.
3. **No-Synthetic-Fallback & WORM Storage (`S04`):** `QualificationEngine` enforces freshness TTLs and graph-revision bindings, locks qualified payloads into S3 Object Lock (`ObjectLockWormClient`), and rejects `KNOWLEDGE_DOC`, `ADVISORY_DRAFT`, `LLM_SUMMARY`, or `RUNBOOK` evidence with `422 SYNTHETIC_FALLBACK_FORBIDDEN`. Missing probes keep readiness explicitly `UNKNOWN`.
4. **Customer AI Empirical Checks (`S04`):** Protected AI dependencies require 6 verified checks: `endpoint_availability`, `latency_p95`, `model_quality`, `subgroup_safety`, `feature_freshness`, and `fallback_capacity`.
5. **Sole Neo4j Writer, Non-Causation & Drift Detection (`S06`):** All graph mutations validate `58` node labels, `55` relationship types, and prohibited property keys (`Neo4jOntology`). `UNCERTAIN` edges display dashed with non-causation notices, and `GraphDriftReconciler` compares Neo4j projections against sealed PostgreSQL revisions (`PROJECTED` vs. `DRIFTED`).
