# S14 Commercial Metering, Regulatory Crosswalk & Assurance Rooms Specification

## Purpose & Scope
`reporting-service` (`S14`, port `8093`, database `uacrf_reporting` with PostgreSQL Row-Level Security) bridges operational resilience engineering with executive metering and third-party regulatory assurance.

## Core Capabilities & Simple Requirements
1. **Commercial Asset & Evaluation Metering (`/v1/reporting/metering`):**
   - Records tamper-evident usage events (`PROTECTED_SERVICE_ASSET`, `PROTECTED_AGENT_WORKLOAD`, `CONTINUOUS_ASSURANCE_EVAL`, `BOUNDED_RECOVERY`) partitioned by tenant, environment, and cloud provider (`AWS`, `GCP`, `AZURE`, `OCI`) with SHA-256 event digests and transactional outbox emission.
2. **Multi-Framework Common Control Crosswalk (`/v1/reporting/crosswalk`):**
   - Exposes live machine-readable and operator-viewable mappings connecting implemented UACRF controls (`S01`–`S14`, `P1`–`P7`, RLS, WORM, FIPS, CI gates) to **SOC 2, ISO 27001, ISO 42001, EU DORA, HIPAA, and FedRAMP** expectations.
3. **Watermarked Time-Bound External Assurance Rooms (`/v1/assurance-rooms`):**
   - Allows authorized stewards to mint short-lived, read-only auditor bundles watermarked with the auditor organization, tenant ID, expiration timestamp, and canonical bundle SHA-256 digest (`ComplianceAssuranceService`).
   - Enforces token hash verification (`403 INVALID_ASSURANCE_TOKEN`) and strict expiration (`410 ASSURANCE_ROOM_EXPIRED`), accessible interactively through `EDGE01` and `UX01` (`/assurance`).
