package org.uacrf.identity;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class IdentityExchangeService {
  private static final Set<String> FIELDS =
      Set.of("audience", "resource_type", "resource_id", "purpose", "operation_deadline");
  private static final Set<String> GRAPH_OPERATION_PURPOSES =
      Set.of("topology.graph.subgraph-selection", "topology.graph.difference-classification");
  private static final Set<String> OBJECTIVE_WORKLOAD_RECEIPT_PURPOSES =
      Set.of("objectives.objective.proposal", "objectives.objective.activation");
  private static final Duration BUDGET = Duration.ofSeconds(5);

  private final JdbcTemplate jdbc;
  private final JwtEncoder encoder;
  private final String issuer;
  private final String keyId;
  private final Clock clock;

  @Autowired
  IdentityExchangeService(
      JdbcTemplate jdbc,
      @Qualifier("delegationJwtEncoder") JwtEncoder encoder,
      @Value("${uacrf.delegation.issuer}") String issuer,
      @Value("${uacrf.delegation.key-id}") String keyId) {
    this(jdbc, encoder, issuer, keyId, Clock.systemUTC());
  }

  IdentityExchangeService(
      JdbcTemplate jdbc, JwtEncoder encoder, String issuer, String keyId, Clock clock) {
    this.jdbc = jdbc;
    this.encoder = encoder;
    this.issuer = issuer;
    this.keyId = keyId;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  IdentityResponses.Delegation exchange(Jwt assertion, Map<String, Object> request) {
    if (request == null || !request.keySet().equals(FIELDS)) {
      throw invalid("The exchange requires exactly the five contract fields.");
    }
    String audience = text(request, "audience");
    String resourceType = text(request, "resource_type");
    String resourceId = text(request, "resource_id");
    String purpose = text(request, "purpose");
    String expectedCallerAudience =
        OBJECTIVE_WORKLOAD_RECEIPT_PURPOSES.contains(purpose) ? "S05" : "S06";
    if (!List.of(expectedCallerAudience).equals(assertion.getAudience())) {
      throw denied();
    }
    if (!resourceId.matches("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$")) {
      throw invalid("The resource id is malformed.");
    }
    boolean graphOperation = GRAPH_OPERATION_PURPOSES.contains(purpose);
    String downstreamCapability;
    if (graphOperation) {
      downstreamCapability = switch (audience + "/" + resourceType) {
        case "S02/service" -> "catalog.service.read";
        case "S04/evidence_event" -> "evidence.topology.summary";
        case "S05/agent_workload" -> "objectives.objective.read";
        case "S07/assessment_case" -> "assessment.case.read";
        default -> throw invalid("The audience and resource type are not supported for this purpose.");
      };
    } else if ("S02".equals(audience) && "agent_workload".equals(resourceType)) {
      downstreamCapability = "catalog.agent.owner-receipt";
    } else if ("S04".equals(audience) && "evidence_event".equals(resourceType)) {
      downstreamCapability = "evidence.topology.summary";
    } else {
      throw invalid("The audience and resource type are not supported.");
    }
    String upstreamCapability = switch (purpose) {
      case "topology.dependency-proposal" -> "topology.proposal.write";
      case "topology.dependency-review" -> "topology.proposal.review";
      case "topology.graph.current-use" -> "topology.graph.read";
      case "topology.graph.subgraph-selection", "topology.graph.difference-classification" ->
          "topology.graph.read";
      case "objectives.objective.proposal" -> "objectives.objective.write";
      case "objectives.objective.activation" -> "objectives.objective.activate";
      default -> throw invalid("The purpose is not supported.");
    };
    Instant deadline;
    try {
      deadline = Instant.parse(text(request, "operation_deadline"));
    } catch (DateTimeParseException error) {
      throw invalid("The operation deadline is malformed.");
    }
    Instant now = clock.instant();
    if (!deadline.isAfter(now) || deadline.isAfter(now.plus(BUDGET))) {
      throw invalid("The operation deadline must be within five seconds.");
    }
    Object capabilities = assertion.getClaim("capabilities");
    if (!(capabilities instanceof List<?> list) || !list.contains(upstreamCapability)) {
      throw denied();
    }
    String scope = assertion.getClaimAsString("service_scope");
    if (!"topology.graph.read".equals(upstreamCapability) && !"*".equals(scope)) {
      throw denied();
    }
    Boolean authorized = jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM uacrf_identity.principal p "
            + "JOIN uacrf_identity.identity_provider ip ON ip.issuer_uri = p.issuer_uri "
            + "JOIN uacrf_identity.environment e ON e.tenant_id = ? AND e.id = ? "
            + "JOIN uacrf_identity.tenant t ON t.id = e.tenant_id "
            + "JOIN uacrf_identity.membership m ON m.principal_id = p.id "
            + "AND m.tenant_id = e.tenant_id AND m.environment_id = e.id "
            + "WHERE p.id = ? AND p.issuer_uri = ? AND p.subject = ? "
            + "AND p.enabled = true AND p.token_valid_after <= ? AND ip.status = 'TRUSTED' "
            + "AND e.status = 'ACTIVE' AND t.status = 'ACTIVE' "
            + "AND (p.kind = 'HUMAN' OR (p.kind = 'SYSTEM' AND ? = 'topology.graph.read')) "
            + "AND m.capability = ? AND m.revoked_at IS NULL AND m.expires_at > now() "
            + "AND (m.service_scope = '*' OR m.service_scope = ?))",
        Boolean.class,
        assertion.getClaimAsString("tenant_id"), assertion.getClaimAsString("environment_id"),
        assertion.getSubject(), assertion.getClaimAsString("actor_issuer"),
        assertion.getClaimAsString("actor_subject"), Timestamp.from(assertion.getIssuedAt()),
        upstreamCapability, upstreamCapability, scope);
    if (!Boolean.TRUE.equals(authorized)) {
      throw denied();
    }

    now = clock.instant();
    Instant issuedAt = now.truncatedTo(ChronoUnit.SECONDS);
    Instant expiresAt = issuedAt.plus(BUDGET);
    if (deadline.isBefore(expiresAt)) expiresAt = deadline.truncatedTo(ChronoUnit.SECONDS);
    if (assertion.getExpiresAt().isBefore(expiresAt)) {
      expiresAt = assertion.getExpiresAt().truncatedTo(ChronoUnit.SECONDS);
    }
    if (!expiresAt.isAfter(now)) {
      throw invalid("The operation deadline or incoming assertion has elapsed.");
    }
    JwtClaimsSet claims = JwtClaimsSet.builder()
        .issuer(issuer).subject(assertion.getSubject()).audience(List.of(audience))
        .issuedAt(issuedAt).expiresAt(expiresAt).id(UUID.randomUUID().toString())
        .claim("tenant_id", assertion.getClaimAsString("tenant_id"))
        .claim("environment_id", assertion.getClaimAsString("environment_id"))
        .claim("actor_issuer", assertion.getClaimAsString("actor_issuer"))
        .claim("actor_subject", assertion.getClaimAsString("actor_subject"))
        .claim("service_scope", resourceId)
        .claim("capabilities", List.of(downstreamCapability))
        .claim("eligible_owner_ids", List.of()).claim("runtime_principal_ids", List.of())
        .claim("resource_type", resourceType).claim("resource_id", resourceId)
        .claim("purpose", purpose).build();
    String token = encoder.encode(JwtEncoderParameters.from(
        JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build(), claims)).getTokenValue();
    if (!expiresAt.isAfter(clock.instant())) {
      throw invalid("The operation deadline or incoming assertion has elapsed.");
    }
    return new IdentityResponses.Delegation(
        token, "Bearer", (int) Duration.between(now, expiresAt).toSeconds());
  }

  private static String text(Map<String, Object> request, String name) {
    Object value = request.get(name);
    if (!(value instanceof String text) || text.isBlank()) {
      throw invalid(name + " is required.");
    }
    return text;
  }

  private static IdentityException invalid(String message) {
    return new IdentityException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
  }

  private static IdentityException denied() {
    return new IdentityException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Current service authority is required.");
  }
}
