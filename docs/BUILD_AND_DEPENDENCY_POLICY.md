# Build and dependency policy

## Build baseline

- Use the checked-in `mvnw` launcher. It downloads Maven 3.9.11 from Maven
  Central only after verifying the pinned SHA-512 digest.
- Build with Java 25. Maven Enforcer rejects other Maven/Java baselines,
  dependency convergence errors, and dependencies between deployable UACRF
  modules.
- Keep the Spring Boot parent at the exact version in the root POM. Do not use
  `LATEST`, `RELEASE`, version ranges, snapshots, or floating container tags
  for runtime dependencies.
- Every deployment module owns its HTTP boundary. Cross-service calls use
  versioned APIs or events; build-time dependencies between deployable
  modules are rejected.

## Container and local environment policy

- Pin container images by digest. `compose.yaml` currently uses the PostgreSQL
  image digest recorded in the existing runtime test evidence and the pinned
  Keycloak 26.8.2 image-index digest.
- Bind local database ports to loopback and use separate databases, credentials,
  and persistent volumes for S01 and S02. Keycloak is also loopback-bound and
  uses a persistent development volume.
- Copy `.env.example` to `.env`, replace both placeholder passwords with
  independent local database secrets, set distinct Keycloak admin passwords,
  configure an RSA delegation key pair with the private key limited to S01,
  configure only its public key for S02, and never commit `.env` or production
  credentials.
- Do not reset or delete named volumes as part of routine startup or tests.

## CI

GitHub Actions runs the complete Maven reactor with Java 25 on pushes and pull
requests, builds each runtime image, generates SBOMs, and fails on any
HIGH or CRITICAL Trivy finding (no ignore file; a finding is fixed or the
dependency changed, not waived in CI).

Version tags (`v*`) run `.github/workflows/release.yml`, which rebuilds each
image, applies the same scan gate before pushing, pushes to GHCR, and signs
each image by digest with keyless cosign, then verifies the signature against
the workflow identity. The workflow has not yet run against a real tag, and
release-time SBOM attestation and signature-enforcing admission remain open.
