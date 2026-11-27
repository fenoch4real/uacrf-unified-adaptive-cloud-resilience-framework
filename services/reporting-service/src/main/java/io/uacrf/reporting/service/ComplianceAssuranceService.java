package io.uacrf.reporting.service;

import io.uacrf.crypto.CanonicalJson;
import io.uacrf.reporting.api.ReportingDomainException;
import io.uacrf.reporting.api.ReportingDtos.*;
import io.uacrf.reporting.service.ReportingRepository.StoredRoom;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class ComplianceAssuranceService {

    private static final List<ComplianceControlMapping> CROSSWALK = List.of(
            new ComplianceControlMapping("CC6.1 / CC6.3", "SOC 2", "Logical Access & Tenant Isolation",
                    "PostgreSQL FORCE ROW LEVEL SECURITY + S01 grant validation + EDGE01 OAuth2/JWT", "IMPLEMENTED", "S01, PostgreSQL RLS, EDGE01"),
            new ComplianceControlMapping("CC7.2 / CC7.3", "SOC 2", "Anomaly Monitoring & Incident Response",
                    "Multi-cloud OTel/CloudWatch/GCP/Azure/OCI ingestion + S07 Decision Envelope + S11 Temporal Saga", "IMPLEMENTED", "S03, S07, S11"),
            new ComplianceControlMapping("CC8.1 / CC9.2", "SOC 2", "Change Authorization & Bounded Recovery",
                    "S10 OPA Rego policy + P3 BoundedRecoveryAuthorization + P6 8-layer probe gate", "IMPLEMENTED", "S10, S11"),
            new ComplianceControlMapping("A.5.33 / A.8.15", "ISO 27001", "Protection of Records & Immutable Logs",
                    "S04 S3 Object Lock WORM GOVERNANCE retention + SHA-256 canonical digest chaining", "IMPLEMENTED", "S04, S3 Object Lock"),
            new ComplianceControlMapping("A.6.1 / B.6", "ISO 42001", "AI Risk & Non-Authoritative Advisory",
                    "P7 Deterministic Arbiter + S09 Prompt-Injection & PII Firewall + Critique preservation", "IMPLEMENTED", "S09, P7"),
            new ComplianceControlMapping("Art. 17 / Art. 25", "EU DORA", "ICT Incident Recovery & Operational Resilience Testing",
                    "P5 Journey Path Certification + S11 auto-compensating recovery + S13 quantitative shadow benchmarks", "IMPLEMENTED", "S07, S11, S13"),
            new ComplianceControlMapping("164.312(a)(b)(e)", "HIPAA", "PHI Redaction, Audit Controls & Integrity",
                    "S03/S09 PiiPhiScrubber pre-storage PHI/PII masking + WORM audit trail + TLS/JWT headers", "IMPLEMENTED", "S03, S09, EDGE01"),
            new ComplianceControlMapping("AC-3 / AU-9 / SI-7", "FedRAMP", "Access Enforcement, Audit Protection & Integrity",
                    "S10 OPA fail-closed policy + Neo4j drift reconciler + SSDF CycloneDX SBOM & Trivy gates", "IMPLEMENTED", "S06, S10, CI")
    );

    private final ReportingRepository repository;

    public ComplianceAssuranceService(ReportingRepository repository) {
        this.repository = repository;
    }

    public List<MeteringEventDto> listMetering(UUID tenantId, UUID environmentId) {
        return repository.listMetering(tenantId, environmentId);
    }

    @Transactional
    public MeteringEventDto recordMetering(RecordMeteringRequest req) {
        String digest = CanonicalJson.digestObject(req);
        MeteringEventDto dto = new MeteringEventDto(
                UUID.randomUUID(), req.tenantId(), req.environmentId(),
                req.meterDimension().toUpperCase(Locale.ROOT),
                req.cloudProvider().toUpperCase(Locale.ROOT),
                req.quantity(), req.unit(), digest, Instant.now()
        );
        repository.insertMetering(dto);
        repository.insertOutbox(req.tenantId(), req.environmentId(), "reporting.metering.recorded",
                "MeteringEvent", dto.meterId().toString(), UUID.randomUUID(), CanonicalJson.toJson(dto));
        return dto;
    }

    public List<ComplianceControlMapping> getComplianceCrosswalk(String frameworkFilter) {
        if (frameworkFilter == null || frameworkFilter.isBlank()) {
            return CROSSWALK;
        }
        String norm = frameworkFilter.trim().toLowerCase(Locale.ROOT);
        return CROSSWALK.stream()
                .filter(c -> c.framework().toLowerCase(Locale.ROOT).contains(norm))
                .toList();
    }

    @Transactional
    public CreatedAssuranceRoomResponse createAssuranceRoom(CreateAssuranceRoomRequest req) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(req.ttlHours(), ChronoUnit.HOURS);
        String rawToken = "uacrf-room-" + UUID.randomUUID();
        String tokenHash = CanonicalJson.digestObject(rawToken);
        List<String> normalizedFrameworks = req.frameworks().stream()
                .map(f -> f.trim().toUpperCase(Locale.ROOT)).toList();

        List<ComplianceControlMapping> selectedControls = filterControlsForFrameworks(normalizedFrameworks);
        List<MeteringEventDto> metering = repository.listMetering(req.tenantId(), req.environmentId());
        String watermark = "WATERMARK::AUDITOR=" + req.auditorOrganization().toUpperCase(Locale.ROOT)
                + "::TENANT=" + req.tenantId() + "::EXPIRES=" + expiresAt;

        UUID roomId = UUID.randomUUID();
        String bundleDigest = CanonicalJson.digestObject(new BundleDigestInput(
                roomId, watermark, normalizedFrameworks, selectedControls, metering));

        StoredRoom room = new StoredRoom(
                roomId, req.tenantId(), req.environmentId(), req.auditorOrganization(),
                req.recipientEmail(), normalizedFrameworks, tokenHash, watermark,
                bundleDigest, "ACTIVE", req.createdBy(), expiresAt, now
        );
        repository.insertAssuranceRoom(room);
        repository.insertOutbox(req.tenantId(), req.environmentId(), "reporting.assurance_room.created",
                "AssuranceRoom", roomId.toString(), UUID.randomUUID(), CanonicalJson.toJson(room.toSummary()));

        return new CreatedAssuranceRoomResponse(
                roomId, req.tenantId(), req.environmentId(), req.auditorOrganization(),
                normalizedFrameworks, watermark, bundleDigest, rawToken, "ACTIVE", expiresAt, now
        );
    }

    public List<AssuranceRoomSummaryDto> listAssuranceRooms(UUID tenantId, UUID environmentId) {
        return repository.listAssuranceRooms(tenantId, environmentId);
    }

    public AssuranceEvidenceBundleDto accessAssuranceBundle(UUID roomId, String accessToken) {
        StoredRoom room = repository.findRoom(roomId)
                .orElseThrow(() -> new ReportingDomainException(
                        HttpStatus.NOT_FOUND, "ASSURANCE_ROOM_NOT_FOUND", "Assurance room not found: " + roomId));
        if (!room.expiresAt().isAfter(Instant.now())) {
            throw new ReportingDomainException(HttpStatus.GONE, "ASSURANCE_ROOM_EXPIRED",
                    "Assurance room access expired at " + room.expiresAt());
        }
        String suppliedHash = CanonicalJson.digestObject(accessToken == null ? "" : accessToken);
        if (!room.accessTokenSha256().equals(suppliedHash)) {
            throw new ReportingDomainException(HttpStatus.FORBIDDEN, "INVALID_ASSURANCE_TOKEN",
                    "Invalid external auditor assurance token.");
        }
        List<ComplianceControlMapping> controls = filterControlsForFrameworks(room.frameworks());
        List<MeteringEventDto> metering = repository.listMetering(room.tenantId(), room.environmentId());
        return new AssuranceEvidenceBundleDto(
                room.roomId(), room.watermarkText(), room.auditorOrganization(), room.frameworks(),
                room.bundleSha256(), controls, metering, Instant.now(), room.expiresAt()
        );
    }

    private List<ComplianceControlMapping> filterControlsForFrameworks(List<String> frameworks) {
        Set<String> set = frameworks.stream().map(s -> s.toUpperCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
        if (set.contains("ALL")) {
            return CROSSWALK;
        }
        var filtered = CROSSWALK.stream()
                .filter(c -> set.contains(c.framework().toUpperCase(Locale.ROOT)))
                .toList();
        return filtered.isEmpty() ? CROSSWALK : filtered;
    }

    private record BundleDigestInput(
            UUID roomId,
            String watermark,
            List<String> frameworks,
            List<ComplianceControlMapping> controls,
            List<MeteringEventDto> metering
    ) {
    }
}
