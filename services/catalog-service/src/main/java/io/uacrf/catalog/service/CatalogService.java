package io.uacrf.catalog.service;

import io.uacrf.catalog.api.CatalogDomainException;
import io.uacrf.catalog.api.CatalogDtos.*;
import io.uacrf.crypto.CanonicalJson;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * S02 authoritative service for Services, Protected Agent Workloads, Customer Journeys,
 * Journey Impact Profiles, and Shared Resources.
 */
@Service
public class CatalogService {

    private final CatalogRepository repository;
    private final Clock clock = Clock.systemUTC();

    public CatalogService(CatalogRepository repository) {
        this.repository = repository;
    }

    public List<ServiceView> listServices(UUID tenantId, UUID environmentId) {
        return repository.listConventionalServices(tenantId, environmentId);
    }

    @Transactional
    public ServiceView registerService(UUID actorId, String idempotencyKey, CreateServiceRequest req) {
        String digest = CanonicalJson.digestObject(req);
        var existing = repository.findIdempotency(req.tenantId(), req.environmentId(), actorId, "CREATE_SERVICE", idempotencyKey);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), digest, ServiceView.class);
        }
        ServiceView created = repository.insertService(req, actorId, "CONVENTIONAL");
        repository.saveIdempotency(req.tenantId(), req.environmentId(), actorId, "CREATE_SERVICE",
                idempotencyKey, digest, HttpStatus.CREATED.value(), created);
        return created;
    }

    public List<AgentWorkloadView> listAgentWorkloads(UUID tenantId, UUID environmentId, UUID parentServiceId) {
        return repository.listAgentWorkloads(tenantId, environmentId, parentServiceId);
    }

    @Transactional
    public AgentWorkloadView registerAgentWorkload(UUID actorId, String idempotencyKey, RegisterAgentWorkloadRequest req) {
        if (req.runtimePrincipalId().equals(req.ownerId())) {
            throw new CatalogDomainException(HttpStatus.BAD_REQUEST, "RUNTIME_PRINCIPAL_CONFLICT",
                    "Agent runtime principal must be separate from accountable human owner");
        }
        String digest = CanonicalJson.digestObject(req);
        var existing = repository.findIdempotency(req.tenantId(), req.environmentId(), actorId, "REGISTER_AGENT", idempotencyKey);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), digest, AgentWorkloadView.class);
        }

        if (req.parentServiceId() != null) {
            repository.findService(req.tenantId(), req.environmentId(), req.parentServiceId())
                    .orElseThrow(() -> new CatalogDomainException(HttpStatus.NOT_FOUND, "PARENT_SERVICE_NOT_FOUND",
                            "Parent service does not exist in tenant/environment"));
        }

        // Create internal AGENT_ANCHOR service identity (never double-counted by GET /v1/services)
        ServiceView anchor = repository.insertService(
                new CreateServiceRequest(req.tenantId(), req.environmentId(), "agent-anchor-" + req.name(),
                        "agent-anchor", "Internal governance anchor for " + req.name(), req.criticality(), req.ownerId()),
                actorId, "AGENT_ANCHOR");

        AgentWorkloadView created = repository.insertAgentWorkload(req, anchor.serviceId(), actorId);
        repository.saveIdempotency(req.tenantId(), req.environmentId(), actorId, "REGISTER_AGENT",
                idempotencyKey, digest, HttpStatus.CREATED.value(), created);
        return created;
    }

    @Transactional
    public WorkloadVersionView registerWorkloadVersion(UUID actorId, UUID profileId, RegisterWorkloadVersionRequest req) {
        repository.findAgentWorkloadForUpdate(req.tenantId(), req.environmentId(), profileId)
                .orElseThrow(() -> new CatalogDomainException(HttpStatus.NOT_FOUND, "WORKLOAD_NOT_FOUND",
                        "Protected agent workload not found"));
        String manifestDigest = CanonicalJson.digestObject(req.manifest());
        return repository.insertWorkloadVersion(profileId, req, actorId, manifestDigest);
    }

    @Transactional
    public WorkloadVersionView activateWorkloadVersion(UUID actorId, UUID profileId, ActivateWorkloadVersionRequest req) {
        AgentWorkloadView profile = repository.findAgentWorkloadForUpdate(req.tenantId(), req.environmentId(), profileId)
                .orElseThrow(() -> new CatalogDomainException(HttpStatus.NOT_FOUND, "WORKLOAD_NOT_FOUND",
                        "Protected agent workload not found"));
        if (profile.version() != req.expectedProfileVersion()) {
            throw new CatalogDomainException(HttpStatus.CONFLICT, "STALE_REVISION",
                    "Workload profile revision mismatch");
        }
        return repository.activateWorkloadVersion(profile, req.versionNumber(), actorId, clock.instant());
    }

    public List<JourneyView> listJourneys(UUID tenantId, UUID environmentId) {
        return repository.listJourneys(tenantId, environmentId);
    }

    public JourneyDetailView getJourneyDetail(UUID tenantId, UUID environmentId, UUID journeyId) {
        JourneyView journey = repository.findJourney(tenantId, environmentId, journeyId)
                .orElseThrow(() -> new CatalogDomainException(HttpStatus.NOT_FOUND, "JOURNEY_NOT_FOUND", "Journey not found"));
        return new JourneyDetailView(
                journey,
                repository.listJourneySteps(journeyId),
                repository.listJourneyCapabilities(journeyId),
                repository.findLatestImpactProfile(journeyId).orElse(null)
        );
    }

    @Transactional
    public JourneyView createJourney(UUID actorId, String idempotencyKey, CreateJourneyRequest req) {
        String digest = CanonicalJson.digestObject(req);
        var existing = repository.findIdempotency(req.tenantId(), req.environmentId(), actorId, "CREATE_JOURNEY", idempotencyKey);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), digest, JourneyView.class);
        }
        JourneyView created = repository.insertJourney(req, actorId);
        repository.saveIdempotency(req.tenantId(), req.environmentId(), actorId, "CREATE_JOURNEY",
                idempotencyKey, digest, HttpStatus.CREATED.value(), created);
        return created;
    }

    @Transactional
    public JourneyStepView addJourneyStep(UUID actorId, UUID journeyId, AddJourneyStepRequest req) {
        repository.findJourney(req.tenantId(), req.environmentId(), journeyId)
                .orElseThrow(() -> new CatalogDomainException(HttpStatus.NOT_FOUND, "JOURNEY_NOT_FOUND", "Journey not found"));
        return repository.insertJourneyStep(journeyId, req, actorId);
    }

    @Transactional
    public JourneyCapabilityView linkCapability(UUID actorId, String idempotencyKey, UUID journeyId,
                                                LinkJourneyCapabilityRequest req) {
        repository.findJourney(req.tenantId(), req.environmentId(), journeyId)
                .orElseThrow(() -> new CatalogDomainException(HttpStatus.NOT_FOUND, "JOURNEY_NOT_FOUND", "Journey not found"));
        String digest = CanonicalJson.digestObject(req);
        var existing = repository.findIdempotency(req.tenantId(), req.environmentId(), actorId, "LINK_CAPABILITY", idempotencyKey);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), digest, JourneyCapabilityView.class);
        }
        JourneyCapabilityView created = repository.insertJourneyCapability(journeyId, req, actorId);
        repository.saveIdempotency(req.tenantId(), req.environmentId(), actorId, "LINK_CAPABILITY",
                idempotencyKey, digest, HttpStatus.CREATED.value(), created);
        return created;
    }

    @Transactional
    public ImpactProfileView saveImpactProfileDraft(UUID actorId, UUID journeyId, UpsertImpactProfileRequest req) {
        repository.findJourney(req.tenantId(), req.environmentId(), journeyId)
                .orElseThrow(() -> new CatalogDomainException(HttpStatus.NOT_FOUND, "JOURNEY_NOT_FOUND", "Journey not found"));
        String digest = CanonicalJson.digestObject(req);
        return repository.insertImpactProfileDraft(journeyId, req, actorId, digest);
    }

    @Transactional
    public ImpactProfileView submitImpactProfile(UUID actorId, UUID journeyId, long revision,
                                                 UUID tenantId, UUID environmentId) {
        ImpactProfileView profile = repository.findImpactProfileForUpdate(tenantId, environmentId, journeyId, revision)
                .orElseThrow(() -> new CatalogDomainException(HttpStatus.NOT_FOUND, "IMPACT_PROFILE_NOT_FOUND",
                        "Impact profile revision not found"));
        if (!"DRAFT".equals(profile.assessmentStatus())) {
            throw new CatalogDomainException(HttpStatus.CONFLICT, "INVALID_PROFILE_STATE",
                    "Only DRAFT profiles can be submitted");
        }
        return repository.updateImpactProfileStatus(tenantId, environmentId, profile, "SUBMITTED", null, null, clock.instant());
    }

    @Transactional
    public ImpactProfileView reviewImpactProfile(UUID reviewerId, UUID journeyId, long revision,
                                                 ReviewImpactProfileRequest req) {
        ImpactProfileView profile = repository.findImpactProfileForUpdate(req.tenantId(), req.environmentId(), journeyId, revision)
                .orElseThrow(() -> new CatalogDomainException(HttpStatus.NOT_FOUND, "IMPACT_PROFILE_NOT_FOUND",
                        "Impact profile revision not found"));
        if (!"SUBMITTED".equals(profile.assessmentStatus())) {
            throw new CatalogDomainException(HttpStatus.CONFLICT, "INVALID_PROFILE_STATE",
                    "Only SUBMITTED profiles can be reviewed");
        }
        if (reviewerId.equals(profile.authorId())) {
            throw new CatalogDomainException(HttpStatus.FORBIDDEN, "SELF_REVIEW_PROHIBITED",
                    "Impact profile reviewer must be distinct from the author");
        }
        String nextStatus = switch (req.decision().toUpperCase()) {
            case "APPROVE" -> "APPROVED";
            case "REJECT" -> "REJECTED";
            default -> throw new CatalogDomainException(HttpStatus.BAD_REQUEST, "INVALID_DECISION",
                    "Decision must be APPROVE or REJECT");
        };
        return repository.updateImpactProfileStatus(req.tenantId(), req.environmentId(), profile, nextStatus, reviewerId, req.reason(), clock.instant());
    }

    public List<SharedResourceView> listSharedResources(UUID tenantId, UUID environmentId) {
        return repository.listSharedResources(tenantId, environmentId);
    }

    @Transactional
    public SharedResourceView registerSharedResource(UUID actorId, String idempotencyKey, CreateSharedResourceRequest req) {
        String digest = CanonicalJson.digestObject(req);
        var existing = repository.findIdempotency(req.tenantId(), req.environmentId(), actorId, "CREATE_SHARED_RESOURCE", idempotencyKey);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), digest, SharedResourceView.class);
        }
        SharedResourceView created = repository.insertSharedResource(req, actorId);
        repository.saveIdempotency(req.tenantId(), req.environmentId(), actorId, "CREATE_SHARED_RESOURCE",
                idempotencyKey, digest, HttpStatus.CREATED.value(), created);
        return created;
    }

    @Transactional
    public void linkSharedResourceConsumer(UUID actorId, UUID sharedAssetId, LinkSharedResourceConsumerRequest req) {
        repository.linkSharedResourceConsumer(sharedAssetId, req, actorId);
    }

    private <T> T replayOrConflict(CatalogRepository.StoredIdempotency stored, String digest, Class<T> type) {
        if (!stored.requestDigest().equals(digest)) {
            throw new CatalogDomainException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                    "Idempotency key reused with changed request payload");
        }
        return CanonicalJson.fromJson(stored.responseJson(), type);
    }
}
