package org.uacrf.assessment;

import static org.uacrf.assessment.AssessmentRequests.*;
import static org.uacrf.assessment.AssessmentResponses.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.oauth2.jwt.Jwt;
import org.uacrf.security.DelegationPrincipal;
import tools.jackson.databind.ObjectMapper;

@Service
class AssessmentService {
  private static final String DB = "uacrf_assessment.";
  private static final Set<String> GRAPH_RECEIPT_PURPOSES =
      Set.of("topology.graph.subgraph-selection", "topology.graph.difference-classification");
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final ReceiptVerifier receiptVerifier;
  AssessmentService(JdbcTemplate jdbc, ObjectMapper json, ReceiptVerifier receiptVerifier) {
    this.jdbc = jdbc;
    this.json = json;
    this.receiptVerifier = receiptVerifier;
  }

  @Transactional
  Case create(DelegationPrincipal actor, CreateCase request) {
    permit(actor, "assessment.case.write");
    TreeSet<String> kinds = new TreeSet<>(request.requiredSourceKinds());
    if (kinds.size() != request.requiredSourceKinds().size()) throw invalid("Source kinds must be unique.");
    CreateCase content = new CreateCase(request.workloadId(), request.purpose(), request.metric(), request.comparator(),
        canonical(request.threshold()), request.unit(), request.windowSeconds(), request.objectiveRef(), request.graphRef(), List.copyOf(kinds));
    String id = UUID.randomUUID().toString();
    jdbc.update("INSERT INTO " + DB + "analysis_case (tenant_id, environment_id, id, workload_id, content, state, revision, created_by) VALUES (?, ?, ?, ?, ?::jsonb, 'DRAFT', 1, ?)",
        actor.tenantId(), actor.environmentId(), id, content.workloadId(), write(content), actor.principalId());
    return findCase(actor, id, false);
  }
  @Transactional
  Case seal(DelegationPrincipal actor, String id, Transition request) {
    permit(actor, "assessment.case.write");
    Case item = findCase(actor, id, true);
    if (item.revision() != request.expectedRevision()) throw conflict("REVISION_MISMATCH", "The case changed since it was read.");
    if (!item.state().equals("DRAFT")) throw conflict("INVALID_STATE", "Only a draft case can be sealed.");
    String digest = hash(write(content(item)));
    jdbc.update("UPDATE " + DB + "analysis_case SET state='SEALED', revision=revision+1, case_digest=? WHERE tenant_id=? AND environment_id=? AND id=?", digest, actor.tenantId(), actor.environmentId(), id);
    event(actor, "CASE", id, 2, "uacrf.s07.case.sealed.v1", digest, Map.of("workload_id", item.workloadId()));
    return findCase(actor, id, false);
  }
  @Transactional(readOnly=true)
  Case getCase(DelegationPrincipal actor, String id) { permit(actor, "assessment.case.read"); return findCase(actor, id, false); }
  @Transactional(readOnly=true)
  List<Case> listCases(DelegationPrincipal actor, String workloadId) {
    permit(actor, "assessment.case.read");
    return jdbc.query("SELECT * FROM " + DB + "analysis_case WHERE tenant_id=? AND environment_id=? AND (CAST(? AS text) IS NULL OR workload_id=?) ORDER BY id", (rs,n)->caseRow(rs), actor.tenantId(), actor.environmentId(), workloadId, workloadId);
  }
  @Transactional(readOnly=true)
  Receipt receipt(DelegationPrincipal actor, String id, Jwt jwt) {
    permitReceipt(actor, id, jwt);
    Case item = findCase(actor, id, false);
    if (!item.state().equals("SEALED")) throw conflict("INVALID_STATE", "A receipt requires a sealed case.");
    return new Receipt(id, item.purpose(), item.caseDigest(), item.workloadId(), item.requiredSourceKinds());
  }
  @Transactional(readOnly=true)
  CaseMaterial material(DelegationPrincipal actor, String id) {
    permit(actor, "assessment.case.read");
    Case item = findCase(actor, id, false);
    if (!item.state().equals("SEALED")) throw conflict("INVALID_STATE", "Material requires a sealed case.");
    String canonicalText = write(content(item));
    if (!hash(canonicalText).equals(item.caseDigest())) {
      throw conflict("CASE_DIGEST_MISMATCH", "Sealed case material does not match its digest.");
    }
    return new CaseMaterial(id, item.caseDigest(), canonicalText);
  }
  @Transactional
  Evaluation evaluate(DelegationPrincipal actor, String id, Evaluate request) {
    permit(actor, "assessment.evaluation.run");
    Case item = findCase(actor, id, true);
    if (!item.state().equals("SEALED")) throw conflict("INVALID_STATE", "Evaluation requires a sealed case.");
    Instant cutoff = request.observationCutoff();
    Instant start = cutoff.minusSeconds(item.windowSeconds());
    Set<String> ids = new HashSet<>();
    List<Measurement> inputs = new ArrayList<>();
    for (Measurement m : request.measurements()) {
      if (!ids.add(m.sourceReceiptId())) throw invalid("Each source receipt may occur only once per evaluation.");
      inputs.add(new Measurement(m.sourceReceiptId(), m.sourceKind(), m.observedAt(), canonical(m.value()), m.unit()));
    }
    inputs.sort(java.util.Comparator.comparing(Measurement::sourceKind).thenComparing(Measurement::observedAt).thenComparing(Measurement::sourceReceiptId));
    // The window is inclusive; latest observation per required kind wins, with receipt ID breaking ties.
    Map<String, Measurement> selected = new HashMap<>();
    for (Measurement m : inputs) {
      if (item.requiredSourceKinds().contains(m.sourceKind()) && !m.observedAt().isAfter(cutoff) && !m.observedAt().isBefore(start)) selected.put(m.sourceKind(), m);
    }
    List<Evidence> evidence = new ArrayList<>();
    for (Measurement m : inputs) {
      String reason = m.observedAt().isAfter(cutoff) ? "AFTER_CUTOFF" : m.observedAt().isBefore(start) ? "OUTSIDE_WINDOW" :
          !item.requiredSourceKinds().contains(m.sourceKind()) ? "SOURCE_NOT_REQUIRED" : selected.get(m.sourceKind()) != m ? "SUPERSEDED" : "USED";
      evidence.add(new Evidence(m, reason));
    }
    String outcome = "CONFIRMED_SUCCESS";
    if (selected.size() != item.requiredSourceKinds().size() || selected.values().stream().anyMatch(m -> !m.unit().equals(item.unit()))) outcome = "INSUFFICIENT_EVIDENCE";
    else for (Measurement m : selected.values()) {
      int comparison = m.value().compareTo(item.threshold());
      if ((item.comparator() == AssessmentRequests.Comparator.LTE && comparison > 0) || (item.comparator() == AssessmentRequests.Comparator.GTE && comparison < 0)) { outcome = "CONFIRMED_BREACH"; break; }
    }
    ReceiptVerifier.Verification verification =
        receiptVerifier.verify(item, inputs, cutoff, actor.tenantId(), actor.environmentId());
    if (verification.status().equals("FAILED")) outcome = "INSUFFICIENT_EVIDENCE";
    evidence.replaceAll(entry -> {
      String failure = verification.sourceFailures().get(entry.measurement().sourceReceiptId());
      return failure == null ? entry : new Evidence(entry.measurement(), failure);
    });
    DependencyVector vector = new DependencyVector(item.caseDigest(), item.objectiveRef(), item.graphRef(),
        verification.status(), verification.reasonCode(), verification.objectiveReceipt(),
        verification.graphReceipt(), List.copyOf(evidence), verification.measurementReceipts());
    String digest = hash(write(new ResultMaterial(cutoff, request.mode(), outcome, vector)));
    Long revision = jdbc.queryForObject("SELECT COALESCE(MAX(revision),0)+1 FROM " + DB + "evaluation WHERE tenant_id=? AND environment_id=? AND case_id=?", Long.class, actor.tenantId(), actor.environmentId(), id);
    String evalId = UUID.randomUUID().toString();
    jdbc.update("INSERT INTO " + DB + "evaluation (tenant_id,environment_id,id,case_id,revision,observation_cutoff,cutoff_epoch_second,cutoff_nano,mode,outcome,dependency_vector,result_digest) VALUES (?,?,?,?,?,?,?,?,?,?,?::jsonb,?)", actor.tenantId(), actor.environmentId(), evalId, id, revision, cutoff.toString(), cutoff.getEpochSecond(), cutoff.getNano(), request.mode().name(), outcome, write(vector), digest);
    event(actor, "EVALUATION", evalId, revision, "uacrf.s07.evaluation.completed.v1", digest, Map.of("case_id", id, "outcome", outcome, "mode", request.mode().name()));
    if (request.mode() == Mode.LIVE && outcome.equals("CONFIRMED_BREACH")) {
      String recId = UUID.randomUUID().toString();
      Bounds bounds = new Bounds(item.metric(), item.comparator(), item.threshold(), item.unit(), "INVESTIGATE_ONLY");
      Prerequisites refs = new Prerequisites(item.caseDigest(), item.objectiveRef(), item.graphRef(), digest);
      jdbc.update("INSERT INTO " + DB + "recommendation (tenant_id,environment_id,id,evaluation_id,case_id,workload_id,kind,effect_bounds,prerequisite_refs,executable) VALUES (?,?,?,?,?,?,'INVESTIGATE_BREACH',?::jsonb,?::jsonb,false)", actor.tenantId(), actor.environmentId(), recId, evalId, id, item.workloadId(), write(bounds), write(refs));
      String recDigest = hash(write(new Recommendation(recId, evalId, id, item.workloadId(), "INVESTIGATE_BREACH", bounds, refs, false)));
      event(actor, "RECOMMENDATION", recId, 1, "uacrf.s07.recommendation.retained.v1", recDigest, Map.of("evaluation_id", evalId, "executable", false));
    }
    return findEvaluation(actor, evalId);
  }
  private record ResultMaterial(Instant observationCutoff, Mode mode, String outcome, DependencyVector dependencyVector) {}
  @Transactional(readOnly=true)
  List<Evaluation> evaluations(DelegationPrincipal actor, String id) {
    permit(actor, "assessment.evaluation.read"); findCase(actor, id, false);
    return jdbc.query("SELECT * FROM " + DB + "evaluation WHERE tenant_id=? AND environment_id=? AND case_id=? ORDER BY cutoff_epoch_second DESC, cutoff_nano DESC, revision DESC", (rs,n)->evaluationRow(rs), actor.tenantId(), actor.environmentId(), id);
  }
  @Transactional(readOnly=true)
  Evaluation latest(DelegationPrincipal actor, String id) {
    List<Evaluation> rows = evaluations(actor, id); if (rows.isEmpty()) throw notFound(); return rows.getFirst();
  }
  @Transactional(readOnly=true)
  Evaluation evaluation(DelegationPrincipal actor, String id) { permit(actor, "assessment.evaluation.read"); return findEvaluation(actor,id); }
  void revalidateEvaluation(DelegationPrincipal actor, String id) {
    Evaluation evaluation = findEvaluation(actor, id);
    DependencyVector vector = evaluation.dependencyVector();
    Case item = findCase(actor, evaluation.caseId(), false);
    if (!"SEALED".equals(item.state()) || !Objects.equals(item.caseDigest(), vector.caseDigest())
        || !Objects.equals(item.caseDigest(), hash(write(content(item))))
        || !Objects.equals(item.objectiveRef(), vector.objectiveRef())
        || !Objects.equals(item.graphRef(), vector.graphRef())
        || vector.measurements() == null || vector.measurements().isEmpty()
        || vector.measurementReceipts() == null
        || vector.measurements().size() != vector.measurementReceipts().size()
        || vector.objectiveReceipt() == null || vector.graphReceipt() == null) {
      throw conflict("UNVERIFIED_EVALUATION", "Evaluation does not contain complete, consistent source evidence.");
    }
    List<Measurement> measurements = new ArrayList<>();
    Map<String, MeasurementReceipt> retained = new HashMap<>();
    for (Evidence evidence : vector.measurements()) {
      if (evidence == null || evidence.measurement() == null
          || evidence.measurement().sourceReceiptId() == null) {
        throw conflict("UNVERIFIED_EVALUATION", "Evaluation contains incomplete measurement evidence.");
      }
      measurements.add(evidence.measurement());
    }
    for (MeasurementReceipt receipt : vector.measurementReceipts()) {
      if (receipt == null || !receipt.usableAsEvidence() || receipt.evidenceId() == null
          || receipt.payloadDigest() == null || retained.put(receipt.evidenceId(), receipt) != null) {
        throw conflict("UNVERIFIED_EVALUATION", "Evaluation contains incomplete measurement receipts.");
      }
    }
    ReceiptVerifier.Verification current = receiptVerifier.verify(
        item, measurements, evaluation.observationCutoff(), actor.tenantId(), actor.environmentId());
    if (!"VERIFIED".equals(current.status())) {
      throw conflict("OWNER_REVALIDATION_FAILED",
          "Current owner verification failed: " + (current.reasonCode() == null ? current.status() : current.reasonCode()) + ".");
    }
    if (!Objects.equals(vector.objectiveReceipt(), current.objectiveReceipt())
        || !Objects.equals(vector.graphReceipt(), current.graphReceipt())
        || current.measurementReceipts().size() != retained.size()) {
      throw conflict("OWNER_REVALIDATION_FAILED", "Current owner receipts differ from the evaluation.");
    }
    for (MeasurementReceipt receipt : current.measurementReceipts()) {
      MeasurementReceipt previous = retained.remove(receipt.evidenceId());
      if (previous == null || !Objects.equals(previous.payloadDigest(), receipt.payloadDigest())
          || previous.revision() != receipt.revision()) {
        throw conflict("OWNER_REVALIDATION_FAILED", "Current measurement receipts differ from the evaluation.");
      }
    }
    if (!retained.isEmpty()) throw conflict("OWNER_REVALIDATION_FAILED", "Current measurement receipts are incomplete.");
  }
  @Transactional(readOnly=true)
  List<Recommendation> recommendations(DelegationPrincipal actor, String workloadId) {
    permit(actor, "assessment.recommendation.read");
    return jdbc.query("SELECT * FROM " + DB + "recommendation WHERE tenant_id=? AND environment_id=? AND workload_id=? ORDER BY created_at,id", (rs,n)->new Recommendation(rs.getString("id"), rs.getString("evaluation_id"), rs.getString("case_id"), rs.getString("workload_id"), rs.getString("kind"), read(rs.getString("effect_bounds"), Bounds.class), read(rs.getString("prerequisite_refs"), Prerequisites.class), rs.getBoolean("executable")), actor.tenantId(), actor.environmentId(), workloadId);
  }
  private Case findCase(DelegationPrincipal actor, String id, boolean lock) {
    List<Case> rows = jdbc.query("SELECT * FROM " + DB + "analysis_case WHERE tenant_id=? AND environment_id=? AND id=?" + (lock ? " FOR UPDATE" : ""), (rs,n)->caseRow(rs), actor.tenantId(), actor.environmentId(), id);
    if (rows.isEmpty()) throw notFound(); return rows.getFirst();
  }
  private Case caseRow(ResultSet rs) throws SQLException {
    CreateCase c = read(rs.getString("content"), CreateCase.class);
    return new Case(rs.getString("id"), c.workloadId(), c.purpose(), c.metric(), c.comparator(), c.threshold(), c.unit(), c.windowSeconds(), c.objectiveRef(), c.graphRef(), c.requiredSourceKinds(), rs.getString("state"), rs.getLong("revision"), rs.getString("case_digest"));
  }
  private static CreateCase content(Case c) { return new CreateCase(c.workloadId(), c.purpose(), c.metric(), c.comparator(), canonical(c.threshold()), c.unit(), c.windowSeconds(), c.objectiveRef(), c.graphRef(), c.requiredSourceKinds()); }
  private Evaluation findEvaluation(DelegationPrincipal actor, String id) {
    List<Evaluation> rows = jdbc.query("SELECT * FROM " + DB + "evaluation WHERE tenant_id=? AND environment_id=? AND id=?", (rs,n)->evaluationRow(rs), actor.tenantId(), actor.environmentId(), id);
    if (rows.isEmpty()) throw notFound(); return rows.getFirst();
  }
  private Evaluation evaluationRow(ResultSet rs) throws SQLException {
    return new Evaluation(rs.getString("id"), rs.getString("case_id"), rs.getLong("revision"), Instant.parse(rs.getString("observation_cutoff")), rs.getTimestamp("received_at").toInstant(), Mode.valueOf(rs.getString("mode")), rs.getString("outcome"), read(rs.getString("dependency_vector"), DependencyVector.class), rs.getString("result_digest"));
  }
  private void event(DelegationPrincipal actor, String type, String id, long revision, String eventType, String digest, Map<String,Object> metadata) {
    Map<String,Object> payload = new LinkedHashMap<>(); payload.put("owned_resource_id", id); payload.put("owned_revision", revision); payload.put("owned_digest", digest); payload.put("minimal_outcome_metadata", metadata); payload.put("actor_id", actor.principalId());
    String serialized = write(payload);
    jdbc.update("INSERT INTO " + DB + "outbox_event (event_id,tenant_id,environment_id,aggregate_type,aggregate_id,aggregate_sequence,event_type,payload,payload_digest) VALUES (?,?,?,?,?,?,?,?::jsonb,?)", UUID.randomUUID(), actor.tenantId(), actor.environmentId(), type, id, revision, eventType, serialized, hash(serialized));
  }
  private String write(Object value) { return json.writeValueAsString(value); }
  private <T> T read(String value, Class<T> type) { return json.readValue(value, type); }
  private static BigDecimal canonical(BigDecimal value) { return value.stripTrailingZeros(); }
  private static String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
  private static void permit(DelegationPrincipal actor, String capability) {
    if (!actor.capabilities().contains(capability) || !"*".equals(actor.serviceScope())) throw new AssessmentException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "The current principal cannot perform this assessment operation.");
  }
  private static void permitReceipt(DelegationPrincipal actor, String id, Jwt jwt) {
    if (!actor.capabilities().contains("assessment.case.read")) {
      throw new AssessmentException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED",
          "The current principal cannot perform this assessment operation.");
    }
    if ("*".equals(actor.serviceScope())) return;
    String purpose = jwt.getClaimAsString("purpose");
    boolean scopedGraphReceipt = id.equals(actor.serviceScope())
        && "assessment_case".equals(jwt.getClaimAsString("resource_type"))
        && id.equals(jwt.getClaimAsString("resource_id"))
        && GRAPH_RECEIPT_PURPOSES.contains(purpose);
    if (!scopedGraphReceipt) {
      throw new AssessmentException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED",
          "The current principal cannot perform this assessment operation.");
    }
  }
  private static AssessmentException invalid(String message) { return new AssessmentException(HttpStatus.BAD_REQUEST,"INVALID_REQUEST",message); }
  private static AssessmentException conflict(String code, String message) { return new AssessmentException(HttpStatus.CONFLICT,code,message); }
  private static AssessmentException notFound() { return new AssessmentException(HttpStatus.NOT_FOUND,"NOT_FOUND","The assessment record does not exist."); }
}
