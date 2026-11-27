# ADR-005: Common Control Framework, FIPS 140-3 Cryptography & External Assurance Rooms

- **Status:** Accepted
- **Summary Description:** Embeds regulatory controls directly into shared runtime libraries, CI supply-chain gates, and `S14` external auditor rooms rather than retrofitting compliance spreadsheets after delivery.

## Context
Regulated organizations adopting UACRF must demonstrate simultaneous adherence to SOC 2, ISO 27001/22301/42001, GDPR, EU DORA, UK PRA, PCI DSS, HIPAA, FedRAMP, and NIST SSDF without slowing delivery across cloud environments.

## Decision
1. **Cryptographic Profile (`uacrf-crypto`):** `FipsCryptoProfile` and `VaultOrCloudKmsProvider` enforce minimum `3072`-bit RSA key strength under FIPS mode alongside RFC 8785 canonical digests.
2. **SSDF CI Supply-Chain Gates:** Every push and pull request runs Gitleaks secret detection, Trivy vulnerability/IaC scanning, CodeQL SAST, CycloneDX SBOM generation, and main-branch Cosign signing readiness.
3. **Commercial Metering & Auditor Rooms (`S14`):** `reporting-service` emits live multi-framework control mappings and issues time-bound, cryptographically watermarked External Assurance Rooms.

## Consequences
Engineering verification artifacts (test results, SBOMs, OPA Rego digests, traceability JSON) feed directly into auditable evidence bundles (`collect-soc2-evidence.sh`), leaving only organizational governance and external CPA/3PAO sign-off for `Phase E4`.
