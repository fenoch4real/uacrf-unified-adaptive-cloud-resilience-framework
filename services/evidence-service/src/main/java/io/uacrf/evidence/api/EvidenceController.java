package io.uacrf.evidence.api;

import io.uacrf.evidence.api.EvidenceDtos.*;
import io.uacrf.evidence.service.EvidenceQualificationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/evidence")
public class EvidenceController {

    private final EvidenceQualificationService service;

    public EvidenceController(EvidenceQualificationService service) {
        this.service = service;
    }

    @GetMapping("/packages")
    public List<EvidencePackageDto> listPackages(@RequestParam UUID tenantId, @RequestParam UUID environmentId) {
        return service.listPackages(tenantId, environmentId);
    }

    @GetMapping("/packages/{packageId}")
    public EvidencePackageDto getPackage(@PathVariable UUID packageId,
                                         @RequestParam UUID tenantId,
                                         @RequestParam UUID environmentId) {
        return service.getPackage(tenantId, environmentId, packageId);
    }

    @PostMapping("/packages/{packageId}/items")
    public EvidencePackageDto submitEvidence(@PathVariable UUID packageId,
                                             @Valid @RequestBody SubmitEvidenceRequest request) {
        return service.submitEvidence(packageId, request);
    }

    @GetMapping("/packages/{packageId}/export")
    public ExportedDraftBundle exportPackage(@PathVariable UUID packageId,
                                             @RequestParam UUID tenantId,
                                             @RequestParam UUID environmentId) {
        return service.exportPackage(tenantId, environmentId, packageId);
    }

    @GetMapping("/ai-dependencies")
    public List<AiDependencyEvidenceDto> listAiEvidence(@RequestParam UUID tenantId,
                                                        @RequestParam UUID environmentId,
                                                        @RequestParam(required = false) String serviceCanonicalId) {
        return service.listAiEvidence(tenantId, environmentId, serviceCanonicalId);
    }

    @PostMapping("/ai-dependencies")
    public List<AiDependencyEvidenceDto> qualifyAiAspect(@Valid @RequestBody UpdateAiEvidenceRequest request) {
        return service.qualifyAiAspect(request);
    }

    @GetMapping("/blobs/verify")
    public java.util.Map<String, Object> verifyBlob(@RequestParam UUID tenantId, @RequestParam String objectKey) {
        return service.verifyBlobMetadata(tenantId, objectKey);
    }
}
