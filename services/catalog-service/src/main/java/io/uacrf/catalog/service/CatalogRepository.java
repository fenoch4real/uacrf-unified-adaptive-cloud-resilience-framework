package io.uacrf.catalog.service;

import io.uacrf.catalog.api.CatalogDomainException;
import io.uacrf.catalog.api.CatalogDtos.*;
import io.uacrf.crypto.CanonicalJson;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class CatalogRepository {

    private final JdbcClient jdbc;

    public CatalogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<ServiceView> listConventionalServices(UUID tenantId, UUID environmentId) {
        return jdbc.sql("""
                SELECT service_id, tenant_id, environment_id, canonical_id, name, domain, description,
                       service_kind, criticality, lifecycle, owner_id, version
                FROM catalog.service
                WHERE tenant_id = :tenantId AND environment_id = :envId AND service_kind = 'CONVENTIONAL'
                ORDER BY name
                """)
                .param("tenantId", tenantId)
                .param("envId", environmentId)
                .query((rs, rowNum) -> mapService(rs))
                .list();
    }

    public Optional<ServiceView> findService(UUID tenantId, UUID environmentId, UUID serviceId) {
        return jdbc.sql("""
                SELECT service_id, tenant_id, environment_id, canonical_id, name, domain, description,
                       service_kind, criticality, lifecycle, owner_id, version
                FROM catalog.service
                WHERE tenant_id = :tenantId AND environment_id = :envId AND service_id = :serviceId
                """)
                .param("tenantId", tenantId)
                .param("envId", environmentId)
                .param("serviceId", serviceId)
                .query((rs, rowNum) -> mapService(rs))
                .optional();
    }

    public ServiceView insertService(CreateServiceRequest req, UUID actorId, String serviceKind) {
        UUID serviceId = UUID.randomUUID();
        String canonicalId = "service:" + req.name().toLowerCase().replaceAll("[^a-z0-9-]+", "-");
        jdbc.sql("""
                INSERT INTO catalog.service
                  (service_id, tenant_id, environment_id, canonical_id, name, domain, description,
                   service_kind, criticality, lifecycle, owner_id)
                VALUES
                  (:id, :tenantId, :envId, :canonicalId, :name, :domain, :desc, :kind, :crit, 'ACTIVE', :ownerId)
                """)
                .param("id", serviceId)
                .param("tenantId", req.tenantId())
                .param("envId", req.environmentId())
                .param("canonicalId", canonicalId)
                .param("name", req.name())
                .param("domain", req.domain())
                .param("desc", req.description())
                .param("kind", serviceKind)
                .param("crit", req.criticality())
                .param("ownerId", req.ownerId())
                .update();

        ServiceView view = new ServiceView(serviceId, req.tenantId(), req.environmentId(), canonicalId,
                req.name(), req.domain(), req.description(), serviceKind, req.criticality(), "ACTIVE", req.ownerId(), 1L);
        recordAuditAndOutbox(req.tenantId(), req.environmentId(), actorId, "SERVICE_REGISTERED",
                "SERVICE", serviceId, "Service registered", view, 1L);
        return view;
    }

    public List<AgentWorkloadView> listAgentWorkloads(UUID tenantId, UUID environmentId, UUID parentServiceId) {
        String sql = """
                SELECT workload_profile_id, tenant_id, environment_id, service_anchor_id, parent_service_id,
                       name, workload_kind, intended_purpose, owner_id, runtime_principal_id,
                       criticality, active_version, active_digest, lifecycle, version
                FROM catalog.agent_workload_profile
                WHERE tenant_id = :tenantId AND environment_id = :envId
                """ + (parentServiceId != null ? " AND parent_service_id = :parentServiceId " : "") + " ORDER BY name";
        var spec = jdbc.sql(sql).param("tenantId", tenantId).param("envId", environmentId);
        if (parentServiceId != null) {
            spec = spec.param("parentServiceId", parentServiceId);
        }
        return spec.query((rs, rowNum) -> mapAgentWorkload(rs)).list();
    }

    public Optional<AgentWorkloadView> findAgentWorkloadForUpdate(UUID tenantId, UUID environmentId, UUID profileId) {
        return jdbc.sql("""
                SELECT workload_profile_id, tenant_id, environment_id, service_anchor_id, parent_service_id,
                       name, workload_kind, intended_purpose, owner_id, runtime_principal_id,
                       criticality, active_version, active_digest, lifecycle, version
                FROM catalog.agent_workload_profile
                WHERE tenant_id = :tenantId AND environment_id = :envId AND workload_profile_id = :profileId
                FOR UPDATE
                """)
                .param("tenantId", tenantId)
                .param("envId", environmentId)
                .param("profileId", profileId)
                .query((rs, rowNum) -> mapAgentWorkload(rs))
                .optional();
    }

    public AgentWorkloadView insertAgentWorkload(RegisterAgentWorkloadRequest req, UUID serviceAnchorId, UUID actorId) {
        UUID profileId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.agent_workload_profile
                  (workload_profile_id, tenant_id, environment_id, service_anchor_id, parent_service_id,
                   name, workload_kind, intended_purpose, owner_id, runtime_principal_id, criticality, lifecycle)
                VALUES
                  (:id, :tenantId, :envId, :anchorId, :parentId, :name, :kind, :purpose, :ownerId, :runtimeId, :crit, 'PROPOSED')
                """)
                .param("id", profileId)
                .param("tenantId", req.tenantId())
                .param("envId", req.environmentId())
                .param("anchorId", serviceAnchorId)
                .param("parentId", req.parentServiceId())
                .param("name", req.name())
                .param("kind", req.workloadKind())
                .param("purpose", req.intendedPurpose())
                .param("ownerId", req.ownerId())
                .param("runtimeId", req.runtimePrincipalId())
                .param("crit", req.criticality())
                .update();

        AgentWorkloadView view = new AgentWorkloadView(profileId, req.tenantId(), req.environmentId(),
                serviceAnchorId, req.parentServiceId(), req.name(), req.workloadKind(), req.intendedPurpose(),
                req.ownerId(), req.runtimePrincipalId(), req.criticality(), null, null, "PROPOSED", 1L);
        recordAuditAndOutbox(req.tenantId(), req.environmentId(), actorId, "AGENT_WORKLOAD_REGISTERED",
                "AGENT_WORKLOAD", profileId, "Protected agent workload registered", view, 1L);
        return view;
    }

    public WorkloadVersionView insertWorkloadVersion(UUID profileId, RegisterWorkloadVersionRequest req,
                                                     UUID authorId, String digest) {
        long nextVer = jdbc.sql("""
                SELECT COALESCE(MAX(version_number), 0) + 1
                FROM catalog.agent_workload_version
                WHERE workload_profile_id = :profileId
                """)
                .param("profileId", profileId)
                .query(Long.class)
                .single();

        UUID verId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.agent_workload_version
                  (workload_version_id, workload_profile_id, tenant_id, environment_id, version_number, manifest, manifest_digest, author_id, status)
                VALUES
                  (:id, :profileId, :tenantId, :envId, :verNum, CAST(:manifest AS jsonb), :digest, :authorId, 'DRAFT')
                """)
                .param("id", verId)
                .param("profileId", profileId)
                .param("tenantId", req.tenantId())
                .param("envId", req.environmentId())
                .param("verNum", nextVer)
                .param("manifest", toJson(req.manifest()))
                .param("digest", digest)
                .param("authorId", authorId)
                .update();

        WorkloadVersionView view = new WorkloadVersionView(verId, profileId, nextVer, digest, authorId, "DRAFT", null, null);
        recordAuditAndOutbox(req.tenantId(), req.environmentId(), authorId, "AGENT_WORKLOAD_VERSION_CREATED",
                "AGENT_WORKLOAD", profileId, "Immutable workload version created", view, nextVer);
        return view;
    }

    public WorkloadVersionView activateWorkloadVersion(AgentWorkloadView profile, long versionNumber,
                                                       UUID activatorId, Instant now) {
        WorkloadVersionView target = jdbc.sql("""
                SELECT workload_version_id, workload_profile_id, version_number, manifest_digest, author_id, status, activated_by, activated_at
                FROM catalog.agent_workload_version
                WHERE workload_profile_id = :profileId AND version_number = :verNum
                FOR UPDATE
                """)
                .param("profileId", profile.workloadProfileId())
                .param("verNum", versionNumber)
                .query((rs, rowNum) -> new WorkloadVersionView(
                        rs.getObject("workload_version_id", UUID.class),
                        rs.getObject("workload_profile_id", UUID.class),
                        rs.getLong("version_number"),
                        rs.getString("manifest_digest"),
                        rs.getObject("author_id", UUID.class),
                        rs.getString("status"),
                        rs.getObject("activated_by", UUID.class),
                        rs.getTimestamp("activated_at") == null ? null : rs.getTimestamp("activated_at").toInstant()
                ))
                .optional()
                .orElseThrow(() -> new CatalogDomainException(HttpStatus.NOT_FOUND, "WORKLOAD_VERSION_NOT_FOUND",
                        "Workload version not found"));

        if (activatorId.equals(target.authorId())) {
            throw new CatalogDomainException(HttpStatus.FORBIDDEN, "SELF_ACTIVATION_PROHIBITED",
                    "Workload version activator must differ from manifest author");
        }

        jdbc.sql("""
                UPDATE catalog.agent_workload_version
                SET status = 'SUPERSEDED'
                WHERE workload_profile_id = :profileId AND status = 'ACTIVE'
                """)
                .param("profileId", profile.workloadProfileId())
                .update();

        jdbc.sql("""
                UPDATE catalog.agent_workload_version
                SET status = 'ACTIVE', activated_by = :activatorId, activated_at = :now
                WHERE workload_version_id = :verId
                """)
                .param("activatorId", activatorId)
                .param("now", Timestamp.from(now))
                .param("verId", target.workloadVersionId())
                .update();

        long nextProfileVer = profile.version() + 1;
        jdbc.sql("""
                UPDATE catalog.agent_workload_profile
                SET active_version = :verNum, active_digest = :digest, lifecycle = 'ACTIVE',
                    version = :nextVer, updated_at = :now
                WHERE workload_profile_id = :profileId
                """)
                .param("verNum", versionNumber)
                .param("digest", target.manifestDigest())
                .param("nextVer", nextProfileVer)
                .param("now", Timestamp.from(now))
                .param("profileId", profile.workloadProfileId())
                .update();

        WorkloadVersionView activated = new WorkloadVersionView(target.workloadVersionId(), target.workloadProfileId(),
                target.versionNumber(), target.manifestDigest(), target.authorId(), "ACTIVE", activatorId, now);
        recordAuditAndOutbox(profile.tenantId(), profile.environmentId(), activatorId, "AGENT_WORKLOAD_VERSION_ACTIVATED",
                "AGENT_WORKLOAD", profile.workloadProfileId(), "Activated version " + versionNumber, activated, nextProfileVer);
        return activated;
    }

    public List<JourneyView> listJourneys(UUID tenantId, UUID environmentId) {
        return jdbc.sql("""
                SELECT journey_id, tenant_id, environment_id, name, purpose, owner_id, operating_window, lifecycle, version
                FROM catalog.customer_journey
                WHERE tenant_id = :tenantId AND environment_id = :envId
                ORDER BY name
                """)
                .param("tenantId", tenantId)
                .param("envId", environmentId)
                .query((rs, rowNum) -> mapJourney(rs))
                .list();
    }

    public Optional<JourneyView> findJourney(UUID tenantId, UUID environmentId, UUID journeyId) {
        return jdbc.sql("""
                SELECT journey_id, tenant_id, environment_id, name, purpose, owner_id, operating_window, lifecycle, version
                FROM catalog.customer_journey
                WHERE tenant_id = :tenantId AND environment_id = :envId AND journey_id = :journeyId
                """)
                .param("tenantId", tenantId)
                .param("envId", environmentId)
                .param("journeyId", journeyId)
                .query((rs, rowNum) -> mapJourney(rs))
                .optional();
    }

    public JourneyView insertJourney(CreateJourneyRequest req, UUID actorId) {
        UUID id = UUID.randomUUID();
        String window = req.operatingWindow() == null || req.operatingWindow().isBlank() ? "24x7" : req.operatingWindow();
        jdbc.sql("""
                INSERT INTO catalog.customer_journey
                  (journey_id, tenant_id, environment_id, name, purpose, owner_id, operating_window, lifecycle)
                VALUES
                  (:id, :tenantId, :envId, :name, :purpose, :ownerId, :window, 'ACTIVE')
                """)
                .param("id", id)
                .param("tenantId", req.tenantId())
                .param("envId", req.environmentId())
                .param("name", req.name())
                .param("purpose", req.purpose())
                .param("ownerId", req.ownerId())
                .param("window", window)
                .update();

        JourneyView view = new JourneyView(id, req.tenantId(), req.environmentId(), req.name(),
                req.purpose(), req.ownerId(), window, "ACTIVE", 1L);
        recordAuditAndOutbox(req.tenantId(), req.environmentId(), actorId, "JOURNEY_REGISTERED",
                "CUSTOMER_JOURNEY", id, "Customer journey registered", view, 1L);
        return view;
    }

    public JourneyStepView insertJourneyStep(UUID journeyId, AddJourneyStepRequest req, UUID actorId) {
        UUID stepId = UUID.randomUUID();
        String behavior = req.failureBehavior() == null || req.failureBehavior().isBlank() ? "DEGRADE" : req.failureBehavior();
        int timeout = req.timeoutSeconds() > 0 ? req.timeoutSeconds() : 30;
        jdbc.sql("""
                INSERT INTO catalog.journey_step
                  (step_id, journey_id, tenant_id, environment_id, sequence_num, name, success_condition, timeout_seconds, failure_behavior)
                VALUES
                  (:id, :journeyId, :tenantId, :envId, :seq, :name, :success, :timeout, :behavior)
                """)
                .param("id", stepId)
                .param("journeyId", journeyId)
                .param("tenantId", req.tenantId())
                .param("envId", req.environmentId())
                .param("seq", req.sequenceNum())
                .param("name", req.name())
                .param("success", req.successCondition())
                .param("timeout", timeout)
                .param("behavior", behavior)
                .update();

        JourneyStepView view = new JourneyStepView(stepId, journeyId, req.sequenceNum(), req.name(),
                req.successCondition(), timeout, behavior);
        recordAuditAndOutbox(req.tenantId(), req.environmentId(), actorId, "JOURNEY_STEP_ADDED",
                "CUSTOMER_JOURNEY", journeyId, "Journey step added: " + req.name(), view, req.sequenceNum());
        return view;
    }

    public List<JourneyStepView> listJourneySteps(UUID journeyId) {
        return jdbc.sql("""
                SELECT step_id, journey_id, sequence_num, name, success_condition, timeout_seconds, failure_behavior
                FROM catalog.journey_step
                WHERE journey_id = :journeyId
                ORDER BY sequence_num
                """)
                .param("journeyId", journeyId)
                .query((rs, rowNum) -> new JourneyStepView(
                        rs.getObject("step_id", UUID.class),
                        rs.getObject("journey_id", UUID.class),
                        rs.getInt("sequence_num"),
                        rs.getString("name"),
                        rs.getString("success_condition"),
                        rs.getInt("timeout_seconds"),
                        rs.getString("failure_behavior")
                ))
                .list();
    }

    public JourneyCapabilityView insertJourneyCapability(UUID journeyId, LinkJourneyCapabilityRequest req, UUID actorId) {
        UUID linkId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.journey_capability
                  (link_id, journey_id, step_id, tenant_id, environment_id, capability_kind, target_id,
                   participation_criticality, rationale, evidence_ref, linked_by)
                VALUES
                  (:id, :journeyId, :stepId, :tenantId, :envId, :kind, :targetId, :crit, :rationale, :evRef, :actorId)
                """)
                .param("id", linkId)
                .param("journeyId", journeyId)
                .param("stepId", req.stepId())
                .param("tenantId", req.tenantId())
                .param("envId", req.environmentId())
                .param("kind", req.capabilityKind())
                .param("targetId", req.targetId())
                .param("crit", req.participationCriticality())
                .param("rationale", req.rationale())
                .param("evRef", req.evidenceRef())
                .param("actorId", actorId)
                .update();

        JourneyCapabilityView view = new JourneyCapabilityView(linkId, journeyId, req.stepId(),
                req.capabilityKind(), req.targetId(), req.participationCriticality(), req.rationale(), req.evidenceRef());
        recordAuditAndOutbox(req.tenantId(), req.environmentId(), actorId, "JOURNEY_CAPABILITY_LINKED",
                "CUSTOMER_JOURNEY", journeyId, req.rationale(), view, System.currentTimeMillis());
        return view;
    }

    public List<JourneyCapabilityView> listJourneyCapabilities(UUID journeyId) {
        return jdbc.sql("""
                SELECT link_id, journey_id, step_id, capability_kind, target_id, participation_criticality, rationale, evidence_ref
                FROM catalog.journey_capability
                WHERE journey_id = :journeyId AND retired_at IS NULL
                ORDER BY created_at
                """)
                .param("journeyId", journeyId)
                .query((rs, rowNum) -> new JourneyCapabilityView(
                        rs.getObject("link_id", UUID.class),
                        rs.getObject("journey_id", UUID.class),
                        rs.getObject("step_id", UUID.class),
                        rs.getString("capability_kind"),
                        rs.getObject("target_id", UUID.class),
                        rs.getString("participation_criticality"),
                        rs.getString("rationale"),
                        rs.getString("evidence_ref")
                ))
                .list();
    }

    public ImpactProfileView insertImpactProfileDraft(UUID journeyId, UpsertImpactProfileRequest req,
                                                      UUID authorId, String contentDigest) {
        long nextRevision = jdbc.sql("""
                SELECT COALESCE(MAX(revision), 0) + 1
                FROM catalog.journey_impact_profile
                WHERE journey_id = :journeyId
                """)
                .param("journeyId", journeyId)
                .query(Long.class)
                .single();

        UUID profileId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.journey_impact_profile
                  (profile_id, journey_id, tenant_id, environment_id, revision, impact_if_unavailable,
                   affected_population, criticality_assessment, assessment_status, business_tolerance_value,
                   business_tolerance_unit, business_tolerance_basis, rationale, content_digest, author_id)
                VALUES
                  (:id, :journeyId, :tenantId, :envId, :rev, :impact, :pop, :crit, 'DRAFT',
                   :tolVal, :tolUnit, :tolBasis, :rationale, :digest, :authorId)
                """)
                .param("id", profileId)
                .param("journeyId", journeyId)
                .param("tenantId", req.tenantId())
                .param("envId", req.environmentId())
                .param("rev", nextRevision)
                .param("impact", req.impactIfUnavailable())
                .param("pop", req.affectedPopulation())
                .param("crit", req.criticalityAssessment())
                .param("tolVal", req.businessToleranceValue())
                .param("tolUnit", req.businessToleranceUnit())
                .param("tolBasis", req.businessToleranceBasis())
                .param("rationale", req.rationale())
                .param("digest", contentDigest)
                .param("authorId", authorId)
                .update();

        for (ImpactDimensionInput dim : req.dimensions()) {
            jdbc.sql("""
                    INSERT INTO catalog.journey_impact_dimension (dimension_id, profile_id, dimension_type, description, severity)
                    VALUES (:id, :profileId, :type, :desc, :sev)
                    """)
                    .param("id", UUID.randomUUID())
                    .param("profileId", profileId)
                    .param("type", dim.dimensionType())
                    .param("desc", dim.description())
                    .param("sev", dim.severity())
                    .update();
        }

        ImpactProfileView view = new ImpactProfileView(profileId, journeyId, nextRevision, req.impactIfUnavailable(),
                req.affectedPopulation(), req.criticalityAssessment(), "DRAFT", req.businessToleranceValue(),
                req.businessToleranceUnit(), req.businessToleranceBasis(), req.rationale(), contentDigest,
                authorId, null, null, req.dimensions());
        recordAuditAndOutbox(req.tenantId(), req.environmentId(), authorId, "IMPACT_PROFILE_DRAFTED",
                "JOURNEY_IMPACT_PROFILE", profileId, req.rationale(), view, nextRevision);
        return view;
    }

    public Optional<ImpactProfileView> findImpactProfileForUpdate(UUID tenantId, UUID environmentId, UUID journeyId, long revision) {
        return jdbc.sql("""
                SELECT profile_id, journey_id, revision, impact_if_unavailable, affected_population,
                       criticality_assessment, assessment_status, business_tolerance_value, business_tolerance_unit,
                       business_tolerance_basis, rationale, content_digest, author_id, reviewer_id, review_reason
                FROM catalog.journey_impact_profile
                WHERE tenant_id = :tenantId AND environment_id = :envId AND journey_id = :journeyId AND revision = :rev
                FOR UPDATE
                """)
                .param("tenantId", tenantId)
                .param("envId", environmentId)
                .param("journeyId", journeyId)
                .param("rev", revision)
                .query((rs, rowNum) -> mapImpactProfile(rs, listDimensions(rs.getObject("profile_id", UUID.class))))
                .optional();
    }

    public Optional<ImpactProfileView> findLatestImpactProfile(UUID journeyId) {
        return jdbc.sql("""
                SELECT profile_id, journey_id, revision, impact_if_unavailable, affected_population,
                       criticality_assessment, assessment_status, business_tolerance_value, business_tolerance_unit,
                       business_tolerance_basis, rationale, content_digest, author_id, reviewer_id, review_reason
                FROM catalog.journey_impact_profile
                WHERE journey_id = :journeyId
                ORDER BY revision DESC
                LIMIT 1
                """)
                .param("journeyId", journeyId)
                .query((rs, rowNum) -> mapImpactProfile(rs, listDimensions(rs.getObject("profile_id", UUID.class))))
                .optional();
    }

    public ImpactProfileView updateImpactProfileStatus(UUID tenantId, UUID environmentId, ImpactProfileView current, String newStatus,
                                                       UUID reviewerId, String reviewReason, Instant now) {
        jdbc.sql("""
                UPDATE catalog.journey_impact_profile
                SET assessment_status = :status, reviewer_id = :reviewerId, review_reason = :reason, reviewed_at = :now
                WHERE profile_id = :id
                """)
                .param("status", newStatus)
                .param("reviewerId", reviewerId)
                .param("reason", reviewReason)
                .param("now", reviewerId == null ? null : Timestamp.from(now))
                .param("id", current.profileId())
                .update();

        ImpactProfileView updated = new ImpactProfileView(current.profileId(), current.journeyId(), current.revision(),
                current.impactIfUnavailable(), current.affectedPopulation(), current.criticalityAssessment(), newStatus,
                current.businessToleranceValue(), current.businessToleranceUnit(), current.businessToleranceBasis(),
                current.rationale(), current.contentDigest(), current.authorId(), reviewerId, reviewReason, current.dimensions());
        recordAuditAndOutbox(tenantId, environmentId, reviewerId == null ? current.authorId() : reviewerId,
                "IMPACT_PROFILE_" + newStatus, "JOURNEY_IMPACT_PROFILE", current.profileId(),
                reviewReason == null ? "Submitted for review" : reviewReason, updated, current.revision() + 1000L);
        return updated;
    }

    public List<SharedResourceView> listSharedResources(UUID tenantId, UUID environmentId) {
        return jdbc.sql("""
                SELECT shared_asset_id, tenant_id, environment_id, name, asset_kind, classification, owner_id, lifecycle, version
                FROM catalog.shared_resource
                WHERE tenant_id = :tenantId AND environment_id = :envId
                ORDER BY name
                """)
                .param("tenantId", tenantId)
                .param("envId", environmentId)
                .query((rs, rowNum) -> new SharedResourceView(
                        rs.getObject("shared_asset_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("environment_id", UUID.class),
                        rs.getString("name"),
                        rs.getString("asset_kind"),
                        rs.getString("classification"),
                        rs.getObject("owner_id", UUID.class),
                        rs.getString("lifecycle"),
                        rs.getLong("version")
                ))
                .list();
    }

    public SharedResourceView insertSharedResource(CreateSharedResourceRequest req, UUID actorId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.shared_resource
                  (shared_asset_id, tenant_id, environment_id, name, asset_kind, classification, owner_id, lifecycle)
                VALUES
                  (:id, :tenantId, :envId, :name, :kind, :cls, :ownerId, 'ACTIVE')
                """)
                .param("id", id)
                .param("tenantId", req.tenantId())
                .param("envId", req.environmentId())
                .param("name", req.name())
                .param("kind", req.assetKind())
                .param("cls", req.classification())
                .param("ownerId", req.ownerId())
                .update();

        SharedResourceView view = new SharedResourceView(id, req.tenantId(), req.environmentId(),
                req.name(), req.assetKind(), req.classification(), req.ownerId(), "ACTIVE", 1L);
        recordAuditAndOutbox(req.tenantId(), req.environmentId(), actorId, "SHARED_RESOURCE_REGISTERED",
                "SHARED_RESOURCE", id, "Shared resource registered", view, 1L);
        return view;
    }

    public void linkSharedResourceConsumer(UUID sharedAssetId, LinkSharedResourceConsumerRequest req, UUID actorId) {
        UUID linkId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.capability_asset
                  (link_id, shared_asset_id, tenant_id, environment_id, consumer_kind, consumer_id, usage_role, rationale, linked_by)
                VALUES
                  (:id, :assetId, :tenantId, :envId, :kind, :consumerId, :role, :rationale, :actorId)
                """)
                .param("id", linkId)
                .param("assetId", sharedAssetId)
                .param("tenantId", req.tenantId())
                .param("envId", req.environmentId())
                .param("kind", req.consumerKind())
                .param("consumerId", req.consumerId())
                .param("role", req.usageRole())
                .param("rationale", req.rationale())
                .param("actorId", actorId)
                .update();

        recordAuditAndOutbox(req.tenantId(), req.environmentId(), actorId, "SHARED_RESOURCE_CONSUMER_LINKED",
                "SHARED_RESOURCE", sharedAssetId, req.rationale(), req, System.currentTimeMillis());
    }

    public Optional<StoredIdempotency> findIdempotency(UUID tenantId, UUID environmentId, UUID actorId,
                                                       String operation, String key) {
        return jdbc.sql("""
                SELECT request_digest, response_status, response_body::text
                FROM catalog.idempotency_record
                WHERE tenant_id = :tenantId AND environment_id = :envId AND actor_id = :actorId
                  AND operation = :op AND idempotency_key = :key
                """)
                .param("tenantId", tenantId)
                .param("envId", environmentId)
                .param("actorId", actorId)
                .param("op", operation)
                .param("key", key)
                .query((rs, rowNum) -> new StoredIdempotency(
                        rs.getString("request_digest"),
                        rs.getInt("response_status"),
                        rs.getString("response_body")
                ))
                .optional();
    }

    public void saveIdempotency(UUID tenantId, UUID environmentId, UUID actorId, String operation,
                                String key, String digest, int status, Object body) {
        jdbc.sql("""
                INSERT INTO catalog.idempotency_record
                  (tenant_id, environment_id, actor_id, operation, idempotency_key, request_digest, response_status, response_body)
                VALUES
                  (:tenantId, :envId, :actorId, :op, :key, :digest, :status, CAST(:body AS jsonb))
                """)
                .param("tenantId", tenantId)
                .param("envId", environmentId)
                .param("actorId", actorId)
                .param("op", operation)
                .param("key", key)
                .param("digest", digest)
                .param("status", status)
                .param("body", toJson(body))
                .update();
    }

    private List<ImpactDimensionInput> listDimensions(UUID profileId) {
        return jdbc.sql("""
                SELECT dimension_type, description, severity
                FROM catalog.journey_impact_dimension
                WHERE profile_id = :profileId
                ORDER BY dimension_type
                """)
                .param("profileId", profileId)
                .query((rs, rowNum) -> new ImpactDimensionInput(
                        rs.getString("dimension_type"),
                        rs.getString("description"),
                        rs.getString("severity")
                ))
                .list();
    }

    private void recordAuditAndOutbox(UUID tenantId, UUID environmentId, UUID actorId, String action,
                                      String entityType, UUID entityId, String reason,
                                      Object afterObj, long aggregateVersion) {
        String json = toJson(afterObj);
        String digest = CanonicalJson.digestObject(afterObj);
        jdbc.sql("""
                INSERT INTO catalog.audit_event
                  (audit_event_id, tenant_id, environment_id, actor_id, action, entity_type, entity_id, reason, after_digest)
                VALUES
                  (:auditId, :tenantId, :envId, :actorId, :action, :entityType, :entityId, :reason, :digest)
                """)
                .param("auditId", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("envId", environmentId)
                .param("actorId", actorId)
                .param("action", action)
                .param("entityType", entityType)
                .param("entityId", entityId)
                .param("reason", reason)
                .param("digest", digest)
                .update();

        jdbc.sql("""
                INSERT INTO integration.outbox_event
                  (outbox_event_id, tenant_id, aggregate_type, aggregate_id, aggregate_version, event_type, payload, payload_digest)
                VALUES
                  (:outboxId, :tenantId, :aggType, :aggId, :aggVer, :eventType, CAST(:payload AS jsonb), :digest)
                """)
                .param("outboxId", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("aggType", entityType)
                .param("aggId", entityId)
                .param("aggVer", aggregateVersion)
                .param("eventType", action)
                .param("payload", json)
                .param("digest", digest)
                .update();
    }

    private String toJson(Object value) {
        return CanonicalJson.toJson(value);
    }

    private static ServiceView mapService(ResultSet rs) throws SQLException {
        return new ServiceView(
                rs.getObject("service_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("environment_id", UUID.class),
                rs.getString("canonical_id"),
                rs.getString("name"),
                rs.getString("domain"),
                rs.getString("description"),
                rs.getString("service_kind"),
                rs.getString("criticality"),
                rs.getString("lifecycle"),
                rs.getObject("owner_id", UUID.class),
                rs.getLong("version")
        );
    }

    private static AgentWorkloadView mapAgentWorkload(ResultSet rs) throws SQLException {
        Long activeVer = rs.getObject("active_version") == null ? null : rs.getLong("active_version");
        return new AgentWorkloadView(
                rs.getObject("workload_profile_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("environment_id", UUID.class),
                rs.getObject("service_anchor_id", UUID.class),
                rs.getObject("parent_service_id", UUID.class),
                rs.getString("name"),
                rs.getString("workload_kind"),
                rs.getString("intended_purpose"),
                rs.getObject("owner_id", UUID.class),
                rs.getObject("runtime_principal_id", UUID.class),
                rs.getString("criticality"),
                activeVer,
                rs.getString("active_digest"),
                rs.getString("lifecycle"),
                rs.getLong("version")
        );
    }

    private static JourneyView mapJourney(ResultSet rs) throws SQLException {
        return new JourneyView(
                rs.getObject("journey_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("environment_id", UUID.class),
                rs.getString("name"),
                rs.getString("purpose"),
                rs.getObject("owner_id", UUID.class),
                rs.getString("operating_window"),
                rs.getString("lifecycle"),
                rs.getLong("version")
        );
    }

    private static ImpactProfileView mapImpactProfile(ResultSet rs, List<ImpactDimensionInput> dims) throws SQLException {
        return new ImpactProfileView(
                rs.getObject("profile_id", UUID.class),
                rs.getObject("journey_id", UUID.class),
                rs.getLong("revision"),
                rs.getString("impact_if_unavailable"),
                rs.getString("affected_population"),
                rs.getString("criticality_assessment"),
                rs.getString("assessment_status"),
                rs.getBigDecimal("business_tolerance_value"),
                rs.getString("business_tolerance_unit"),
                rs.getString("business_tolerance_basis"),
                rs.getString("rationale"),
                rs.getString("content_digest"),
                rs.getObject("author_id", UUID.class),
                rs.getObject("reviewer_id", UUID.class),
                rs.getString("review_reason"),
                dims
        );
    }

    public record StoredIdempotency(String requestDigest, int status, String responseJson) {
    }
}
