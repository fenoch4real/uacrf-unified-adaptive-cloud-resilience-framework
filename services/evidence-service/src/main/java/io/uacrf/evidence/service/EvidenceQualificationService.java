package io.uacrf.evidence.service;

import io.uacrf.crypto.CanonicalJson;
import io.uacrf.evidence.api.EvidenceDomainException;
import io.uacrf.evidence.api.EvidenceDtos.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class EvidenceQualificationService {

    private final EvidenceRepository repository;
    private final QualificationEngine engine;
    private final ObjectLockWormClient s3WormClient;

    public EvidenceQualificationService(EvidenceRepository repository,
                                        QualificationEngine engine,
                                        ObjectLockWormClient s3WormClient) {
        this.repository = repository;
        this.engine = engine;
        this.s3WormClient = s3WormClient;
    }

    public List<EvidencePackageDto> listPackages(UUID tenantId, UUID environmentId) {
        return repository.listPackages(tenantId, environmentId);
    }

    public EvidencePackageDto getPackage(UUID tenantId, UUID environmentId, UUID packageId) {
        return repository.findPackage(tenantId, environmentId, packageId)
                .orElseThrow(() -> new EvidenceDomainException(
                        HttpStatus.NOT_FOUND, "EVIDENCE_PACKAGE_NOT_FOUND", "Evidence package not found"));
    }

    @Transactional
    public EvidencePackageDto submitEvidence(UUID packageId, SubmitEvidenceRequest req) {
        EvidencePackageDto pkg = getPackage(req.tenantId(), req.environmentId(), packageId);
        var verdict = engine.qualify(
                req.itemKey(), req.sourceKind(), pkg.graphRevision(), req.graphRevision(), req.ttlSeconds(), req.rawPayload()
        );

        String payloadJson = CanonicalJson.toJson(req.rawPayload() != null ? req.rawPayload() : Map.of("detail", req.detailNote()));
        String s3Key = req.tenantId() + "/evidence/" + packageId + "/" + req.itemKey() + "/" + verdict.sha256Digest().substring(0, 12) + ".json";
        String objectKey = "worm://" + s3Key;
        repository.storeImmutableBlob(req.tenantId(), objectKey, verdict.sha256Digest(), payloadJson);
        s3WormClient.storeWormObject(s3Key, payloadJson, verdict.sha256Digest());

        Instant now = Instant.now();
        int ttl = req.ttlSeconds() != null && req.ttlSeconds() > 0 ? req.ttlSeconds() : 3600;
        repository.updateItem(
                packageId,
                req.itemKey(),
                req.sourceKind(),
                verdict.availabilityStatus(),
                verdict.qualificationState(),
                verdict.freshnessState(),
                req.detailNote(),
                objectKey,
                verdict.sha256Digest(),
                req.graphRevision(),
                now,
                now.plus(ttl, ChronoUnit.SECONDS)
        );

        List<EvidenceItemDto> updatedItems = repository.listItems(packageId);
        boolean allQualified = updatedItems.stream()
                .filter(EvidenceItemDto::requiredForRecovery)
                .allMatch(i -> "QUALIFIED".equals(i.qualificationState()));

        repository.updatePackageStatus(
                packageId,
                allQualified ? "COMPLETE" : "MISSING_REQUIRED",
                allQualified ? "QUALIFIED" : "UNKNOWN"
        );

        EvidencePackageDto refreshed = getPackage(req.tenantId(), req.environmentId(), packageId);
        repository.insertOutbox(
                req.tenantId(), req.environmentId(),
                "evidence.item.qualified", "EvidencePackage", packageId.toString(),
                UUID.randomUUID(), CanonicalJson.toJson(refreshed)
        );
        return refreshed;
    }

    public List<AiDependencyEvidenceDto> listAiEvidence(UUID tenantId, UUID environmentId, String serviceCanonicalId) {
        return repository.listAiEvidence(tenantId, environmentId, serviceCanonicalId);
    }

    @Transactional
    public List<AiDependencyEvidenceDto> qualifyAiAspect(UpdateAiEvidenceRequest req) {
        if ("KNOWLEDGE_DOC".equalsIgnoreCase(req.sourceKind()) || "ADVISORY_DRAFT".equalsIgnoreCase(req.sourceKind())) {
            throw new EvidenceDomainException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "SYNTHETIC_FALLBACK_FORBIDDEN",
                    "Reference knowledge cannot replace empirical AI quality, subgroup safety, or fallback tests."
            );
        }
        repository.updateAiAspect(
                req.tenantId(), req.environmentId(), req.serviceCanonicalId(),
                req.aspectKey(), "OBSERVED", req.currentValue(), "QUALIFIED", Instant.now()
        );
        return repository.listAiEvidence(req.tenantId(), req.environmentId(), req.serviceCanonicalId());
    }

    public ExportedDraftBundle exportPackage(UUID tenantId, UUID environmentId, UUID packageId) {
        EvidencePackageDto pkg = getPackage(tenantId, environmentId, packageId);
        List<String> limitations = new ArrayList<>();
        for (EvidenceItemDto item : pkg.items()) {
            if (!"QUALIFIED".equals(item.qualificationState())) {
                limitations.add(item.itemLabel() + " is " + item.qualificationState() + ": " + item.detailNote());
            }
        }
        String exportKind = "COMPLETE".equals(pkg.completenessStatus()) ? "FINAL_QUALIFIED_RECORD" : "DRAFT_WITH_DISCLOSURES";
        String bundleHash = CanonicalJson.digestObject(Map.of(
                "packageId", pkg.packageId().toString(),
                "exportKind", exportKind,
                "limitations", limitations,
                "items", pkg.items()
        ));
        return new ExportedDraftBundle(
                pkg.packageId(), exportKind, pkg.completenessStatus(), pkg.recoveryReadiness(),
                bundleHash, limitations, pkg, Instant.now()
        );
    }
}
