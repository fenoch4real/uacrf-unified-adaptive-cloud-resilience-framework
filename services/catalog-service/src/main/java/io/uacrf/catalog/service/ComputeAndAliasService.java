package io.uacrf.catalog.service;

import io.uacrf.catalog.api.CatalogDomainException;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Tracks resilience-focused Cloud Compute Placement & Scaling Bounds across GCP/AWS/Azure/OCI/K8s
 * plus Provider Resource Aliases (FR-CAT-003) without duplicating raw vendor hardware SKUs.
 */
@Service
public class ComputeAndAliasService {

    private final JdbcTemplate jdbc;

    public ComputeAndAliasService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ComputePlacementDto> PLACEMENT_MAPPER = (rs, rowNum) -> new ComputePlacementDto(
            rs.getObject("placement_id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("environment_id", UUID.class),
            rs.getString("service_canonical_id"),
            rs.getString("cloud_provider"),
            rs.getString("runtime_kind"),
            rs.getString("region"),
            rs.getString("availability_zone"),
            rs.getString("failure_domain"),
            rs.getString("cluster_or_pool"),
            rs.getInt("min_capacity"),
            rs.getInt("max_capacity"),
            rs.getInt("current_capacity"),
            rs.getBoolean("autoscaling_enabled"),
            rs.getTimestamp("created_at").toInstant()
    );

    private static final RowMapper<ProviderAliasDto> ALIAS_MAPPER = (rs, rowNum) -> new ProviderAliasDto(
            rs.getObject("alias_id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("environment_id", UUID.class),
            rs.getString("service_canonical_id"),
            rs.getString("cloud_provider"),
            rs.getString("provider_resource_id"),
            rs.getBigDecimal("confidence"),
            rs.getString("source_system"),
            rs.getTimestamp("observed_at").toInstant()
    );

    public List<ComputePlacementDto> listPlacements(UUID tenantId, UUID environmentId) {
        return jdbc.query("""
                SELECT * FROM catalog.compute_placement
                WHERE tenant_id = ? AND environment_id = ?
                ORDER BY cloud_provider, service_canonical_id
                """, PLACEMENT_MAPPER, tenantId, environmentId);
    }

    @Transactional
    public ComputePlacementDto registerPlacement(RegisterComputePlacementRequest req) {
        if (req.maxCapacity() < req.minCapacity()) {
            throw new CatalogDomainException(HttpStatus.BAD_REQUEST, "INVALID_CAPACITY_BOUNDS",
                    "maxCapacity must be >= minCapacity");
        }
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO catalog.compute_placement (
                    placement_id, tenant_id, environment_id, service_canonical_id, cloud_provider, runtime_kind,
                    region, availability_zone, failure_domain, cluster_or_pool, min_capacity, max_capacity,
                    current_capacity, autoscaling_enabled
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, req.tenantId(), req.environmentId(), req.serviceCanonicalId(),
                req.cloudProvider(), req.runtimeKind(), req.region(), req.availabilityZone(),
                req.failureDomain(), req.clusterOrPool(), req.minCapacity(), req.maxCapacity(),
                req.currentCapacity(), req.autoscalingEnabled());
        return new ComputePlacementDto(id, req.tenantId(), req.environmentId(), req.serviceCanonicalId(),
                req.cloudProvider(), req.runtimeKind(), req.region(), req.availabilityZone(),
                req.failureDomain(), req.clusterOrPool(), req.minCapacity(), req.maxCapacity(),
                req.currentCapacity(), req.autoscalingEnabled(), now);
    }

    public List<ProviderAliasDto> listAliases(UUID tenantId, UUID environmentId) {
        return jdbc.query("""
                SELECT * FROM catalog.provider_resource_alias
                WHERE tenant_id = ? AND environment_id = ?
                ORDER BY observed_at DESC
                """, ALIAS_MAPPER, tenantId, environmentId);
    }

    @Transactional
    public ProviderAliasDto registerAlias(RegisterProviderAliasRequest req) {
        var conflicting = jdbc.query("""
                SELECT service_canonical_id FROM catalog.provider_resource_alias
                WHERE tenant_id = ? AND environment_id = ? AND cloud_provider = ? AND provider_resource_id = ?
                """, (rs, n) -> rs.getString("service_canonical_id"),
                req.tenantId(), req.environmentId(), req.cloudProvider(), req.providerResourceId());
        if (!conflicting.isEmpty() && !conflicting.getFirst().equals(req.serviceCanonicalId())) {
            throw new CatalogDomainException(HttpStatus.CONFLICT, "ALIAS_CONFLICT",
                    "Provider resource ID [" + req.providerResourceId() + "] is already bound to " + conflicting.getFirst());
        }
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        BigDecimal conf = req.confidence() != null ? req.confidence() : BigDecimal.ONE;
        jdbc.update("""
                INSERT INTO catalog.provider_resource_alias (
                    alias_id, tenant_id, environment_id, service_canonical_id, cloud_provider,
                    provider_resource_id, confidence, source_system
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, environment_id, cloud_provider, provider_resource_id)
                DO UPDATE SET confidence = EXCLUDED.confidence, observed_at = now()
                """, id, req.tenantId(), req.environmentId(), req.serviceCanonicalId(),
                req.cloudProvider(), req.providerResourceId(), conf, req.sourceSystem());
        return new ProviderAliasDto(id, req.tenantId(), req.environmentId(), req.serviceCanonicalId(),
                req.cloudProvider(), req.providerResourceId(), conf, req.sourceSystem(), now);
    }

    public record RegisterComputePlacementRequest(
            @NotNull UUID tenantId, @NotNull UUID environmentId,
            @NotBlank String serviceCanonicalId, @NotBlank String cloudProvider,
            @NotBlank String runtimeKind, @NotBlank String region,
            @NotBlank String availabilityZone, @NotBlank String failureDomain,
            @NotBlank String clusterOrPool, @Min(0) int minCapacity,
            @Min(1) int maxCapacity, @Min(0) int currentCapacity, boolean autoscalingEnabled
    ) {
    }

    public record ComputePlacementDto(
            UUID placementId, UUID tenantId, UUID environmentId,
            String serviceCanonicalId, String cloudProvider, String runtimeKind,
            String region, String availabilityZone, String failureDomain, String clusterOrPool,
            int minCapacity, int maxCapacity, int currentCapacity, boolean autoscalingEnabled, Instant createdAt
    ) {
    }

    public record RegisterProviderAliasRequest(
            @NotNull UUID tenantId, @NotNull UUID environmentId,
            @NotBlank String serviceCanonicalId, @NotBlank String cloudProvider,
            @NotBlank String providerResourceId, BigDecimal confidence, @NotBlank String sourceSystem
    ) {
    }

    public record ProviderAliasDto(
            UUID aliasId, UUID tenantId, UUID environmentId,
            String serviceCanonicalId, String cloudProvider, String providerResourceId,
            BigDecimal confidence, String sourceSystem, Instant observedAt
    ) {
    }
}
