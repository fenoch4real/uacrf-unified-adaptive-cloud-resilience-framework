# S07 Assessment, Path Independence Certification (P5) & Decision Envelopes (P1)

## Overview
`assessment-service` (`S07`, port `8087`, database `uacrf_assessment`) implements the UACRF **Assess** pillar. It evaluates whether critical Customer Journeys can survive failure scenarios and seals verifiable proof of every conclusion without mutating production workloads.

## Simple Requirements & Invariants
1. **Read-Only Assessment & Dual Independent Review:** Evaluates journey resilience and enforces strict separation between the assessor, technical reviewer, and independent reviewer (`403 SELF_REVIEW_FORBIDDEN`) prior to marking reports `ISSUED`.
2. **Correlation-Domain Path Independence (`P5`):** `PathIndependenceCertifier` compares primary and alternate recovery paths across `REGION`, `ZONE`, `CLUSTER`, `IDP`, `KMS_KEY`, `DNS`, `DATA_STORE`, `RELEASE`, and `VENDOR`. Shared critical dependencies return `NOT_INDEPENDENT` alongside explicit witness paths rather than falsely claiming redundancy.
3. **Cryptographic Decision Sealing & Replay (`P1`):** Computes canonical RFC 8785 SHA-256 digests (`CanonicalJson`) and signs reports into RS256 JWS `DecisionEnvelope`s (`EnvelopeSigner` / `FipsCryptoProfile`). Historical replay (`POST /v1/decision-replays`) re-verifies signatures and digests (`VERIFIED` vs. `TAMPER_DETECTED`).
