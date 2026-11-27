# S11 Temporal Recovery Orchestrator, P3 Adapters & P6 Validation Spec

- **P3 Allowlisted Adapter Runtime (`ProviderActionAdapter`)**: Validates Kill Switch state, RS256 expiration, tenant/environment match, exact adapter audience binding, strict target canonical ID allowlist (`TARGET_SUBSTITUTION_FORBIDDEN`), and atomic single-use nonce consumption (`NONCE_REPLAY_DETECTED`) prior to dispatching workload rollbacks.
- **P6 Cross-Layer Validation DAG (`CrossLayerValidator`)**: Runs ordered verification across Infrastructure, Platform, Application, Data, Security, AI Workload, SLO, and Synthetic Customer Journey Probe (`JOURNEY_PROBE`). Mandatory failure triggers bounded `adapter.compensate(...)` -> `COMPENSATED`.
