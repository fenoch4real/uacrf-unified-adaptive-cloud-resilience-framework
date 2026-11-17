package org.uacrf.topology;

import static org.uacrf.topology.TopologyRequests.*;
import static org.uacrf.topology.TopologyResponses.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.uacrf.security.DelegationPrincipal;
import org.uacrf.security.OwnerReceiptSignature;
import tools.jackson.databind.ObjectMapper;

@Service
class TopologyGraphCommandService {
  private static final String S = "uacrf_topology.";
  private static final String SELECTION = "SUBGRAPH_SELECTION";
  private static final String DIFFERENCE = "DIFFERENCE_CLASSIFICATION";
  private static final Set<String> QUERIES = Set.of(
      "action-relevant-dependencies.v1", "action-relevant-dependencies.v2");
  private static final Pattern CATALOG_REF = Pattern.compile(
      "^catalog://service/([A-Za-z0-9][A-Za-z0-9_.:-]{0,199})/revision/([1-9][0-9]{0,15})$");
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final TopologyProofClient proofs;
  private final TransactionTemplate transactions;

  TopologyGraphCommandService(JdbcTemplate db, ObjectMapper json, TopologyProofClient proofs,
      TransactionTemplate transactions) {
    this.db=db; this.json=json; this.proofs=proofs; this.transactions=transactions;
  }

  CommandReceipt select(Jwt jwt, SelectSubgraph request, String key) {
    DelegationPrincipal actor=DelegationPrincipal.from(jwt);
    permit(actor,request.serviceId());
    validateKey(key);
    if (!QUERIES.contains(request.queryId()))
      throw new TopologyException(HttpStatus.UNPROCESSABLE_ENTITY,"QUERY_NOT_ALLOWLISTED","The query identifier is not in the versioned S06 allowlist.");
    if (!TopologyProofClient.SUBGRAPH_SELECTION.equals(request.purpose()))
      throw new TopologyException(HttpStatus.BAD_REQUEST,"INVALID_PURPOSE","The requested purpose is not supported.");
    Matcher reference=CATALOG_REF.matcher(request.queryParametersRef());
    if (!reference.matches()) {
      if (request.queryParametersRef().startsWith("catalog://service/")
          && request.queryParametersRef().contains("/revision/"))
        throw conflict("SERVICE_REVISION_UNAVAILABLE","The opaque S02 catalog reference does not contain a supported immutable service revision.");
      throw new TopologyException(HttpStatus.BAD_REQUEST,"INVALID_CATALOG_REFERENCE","query_parameters_ref must be an opaque S02 service revision reference for service_id.");
    }
    if (!request.serviceId().equals(reference.group(1)))
      throw new TopologyException(HttpStatus.BAD_REQUEST,"INVALID_CATALOG_REFERENCE","The opaque S02 service reference must identify service_id.");
    String version=reference.group(2);
    String canonical=canonical(Map.ofEntries(
        Map.entry("service_id",request.serviceId()),Map.entry("case_id",request.caseId()),
        Map.entry("graph_revision_id",request.graphRevisionId()),Map.entry("reason",request.reason()),
        Map.entry("expected_revision",request.expectedRevision()),Map.entry("query_id",request.queryId()),
        Map.entry("query_parameters_ref",request.queryParametersRef()),
        Map.entry("query_parameters_digest",request.queryParametersDigest()),
        Map.entry("purpose",request.purpose()),Map.entry("maximum_nodes",request.maximumNodes())));
    String digest=GraphDigest.sha256(canonical);
    CommandReceipt replay=replay(actor,SELECTION,key,digest);
    if (replay!=null) return replay;

    Map<String,Object> graph=graph(actor,request.graphRevisionId());
    Instant deadline=Instant.now().plusSeconds(5);
    TopologyProofClient.ServiceRevisionBinding service=proofs.prepareServiceRevision(
        jwt,request.serviceId(),version,
        request.queryParametersDigest(),TopologyProofClient.SUBGRAPH_SELECTION,deadline);
    Map<String,Object> caseReceipt=proofs.prepareCaseReceipt(jwt,request.caseId(),
        TopologyProofClient.SUBGRAPH_SELECTION,deadline);
    Map<String,Object> evidence=evidenceSnapshot(actor,jwt,request.graphRevisionId(),
        TopologyProofClient.SUBGRAPH_SELECTION,deadline);
    Map<String,Object> binding=new LinkedHashMap<>();
    binding.put("service_id",request.serviceId());
    binding.put("graph_revision_id",request.graphRevisionId());
    binding.put("graph_revision_number",graph.get("revision_number"));
    binding.put("graph_digest",graph.get("digest"));
    binding.put("service_node_present",hasServiceNode(actor,request.graphRevisionId(),request.serviceId()));
    binding.put("catalog_ref",request.queryParametersRef());
    binding.put("catalog_digest",request.queryParametersDigest());
    binding.put("service_receipt",service.receipt());
    binding.put("service_snapshot",service.snapshot());
    binding.put("service_workload_ids",service.workloadIds());
    binding.put("service_membership_supported",service.membershipSupported());
    binding.put("case_receipt",caseReceipt);
    binding.put("evidence_snapshot",evidence);
    binding.put("maximum_nodes",request.maximumNodes());
    binding.put("query_id",request.queryId());
    String targetKey=targetKey(Map.ofEntries(
        Map.entry("service_id",request.serviceId()),
        Map.entry("query_id",request.queryId())));
    return persist(actor,SELECTION,key,digest,canonical,binding,targetKey,
        request.expectedRevision(),request.serviceId(),request.graphRevisionId());
  }

  CommandReceipt classify(Jwt jwt, ClassifyDifferences request, String key) {
    DelegationPrincipal actor=DelegationPrincipal.from(jwt);
    permit(actor,request.serviceId());
    validateKey(key);
    String canonical=canonical(Map.ofEntries(
        Map.entry("service_id",request.serviceId()),Map.entry("case_id",request.caseId()),
        Map.entry("reason",request.reason()),Map.entry("expected_revision",request.expectedRevision()),
        Map.entry("authorized_graph_revision_id",request.authorizedGraphRevisionId()),
        Map.entry("current_graph_revision_id",request.currentGraphRevisionId()),
        Map.entry("selection_digest",request.selectionDigest())));
    String digest=GraphDigest.sha256(canonical);
    CommandReceipt replay=replay(actor,DIFFERENCE,key,digest);
    if (replay!=null) return replay;
    RetainedSelection selection=resolveSelection(actor,request.serviceId(),
        request.authorizedGraphRevisionId(),request.selectionDigest());
    Map<String,Object> authorized=graph(actor,request.authorizedGraphRevisionId());
    Map<String,Object> current=graph(actor,request.currentGraphRevisionId());
    Instant deadline=Instant.now().plusSeconds(5);
    Map<String,Object> caseReceipt=proofs.prepareCaseReceipt(jwt,request.caseId(),
        TopologyProofClient.DIFFERENCE_CLASSIFICATION,deadline);
    Map<String,Object> authorizedEvidence=selection.snapshot().get("evidence_snapshot") instanceof Map<?,?> raw
        ?castMap(raw):Map.of("complete",false,"summaries",List.of(),"reason_code","SELECTION_EVIDENCE_UNAVAILABLE");
    Map<String,Object> currentEvidence=evidenceSnapshot(actor,jwt,
        request.currentGraphRevisionId(),TopologyProofClient.DIFFERENCE_CLASSIFICATION,deadline);
    Map<String,Object> binding=new LinkedHashMap<>();
    binding.put("service_id",request.serviceId());
    binding.put("authorized_graph_revision_id",request.authorizedGraphRevisionId());
    binding.put("authorized_graph_revision_number",authorized.get("revision_number"));
    binding.put("authorized_graph_digest",authorized.get("digest"));
    binding.put("authorized_service_node_present",hasServiceNode(actor,request.authorizedGraphRevisionId(),request.serviceId()));
    binding.put("current_graph_revision_id",request.currentGraphRevisionId());
    binding.put("current_graph_revision_number",current.get("revision_number"));
    binding.put("current_graph_digest",current.get("digest"));
    binding.put("current_service_node_present",hasServiceNode(actor,request.currentGraphRevisionId(),request.serviceId()));
    binding.put("selection_digest",request.selectionDigest());
    binding.put("selection_revision",selection.revision());
    binding.put("selection_snapshot",selection.snapshot());
    binding.put("selection_source_binding",selection.sourceBinding());
    binding.put("case_receipt",caseReceipt);
    binding.put("authorized_evidence_snapshot",authorizedEvidence);
    binding.put("current_evidence_snapshot",currentEvidence);
    String targetKey=targetKey(Map.ofEntries(
        Map.entry("service_id",request.serviceId()),
        Map.entry("authorized_graph_revision_id",request.authorizedGraphRevisionId())));
    return persist(actor,DIFFERENCE,key,digest,canonical,binding,targetKey,
        request.expectedRevision(),request.serviceId(),request.authorizedGraphRevisionId());
  }

  private Map<String,Object> evidenceSnapshot(DelegationPrincipal actor,Jwt jwt,String graphId,
      String purpose,Instant deadline) {
    Long graphEdgeCount=db.queryForObject("SELECT count(*) FROM "+S+"graph_edges "
        +"WHERE tenant_id=? AND environment_id=? AND graph_id=?",Long.class,
        actor.tenantId(),actor.environmentId(),graphId);
    List<Map<String,Object>> rows=db.queryForList(
        "SELECT d.source_id,p.source_version,d.target_id,p.target_version,p.evidence_id,"
            +"p.evidence_digest,p.evidence_revision,p.source_receipt::text,p.target_receipt::text,"
            +"p.summary::text,p.invalidated "
            +"FROM "+S+"graph_edges g JOIN "+S+"proposal_evidence p "
            +"ON p.tenant_id=g.tenant_id AND p.environment_id=g.environment_id AND p.proposal_id=g.proposal_id "
            +"JOIN "+S+"dependency_proposals d ON d.tenant_id=p.tenant_id AND d.environment_id=p.environment_id AND d.id=p.proposal_id "
            +"WHERE g.tenant_id=? AND g.environment_id=? AND g.graph_id=? "
            +"ORDER BY g.source_type,g.source_id,g.relationship_type,g.target_type,g.target_id LIMIT 11",
        actor.tenantId(),actor.environmentId(),graphId);
    List<Map<String,Object>> summaries=new java.util.ArrayList<>();
    boolean truncated=rows.size()>10;
    java.util.TreeSet<String> reasonCodes=new java.util.TreeSet<>();
    int unverifiedSummaryCount=0;
    if (truncated) reasonCodes.add("SUMMARY_LIMIT_REACHED");
    for (Map<String,Object> row:rows.stream().limit(10).toList()) {
      if (Boolean.TRUE.equals(row.get("invalidated"))) {
        unverifiedSummaryCount++;
        reasonCodes.add("SUMMARY_INVALIDATED");
        continue;
      }
      try {
        var expected=new TopologyProofClient.Binding(
            (String)row.get("source_id"),(String)row.get("source_version"),
            (String)row.get("target_id"),(String)row.get("target_version"),
            (String)row.get("evidence_id"),(String)row.get("evidence_digest"),
            ((Number)row.get("evidence_revision")).longValue(),
            readObject((String)row.get("source_receipt")),
            readObject((String)row.get("target_receipt")),
            readObject((String)row.get("summary")));
        var checked=proofs.prepare(jwt,purpose,expected,deadline);
        summaries.add(checked.summary());
      } catch (TopologyException error) {
        String reasonCode=switch (error.code()) {
          case "PROOF_STALE" -> "SUMMARY_STALE";
          case "PROOF_UNAVAILABLE_OR_STALE" -> "SUMMARY_UNAVAILABLE";
          default -> throw error;
        };
        unverifiedSummaryCount++;
        reasonCodes.add(reasonCode);
      }
    }
    if (graphEdgeCount==null)
      throw new IllegalStateException("The graph edge count query returned no result.");
    if (graphEdgeCount.longValue()!=summaries.size())
      reasonCodes.add("SUMMARY_COVERAGE_GAP");
    Map<String,Object> result=new LinkedHashMap<>();
    result.put("graph_edge_count",graphEdgeCount);
    result.put("verified_summary_count",summaries.size());
    result.put("summaries",List.copyOf(summaries));
    result.put("truncated",truncated);
    result.put("complete",!truncated && unverifiedSummaryCount==0
        && graphEdgeCount.longValue()==summaries.size());
    result.put("unverified_summary_count",unverifiedSummaryCount);
    result.put("reason_codes",List.copyOf(reasonCodes));
    return result;
  }

  Map<String,Object> status(DelegationPrincipal actor, String id) {
    if (!actor.capabilities().contains("topology.graph.read"))
      throw new TopologyException(HttpStatus.FORBIDDEN,"PERMISSION_DENIED","The current principal cannot read topology command status.");
    List<Map<String,Object>> rows=db.queryForList(
        "SELECT service_id,status,result::text "
            + "FROM "+S+"graph_command WHERE command_id=?::uuid AND tenant_id=? AND environment_id=? AND actor_id=?",
        id,actor.tenantId(),actor.environmentId(),actor.principalId());
    if (rows.isEmpty()) throw missing();
    Map<String,Object> row=rows.getFirst();
    if (!"*".equals(actor.serviceScope()) && !actor.serviceScope().equals(row.get("service_id")))
      throw missing();
    String status=(String)row.get("status");
    boolean terminal=Set.of("SUCCEEDED","REJECTED","FAILED","CANCELLED","EXPIRED").contains(status);
    Map<String,Object> response=new LinkedHashMap<>();
    response.put("command_id",id);
    response.put("status",status);
    response.put("terminal",terminal);
    response.put("domain_completion","SUCCEEDED".equals(status));
    if ("SUCCEEDED".equals(status) && row.get("result") instanceof String value) {
      Map<String,Object> result=readObject(value);
      response.put("advisory_result",advisoryResult(result));
    }
    return response;
  }

  private Map<String,Object> advisoryResult(Map<String,Object> result) {
    boolean selection="ACTION_RELEVANT_SUBGRAPH".equals(result.get("kind"));
    String digest=(String)result.get("result_digest");
    String completeness="INDETERMINATE".equals(result.get("outcome"))
        ?"INDETERMINATE":"INCOMPLETE";
    String summary=selection
        ?"Selection "+digest+" computed with "+listSize(result.get("nodes"))
            +" nodes and "+listSize(result.get("edges"))+" existing edges; coverage is INCOMPLETE."
        :"Classification "+result.get("classification")+" for selection "
            +result.get("selection_digest")+"; coverage is "+completeness+".";
    Object unsupported=result.get("unsupported_domains");
    if (unsupported instanceof List<?> domains && !domains.isEmpty())
      summary+=" Unsupported domains: "+String.join(", ",domains.stream()
          .filter(String.class::isInstance).map(String.class::cast).toList())+".";
    if (result.get("freshness_limitation") instanceof String limitation)
      summary+=" "+limitation;
    if (summary.length()>1900) summary=summary.substring(0,1897)+"...";
    Map<String,Object> advisory=new LinkedHashMap<>();
    advisory.put("kind",selection?"ACTION_RELEVANT_SUBGRAPH":"GRAPH_DIFFERENCE_CLASSIFICATION");
    advisory.put("completeness",completeness);
    advisory.put("result_digest",digest);
    advisory.put("summary",summary);
    advisory.put("advisory_only",true);
    if (selection) {
      advisory.put("nodes",result.get("nodes"));
      advisory.put("edges",result.get("edges"));
      advisory.put("unsupported_domains",result.get("unsupported_domains"));
      if (result.containsKey("inventory_reconciliation"))
        advisory.put("inventory_reconciliation",result.get("inventory_reconciliation"));
    } else {
      advisory.put("classification",result.get("classification"));
      advisory.put("selection_digest",result.get("selection_digest"));
      advisory.put("changes",result.get("changes"));
      advisory.put("unsupported_domains",result.get("unsupported_domains"));
    }
    return advisory;
  }

  private int listSize(Object value) {
    return value instanceof List<?> list?list.size():0;
  }

  private Map<String,Object> graph(DelegationPrincipal actor,String id) {
    List<Map<String,Object>> rows=db.queryForList(
        "SELECT revision_number,digest FROM "+S+"graph_revisions WHERE tenant_id=? AND environment_id=? AND id=?",
        actor.tenantId(),actor.environmentId(),id);
    if (rows.isEmpty()) throw missing();
    return rows.getFirst();
  }

  private CommandReceipt replay(DelegationPrincipal actor,String operation,String key,String digest) {
    List<Map<String,Object>> rows=db.queryForList(
        "SELECT command_id,request_digest FROM "+S+"graph_command WHERE tenant_id=? AND environment_id=? AND actor_id=? AND operation=? AND idempotency_key=?",
        actor.tenantId(),actor.environmentId(),actor.principalId(),operation,key);
    if (rows.isEmpty()) return null;
    Map<String,Object> row=rows.getFirst();
    if (!digest.equals(row.get("request_digest")))
      throw conflict("IDEMPOTENCY_CONFLICT","The idempotency key was already used for a different canonical request.");
    return receipt(row.get("command_id").toString());
  }

  private CommandReceipt persist(DelegationPrincipal actor,String operation,String key,String digest,
      String canonicalRequest,Map<String,Object> binding,String targetKey,long expectedRevision,
      String serviceId,String graphRevisionId) {
    return transactions.execute(status -> persistLocked(actor,operation,key,digest,
        canonicalRequest,binding,targetKey,expectedRevision,serviceId,graphRevisionId));
  }

  private CommandReceipt persistLocked(DelegationPrincipal actor,String operation,String key,String digest,
      String canonicalRequest,Map<String,Object> binding,String targetKey,long expectedRevision,
      String serviceId,String graphRevisionId) {
    String id=UUID.randomUUID().toString();
    String body=json.writeValueAsString(binding);
    int inserted=db.update("INSERT INTO "+S+"graph_command (command_id,tenant_id,environment_id,actor_id,service_id,operation,idempotency_key,request_digest,request_body,source_binding,status) "
        + "VALUES (?::uuid,?,?,?,?,?,?,?,?::jsonb,?::jsonb,'ACCEPTED') ON CONFLICT (tenant_id,environment_id,actor_id,operation,idempotency_key) DO NOTHING",
        id,actor.tenantId(),actor.environmentId(),actor.principalId(),serviceId(canonicalRequest),operation,key,digest,canonicalRequest,body);
    if (inserted==0) {
      List<Map<String,Object>> rows=db.queryForList("SELECT command_id,request_digest FROM "+S+"graph_command WHERE tenant_id=? AND environment_id=? AND actor_id=? AND operation=? AND idempotency_key=? FOR UPDATE",
          actor.tenantId(),actor.environmentId(),actor.principalId(),operation,key);
      if (rows.isEmpty() || !digest.equals(rows.getFirst().get("request_digest")))
        throw conflict("IDEMPOTENCY_CONFLICT","The idempotency key was already used for a different canonical request.");
      return receipt(rows.getFirst().get("command_id").toString());
    }
    db.update("INSERT INTO "+S+"graph_domain_head (tenant_id,environment_id,operation,target_key) "
        +"VALUES (?,?,?,?) ON CONFLICT (tenant_id,environment_id,operation,target_key) DO NOTHING",
        actor.tenantId(),actor.environmentId(),operation,targetKey);
    List<Map<String,Object>> heads=db.queryForList(
        "SELECT current_revision,pending_command_id FROM "+S+"graph_domain_head "
            +"WHERE tenant_id=? AND environment_id=? AND operation=? AND target_key=? FOR UPDATE",
        actor.tenantId(),actor.environmentId(),operation,targetKey);
    if (heads.isEmpty()) throw new IllegalStateException("The graph domain revision head disappeared.");
    Map<String,Object> head=heads.getFirst();
    long currentRevision=((Number)head.get("current_revision")).longValue();
    if (head.get("pending_command_id")!=null)
      throw conflict("TARGET_REVISION_PENDING","Another command is updating this target revision.");
    if (currentRevision!=expectedRevision)
      throw conflict("REVISION_MISMATCH","expected_revision must equal the current target revision.");
    long targetRevision=currentRevision+1;
    db.update("INSERT INTO "+S+"graph_domain_revision "
        +"(tenant_id,environment_id,operation,target_key,revision,expected_revision,command_id,"
        +"service_id,graph_revision_id,source_binding,result,status) "
        +"VALUES (?,?,?,?,?,?,?::uuid,?,?,?::jsonb,NULL,'PENDING')",
        actor.tenantId(),actor.environmentId(),operation,targetKey,targetRevision,expectedRevision,
        id,serviceId,graphRevisionId,body);
    db.update("UPDATE "+S+"graph_domain_head SET pending_command_id=?::uuid,updated_at=now() "
        +"WHERE tenant_id=? AND environment_id=? AND operation=? AND target_key=?",
        id,actor.tenantId(),actor.environmentId(),operation,targetKey);
    String eventPayload=json.writeValueAsString(Map.of(
        "command_id",id,"operation",operation,"request_digest",digest));
    db.update("INSERT INTO "+S+"graph_command_outbox (event_id,command_id,event_type,payload,payload_digest) VALUES ( ?,?::uuid,?,?::jsonb,?)",
        UUID.randomUUID(),id,"uacrf.s06.graph.command.accepted.v1",
        eventPayload,GraphDigest.sha256(eventPayload));
    return receipt(id);
  }

  private boolean hasServiceNode(DelegationPrincipal actor,String graphId,String serviceId) {
    Integer count=db.queryForObject("SELECT count(*) FROM "+S+"graph_nodes WHERE tenant_id=? "
        +"AND environment_id=? AND graph_id=? AND entity_type='service' AND entity_id=?",
        Integer.class,actor.tenantId(),actor.environmentId(),graphId,serviceId);
    return count!=null && count>0;
  }

  private String serviceId(String canonicalRequest) {
    Map<String,Object> request=readObject(canonicalRequest);
    return (String)request.get("service_id");
  }
  private String canonical(Map<String,Object> request) { return json.writeValueAsString(new TreeMap<>(request)); }
  private Map<String,Object> readObject(String value) { return json.readValue(value,Map.class); }
  private String targetKey(Map<String,Object> target) {
    return GraphDigest.sha256(OwnerReceiptSignature.canonicalize(canonical(target)));
  }
  private RetainedSelection resolveSelection(DelegationPrincipal actor,String serviceId,
      String graphRevisionId,String selectionDigest) {
    List<Map<String,Object>> rows=db.queryForList(
        "SELECT revision,selection_snapshot::text,result::text,source_binding::text "
            +"FROM "+S+"graph_domain_revision WHERE tenant_id=? AND environment_id=? "
            +"AND operation='SUBGRAPH_SELECTION' AND service_id=? AND graph_revision_id=? "
            +"AND selection_digest=? AND status='SUCCEEDED' "
            +"ORDER BY revision DESC LIMIT 1",
        actor.tenantId(),actor.environmentId(),serviceId,graphRevisionId,selectionDigest);
    if (rows.isEmpty())
      throw conflict("SELECTION_CONTEXT_MISMATCH",
          "selection_digest must identify a retained S06 selection for this service and authorized graph revision.");
    Map<String,Object> row=rows.getFirst();
    Map<String,Object> snapshot=readObject((String)row.get("selection_snapshot"));
    Map<String,Object> result=readObject((String)row.get("result"));
    Map<String,Object> sourceBinding=readObject((String)row.get("source_binding"));
    if (!selectionDigest.equals(selectionDigest(snapshot))
        || !serviceId.equals(snapshot.get("service_id"))
        || !graphRevisionId.equals(snapshot.get("graph_revision_id"))
        || !selectionDigest.equals(result.get("result_digest")))
      throw conflict("SELECTION_CONTEXT_MISMATCH",
          "The retained selection does not match the supplied digest, service, and authorized graph.");
    return new RetainedSelection(((Number)row.get("revision")).longValue(),snapshot,sourceBinding);
  }
  private String selectionDigest(Map<String,Object> snapshot) {
    return GraphDigest.sha256(OwnerReceiptSignature.canonicalize(json.writeValueAsString(snapshot)));
  }
  @SuppressWarnings("unchecked")
  private static Map<String,Object> castMap(Map<?,?> raw) {
    return (Map<String,Object>)raw;
  }
  private record RetainedSelection(long revision,Map<String,Object> snapshot,
      Map<String,Object> sourceBinding) {}
  private CommandReceipt receipt(String id) { return new CommandReceipt(id,"PENDING","/v1/commands/"+id,false); }
  private static void validateKey(String key) {
    if (key==null || key.isBlank() || key.length()>200 || key.chars().anyMatch(Character::isISOControl))
      throw new TopologyException(HttpStatus.BAD_REQUEST,"INVALID_IDEMPOTENCY_KEY","Idempotency-Key must be a non-empty token of at most 200 characters.");
  }
  private static void permit(DelegationPrincipal actor,String serviceId) {
    if (!actor.capabilities().contains("topology.graph.read")
        || (!"*".equals(actor.serviceScope()) && !serviceId.equals(actor.serviceScope())))
      throw new TopologyException(HttpStatus.FORBIDDEN,"PERMISSION_DENIED","The current principal cannot read this graph context.");
  }
  private static TopologyException missing() { return new TopologyException(HttpStatus.NOT_FOUND,"NOT_FOUND","The requested topology command or graph does not exist."); }
  private static TopologyException conflict(String code,String message) { return new TopologyException(HttpStatus.CONFLICT,code,message); }
}
