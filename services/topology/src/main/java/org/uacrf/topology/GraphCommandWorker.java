package org.uacrf.topology;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.uacrf.security.OwnerReceiptSignature;
import tools.jackson.databind.ObjectMapper;

@Component
class GraphCommandWorker {
  private static final Logger LOG=LoggerFactory.getLogger(GraphCommandWorker.class);
  private static final String S="uacrf_topology.";
  private static final int MAX_BOUND_GRAPH_NODES=20_000;
  private static final int MAX_BOUND_GRAPH_EDGES=50_000;
  private static final int MAX_SELECTED_EDGES=50_000;
  private static final int MAX_RESULT_NODES=1_000;
  private static final int MAX_RESULT_EDGES=5_000;
  private static final int MAX_RESULT_CHANGES=100;
  private static final int MAX_ATTEMPTS=5;
  private static final Set<String> FAILURE_DOMAIN_RELATIONSHIPS=
      Set.of("IN_FAILURE_DOMAIN","MEMBER_OF_FAILURE_DOMAIN");
  private final JdbcTemplate db;
  private final TransactionTemplate transactions;
  private final ObjectMapper json;
  private final String owner=UUID.randomUUID().toString();
  private final long leaseMillis;
  private final int batchSize;

  GraphCommandWorker(JdbcTemplate db,TransactionTemplate transactions,ObjectMapper json,
      @Value("${uacrf.topology.commands.lease-ms:30000}") long leaseMillis,
      @Value("${uacrf.topology.commands.batch-size:10}") int batchSize) {
    this.db=db; this.transactions=transactions; this.json=json;
    this.leaseMillis=Math.max(1000,leaseMillis); this.batchSize=Math.max(1,Math.min(100,batchSize));
  }

  @Scheduled(fixedDelayString="${uacrf.topology.commands.interval-ms:1000}")
  void scheduled() {
    for (int i=0;i<batchSize;i++) {
      try { if (!processNext()) return; }
      catch (RuntimeException error) {
        LOG.warn("S06 graph command cycle failed; the command remains retryable: {}",error.toString());
        return;
      }
    }
  }

  boolean processNext() {
    Claim claim=transactions.execute(status -> claimLocked());
    if (claim==null) return false;
    try {
      if (!bound(claim)) {
        fail(claim,"GRAPH_REVISION_DIGEST_MISMATCH");
        return true;
      }
      Computation computation="SUBGRAPH_SELECTION".equals(claim.operation())
          ?select(claim,claim.binding(),(String)claim.binding().get("graph_revision_id"),
              (String)claim.binding().get("graph_digest"),
              claim.binding().get("graph_revision_number"),
              mapValue(claim.binding().get("evidence_snapshot")))
          :classify(claim);
      succeed(claim,computation);
    } catch (RuntimeException error) {
      retryOrFail(claim,error);
      LOG.warn("S06 graph command {} could not be verified: {}",claim.commandId(),error.toString());
    }
    return true;
  }

  private Claim claimLocked() {
    List<Map<String,Object>> rows=db.queryForList(
        "SELECT o.event_id,o.command_id,c.tenant_id,c.environment_id,c.operation,c.source_binding::text,"
            +"d.target_key,d.revision,o.attempt_count "
            +"FROM "+S+"graph_command_outbox o JOIN "+S+"graph_command c ON c.command_id=o.command_id "
            +"JOIN "+S+"graph_domain_revision d ON d.command_id=c.command_id "
            +"WHERE o.processed_at IS NULL AND o.next_attempt_at<=clock_timestamp() "
            +"AND (o.lease_expires_at IS NULL OR o.lease_expires_at<=clock_timestamp()) "
            +"ORDER BY o.created_at,o.event_id LIMIT 1 FOR UPDATE OF o SKIP LOCKED");
    if (rows.isEmpty()) return null;
    Map<String,Object> row=rows.getFirst();
    int changed=db.update("UPDATE "+S+"graph_command_outbox SET claimed_at=now(),lease_owner=?,"
        +"attempt_count=attempt_count+1,"
        +"lease_expires_at=clock_timestamp()+(? * interval '1 millisecond') "
        +"WHERE event_id=?::uuid AND processed_at IS NULL",
        owner,leaseMillis,row.get("event_id"));
    if (changed!=1) return null;
    int started=db.update("UPDATE "+S+"graph_command SET status='RUNNING',updated_at=now() "
        +"WHERE command_id=?::uuid AND status IN ('ACCEPTED','RUNNING','RETRY_SCHEDULED')",
        row.get("command_id"));
    if (started!=1) return null;
    return new Claim(row.get("event_id").toString(),row.get("command_id").toString(),
        (String)row.get("tenant_id"),(String)row.get("environment_id"),(String)row.get("operation"),
        readObject((String)row.get("source_binding")),(String)row.get("target_key"),
        ((Number)row.get("revision")).longValue(),((Number)row.get("attempt_count")).intValue()+1);
  }

  private boolean bound(Claim claim) {
    if ("SUBGRAPH_SELECTION".equals(claim.operation()))
      return graphMatches(claim,"graph_revision_id","graph_digest");
    if (!graphMatches(claim,"authorized_graph_revision_id","authorized_graph_digest")
        || !graphMatches(claim,"current_graph_revision_id","current_graph_digest")) return false;
    Map<String,Object> snapshot=mapValue(claim.binding().get("selection_snapshot"));
    Map<String,Object> selectionBinding=mapValue(claim.binding().get("selection_source_binding"));
    String selectionDigest=(String)claim.binding().get("selection_digest");
    return selectionDigest!=null
        && selectionDigest.equals(selectionDigest(snapshot))
        && claim.binding().get("authorized_graph_revision_id").equals(snapshot.get("graph_revision_id"))
        && claim.binding().get("service_id").equals(snapshot.get("service_id"))
        && selectionBinding.get("service_snapshot") instanceof Map<?,?> serviceSnapshot
        && claim.binding().get("service_id").equals(serviceSnapshot.get("service_id"));
  }

  private boolean graphMatches(Claim claim,String idKey,String digestKey) {
    Object id=claim.binding().get(idKey), digest=claim.binding().get(digestKey);
    if (!(id instanceof String graphId) || !(digest instanceof String expected)) return false;
    List<Map<String,Object>> rows=db.queryForList("SELECT digest,digest_version FROM "+S+"graph_revisions "
        +"WHERE tenant_id=? AND environment_id=? AND id=?",claim.tenantId(),claim.environmentId(),graphId);
    if (rows.size()!=1 || !expected.equals(rows.getFirst().get("digest"))) return false;
    int version=((Number)rows.getFirst().get("digest_version")).intValue();
    List<TopologyResponses.Node> nodes=db.query("SELECT entity_type,entity_id FROM "+S+"graph_nodes "
        +"WHERE tenant_id=? AND environment_id=? AND graph_id=? ORDER BY entity_type,entity_id LIMIT "
        +(MAX_BOUND_GRAPH_NODES+1),
        (rs,n)->new TopologyResponses.Node(rs.getString(1),rs.getString(2)),
        claim.tenantId(),claim.environmentId(),graphId);
    if (nodes.size()>MAX_BOUND_GRAPH_NODES) return false;
    List<TopologyResponses.Edge> edges=db.query("SELECT source_type,source_id,relationship_type,target_type,target_id,proposal_id "
        +"FROM "+S+"graph_edges WHERE tenant_id=? AND environment_id=? AND graph_id=? "
        +"ORDER BY source_type,source_id,relationship_type,target_type,target_id LIMIT "
        +(MAX_BOUND_GRAPH_EDGES+1),
        (rs,n)->new TopologyResponses.Edge(rs.getString(1),rs.getString(2),rs.getString(3),
            rs.getString(4),rs.getString(5),rs.getString(6)),
        claim.tenantId(),claim.environmentId(),graphId);
    if (edges.size()>MAX_BOUND_GRAPH_EDGES) return false;
    return expected.equals(GraphDigest.digest(nodes,edges,version));
  }

  private Computation select(Claim claim,Map<String,Object> binding,String graphId,
      String graphDigest,Object revisionNumber,Map<String,Object> evidenceSnapshot) {
    List<TopologyResponses.Node> graphNodes=db.query(
        "SELECT entity_type,entity_id FROM "+S+"graph_nodes WHERE tenant_id=? "
            +"AND environment_id=? AND graph_id=? ORDER BY entity_type,entity_id LIMIT "
            +(MAX_BOUND_GRAPH_NODES+1),
        (rs,n)->new TopologyResponses.Node(rs.getString(1),rs.getString(2)),
        claim.tenantId(),claim.environmentId(),graphId);
    List<TopologyResponses.Edge> graphEdges=db.query(
        "SELECT source_type,source_id,relationship_type,target_type,target_id,proposal_id "
            +"FROM "+S+"graph_edges WHERE tenant_id=? AND environment_id=? AND graph_id=? "
            +"ORDER BY source_type,source_id,relationship_type,target_type,target_id LIMIT "
            +(MAX_BOUND_GRAPH_EDGES+1),
        (rs,n)->new TopologyResponses.Edge(rs.getString(1),rs.getString(2),rs.getString(3),
            rs.getString(4),rs.getString(5),rs.getString(6)),
        claim.tenantId(),claim.environmentId(),graphId);
    if (graphNodes.size()>MAX_BOUND_GRAPH_NODES || graphEdges.size()>MAX_BOUND_GRAPH_EDGES)
      throw new IllegalStateException("The pinned graph exceeds S06's bounded verification limits.");

    Map<NodeKey,TopologyResponses.Node> nodeByKey=new java.util.HashMap<>();
    for (TopologyResponses.Node node:graphNodes)
      nodeByKey.put(new NodeKey(node.entityType(),node.entityId()),node);
    Map<NodeKey,List<TopologyResponses.Edge>> outgoing=new java.util.HashMap<>();
    for (TopologyResponses.Edge edge:graphEdges)
      outgoing.computeIfAbsent(new NodeKey(edge.sourceType(),edge.sourceId()),ignored->
          new java.util.ArrayList<>()).add(edge);

    List<String> workloadIds=binding.get("service_workload_ids") instanceof List<?> values
        ?values.stream().filter(String.class::isInstance).map(String.class::cast).toList():List.of();
    boolean membershipSupported=Boolean.TRUE.equals(binding.get("service_membership_supported"));
    List<String> rootIds=new java.util.ArrayList<>();
    List<String> missingRoots=new java.util.ArrayList<>();
    if (membershipSupported) for (String id:workloadIds) {
      if (nodeByKey.containsKey(new NodeKey("agent_workload",id))) rootIds.add(id);
      else missingRoots.add(id);
    }

    int requestedMaximumNodes=binding.get("maximum_nodes") instanceof Number number
        ?Math.max(1,Math.min(10_000,number.intValue())):1;
    int maximumNodes=Math.min(requestedMaximumNodes,MAX_RESULT_NODES);
    java.util.LinkedHashMap<NodeKey,TopologyResponses.Node> selectedNodes=new java.util.LinkedHashMap<>();
    java.util.LinkedHashMap<EdgeKey,TopologyResponses.Edge> selectedEdges=new java.util.LinkedHashMap<>();
    java.util.ArrayDeque<NodeKey> queue=new java.util.ArrayDeque<>();
    boolean truncated=false;
    for (String root:rootIds) {
      NodeKey key=new NodeKey("agent_workload",root);
      if (selectedNodes.size()>=maximumNodes) { truncated=true; break; }
      selectedNodes.put(key,nodeByKey.get(key));
      queue.addLast(key);
    }
    while (!queue.isEmpty()) {
      NodeKey source=queue.removeFirst();
      for (TopologyResponses.Edge edge:outgoing.getOrDefault(source,List.of())) {
        if (!"DEPENDS_ON".equals(edge.relationshipType())) continue;
        NodeKey target=new NodeKey(edge.targetType(),edge.targetId());
        if (!selectedNodes.containsKey(target) && selectedNodes.size()>=maximumNodes) {
          truncated=true;
          continue;
        }
        if (!selectedEdges.containsKey(new EdgeKey(edge))
            && selectedEdges.size()>=Math.min(MAX_SELECTED_EDGES,MAX_RESULT_EDGES)) {
          truncated=true;
          continue;
        }
        selectedEdges.put(new EdgeKey(edge),edge);
        if (!selectedNodes.containsKey(target)) {
          TopologyResponses.Node node=nodeByKey.get(target);
          if (node==null) { truncated=true; continue; }
          selectedNodes.put(target,node);
          queue.addLast(target);
        }
        if (selectedEdges.size()>=MAX_SELECTED_EDGES) { truncated=true; queue.clear(); break; }
      }
    }
    List<NodeKey> dependencyClosure=List.copyOf(selectedNodes.keySet());
    for (NodeKey source:dependencyClosure) {
      for (TopologyResponses.Edge edge:outgoing.getOrDefault(source,List.of())) {
        if (!Set.of("IN_FAILURE_DOMAIN","MEMBER_OF_FAILURE_DOMAIN","RUNS_IN","HOSTED_ON","USES_IDENTITY","USES_MODEL")
            .contains(edge.relationshipType())) continue;
        NodeKey target=new NodeKey(edge.targetType(),edge.targetId());
        if (!selectedNodes.containsKey(target) && selectedNodes.size()>=maximumNodes) {
          truncated=true;
          continue;
        }
        if (!selectedEdges.containsKey(new EdgeKey(edge))
            && selectedEdges.size()>=Math.min(MAX_SELECTED_EDGES,MAX_RESULT_EDGES)) {
          truncated=true;
          continue;
        }
        if (!selectedNodes.containsKey(target)) {
          TopologyResponses.Node node=nodeByKey.get(target);
          if (node==null) { truncated=true; continue; }
          selectedNodes.put(target,node);
        }
        selectedEdges.put(new EdgeKey(edge),edge);
      }
    }
    for (TopologyResponses.Node node:List.copyOf(selectedNodes.values())) {
      if (!"region".equals(node.entityType())) continue;
      NodeKey source=new NodeKey(node.entityType(),node.entityId());
      for (TopologyResponses.Edge edge:outgoing.getOrDefault(source,List.of())) {
        if (!FAILURE_DOMAIN_RELATIONSHIPS.contains(edge.relationshipType())) continue;
        NodeKey target=new NodeKey(edge.targetType(),edge.targetId());
        if (!selectedNodes.containsKey(target) && selectedNodes.size()>=maximumNodes) {
          truncated=true;
          continue;
        }
        if (!selectedEdges.containsKey(new EdgeKey(edge))
            && selectedEdges.size()>=Math.min(MAX_SELECTED_EDGES,MAX_RESULT_EDGES)) {
          truncated=true;
          continue;
        }
        if (!selectedNodes.containsKey(target)) {
          TopologyResponses.Node targetNode=nodeByKey.get(target);
          if (targetNode==null) { truncated=true; continue; }
          selectedNodes.put(target,targetNode);
        }
        selectedEdges.put(new EdgeKey(edge),edge);
      }
    }

    List<String> unsupported=List.of(
        "SLOs and versioned measurement definitions",
        "customer journeys, journey steps, and customer segments",
        "S02 service-revision snapshots do not bind journey membership, capability links, customer segments, or related catalog associations",
        "impact and outcome measures",
        "controls and enforcement points",
        "approved fallbacks, recovery paths, certification, expiry, and recertification",
        "API, data-store, queue, feature-pipeline, team, provider, essential-function, and outcome graph objects",
        "bounded S04 summaries with an operation-specific freshness threshold");
    Map<String,Object> result=new LinkedHashMap<>();
    result.put("kind","ACTION_RELEVANT_SUBGRAPH");
    result.put("outcome","INCOMPLETE"); result.put("complete",false); result.put("advisory_only",true);
    result.put("service_id",binding.get("service_id"));
    result.put("graph_revision_id",graphId);
    result.put("graph_revision_number",revisionNumber);
    result.put("graph_digest",graphDigest);
    result.put("query_id","action-relevant-dependencies.v1");
    result.put("service_node_present",hasNode(claim,graphId,"service",(String)binding.get("service_id")));
    result.put("catalog_ref",binding.get("catalog_ref"));
    result.put("catalog_revision",binding.get("service_snapshot") instanceof Map<?,?> snapshot
        ?snapshot.get("revision"):null);
    result.put("catalog_digest",binding.get("catalog_digest"));
    result.put("catalog_membership_supported",membershipSupported);
    result.put("root_workload_ids",rootIds);
    result.put("missing_workload_roots",missingRoots);
    result.put("nodes",selectedNodes.values().stream().map(node->
        Map.of("node_type",node.entityType(),"node_id",node.entityId())).toList());
    result.put("edges",selectedEdges.values().stream().map(edge->Map.of(
        "source_type",edge.sourceType(),"source_id",edge.sourceId(),
        "relationship_type",edge.relationshipType(),"target_type",edge.targetType(),
        "target_id",edge.targetId())).toList());
    result.put("maximum_nodes",requestedMaximumNodes);
    result.put("result_node_cap",MAX_RESULT_NODES);
    result.put("result_edge_cap",MAX_RESULT_EDGES);
    result.put("truncated",truncated);
    result.put("supported_domains",List.of(
        "service-rooted, catalog-revision-bound agent-workload mapping",
        "accepted S06 DEPENDS_ON edges within the pinned graph revision",
        "bounded region, cloud-resource, identity, model, and failure-domain context where represented"));
    result.put("unsupported_domains",unsupported);
    result.put("verified_s04_summaries",evidenceSnapshot instanceof Map<?,?> snapshot
        ?snapshot.get("summaries"):List.of());
    result.put("s04_summary_coverage",evidenceSnapshot);
    result.put("freshness_limitation",
        "S04 supplies no evidence-expiration policy. observed_at is retained metadata; QUALIFIED does not certify freshness.");
    result.put("catalog_context_limitation",
        "The exact S02 revision binds service metadata and its sorted owned_agent_workload_ids, but not journey membership, capability links, customer segments, or other catalog associations.");
    result.put("reason_code",!membershipSupported?"SERVICE_MEMBERSHIP_UNAVAILABLE":
        !missingRoots.isEmpty()?"SERVICE_MEMBERSHIP_NOT_IN_GRAPH":
        rootIds.isEmpty()?"SERVICE_HAS_NO_WORKLOAD_ROOTS":
        truncated?"SELECTION_LIMIT_REACHED":"TOPOLOGY_SCHEMA_COVERAGE_INCOMPLETE");
    if (!membershipSupported)
      result.put("membership_limitation",
          "The exact immutable S02 revision has no supported owned_agent_workload_ids mapping; no mutable catalog read or inferred graph edge was used.");
    if (!missingRoots.isEmpty())
      result.put("membership_limitation",
          "Some workload IDs in the exact S02 revision are absent from this accepted graph revision; they were not added as graph nodes or edges.");
    result.put("summary","Bounded selection of "+selectedNodes.size()+" nodes and "
        +selectedEdges.size()+" accepted edges; UACRF coverage is INCOMPLETE because unsupported ontology and freshness domains remain.");
    Map<String,Object> selectionSnapshot=new LinkedHashMap<>(result);
    String digest=selectionDigest(selectionSnapshot);
    result.put("result_digest",digest);
    return new Computation(result,selectionSnapshot,null,digest,digest);
  }

  private record NodeKey(String type,String id) {}
  private record EdgeKey(String sourceType,String sourceId,String relationshipType,
      String targetType,String targetId) {
    EdgeKey(TopologyResponses.Edge edge) {
      this(edge.sourceType(),edge.sourceId(),edge.relationshipType(),edge.targetType(),edge.targetId());
    }
  }

  private Computation classify(Claim claim) {
    Map<String,Object> authorizedSnapshot=mapValue(claim.binding().get("selection_snapshot"));
    Map<String,Object> selectionBinding=mapValue(claim.binding().get("selection_source_binding"));
    String currentGraphId=(String)claim.binding().get("current_graph_revision_id");
    String currentGraphDigest=(String)claim.binding().get("current_graph_digest");
    Computation current=select(claim,selectionBinding,currentGraphId,currentGraphDigest,
        claim.binding().get("current_graph_revision_number"),
        mapValue(claim.binding().get("current_evidence_snapshot")));

    Set<EdgeValue> oldDependencies=edges(authorizedSnapshot,"DEPENDS_ON");
    Set<EdgeValue> newDependencies=edges(current.selectionSnapshot(),"DEPENDS_ON");
    Set<EdgeValue> removedDependencies=new java.util.TreeSet<>(oldDependencies);
    removedDependencies.removeAll(newDependencies);
    Set<EdgeValue> addedDependencies=new java.util.TreeSet<>(newDependencies);
    addedDependencies.removeAll(oldDependencies);
    List<Map<String,Object>> dependencyTargetChanges=new java.util.ArrayList<>();
    for (EdgeValue removed:new java.util.ArrayList<>(removedDependencies)) {
      EdgeValue replacement=addedDependencies.stream()
          .filter(added->removed.sameSourceAndRelationship(added)
              && !removed.sameTarget(added))
          .findFirst().orElse(null);
      if (replacement!=null) {
        dependencyTargetChanges.add(Map.of("from",removed.asMap(),"to",replacement.asMap()));
        removedDependencies.remove(removed);
        addedDependencies.remove(replacement);
      }
    }

    Set<EdgeValue> oldFailureDomains=failureDomainEdges(authorizedSnapshot);
    Set<EdgeValue> newFailureDomains=failureDomainEdges(current.selectionSnapshot());
    Set<EdgeValue> removedFailureDomains=new java.util.TreeSet<>(oldFailureDomains);
    removedFailureDomains.removeAll(newFailureDomains);
    Set<EdgeValue> addedFailureDomains=new java.util.TreeSet<>(newFailureDomains);
    addedFailureDomains.removeAll(oldFailureDomains);

    List<Map<String,Object>> allChanges=new java.util.ArrayList<>();
    for (Map<String,Object> change:dependencyTargetChanges)
      allChanges.add(changeEntry("dependency_target_changed",change.get("from"),change.get("to")));
    for (EdgeValue edge:addedDependencies)
      allChanges.add(changeEntry("dependency_added",null,edge.asMap()));
    for (EdgeValue edge:removedDependencies)
      allChanges.add(changeEntry("dependency_removed",edge.asMap(),null));
    for (EdgeValue edge:addedFailureDomains)
      allChanges.add(changeEntry("failure_domain_membership_added",null,edge.asMap()));
    for (EdgeValue edge:removedFailureDomains)
      allChanges.add(changeEntry("failure_domain_membership_removed",edge.asMap(),null));
    allChanges.sort(java.util.Comparator.comparing(value->value.get("change_type").toString()
        +json.writeValueAsString(value.get("before"))+json.writeValueAsString(value.get("after"))));
    boolean changesTruncated=allChanges.size()>MAX_RESULT_CHANGES;
    List<Map<String,Object>> changes=List.copyOf(allChanges.stream()
        .limit(MAX_RESULT_CHANGES).toList());

    String classification;
    if (!dependencyTargetChanges.isEmpty()
        || !addedFailureDomains.isEmpty() || !removedFailureDomains.isEmpty()) {
      classification="target-changing";
    } else if (!addedDependencies.isEmpty() && removedDependencies.isEmpty()) {
      classification="widening";
    } else if (!removedDependencies.isEmpty() && addedDependencies.isEmpty()) {
      classification="narrowing";
    } else {
      classification="indeterminate";
    }
    boolean coverageUnknown=Boolean.TRUE.equals(authorizedSnapshot.get("truncated"))
        || Boolean.TRUE.equals(current.selectionSnapshot().get("truncated"))
        || !Boolean.TRUE.equals(authorizedSnapshot.get("catalog_membership_supported"))
        || !Boolean.TRUE.equals(current.selectionSnapshot().get("catalog_membership_supported"))
        || !asList(authorizedSnapshot.get("missing_workload_roots")).isEmpty()
        || !asList(current.selectionSnapshot().get("missing_workload_roots")).isEmpty()
        || !evidenceComplete(authorizedSnapshot.get("s04_summary_coverage"))
        || !evidenceComplete(current.selectionSnapshot().get("s04_summary_coverage"))
        || changesTruncated;
    String completeness=classification.equals("indeterminate") || coverageUnknown
        ?"INDETERMINATE":"INCOMPLETE";

    Map<String,Object> result=new LinkedHashMap<>();
    result.put("kind","GRAPH_DIFFERENCE_CLASSIFICATION");
    result.put("outcome",completeness);
    result.put("classification",classification);
    result.put("complete",false);
    result.put("advisory_only",true);
    result.put("service_id",claim.binding().get("service_id"));
    result.put("authorized_graph_revision_id",claim.binding().get("authorized_graph_revision_id"));
    result.put("authorized_graph_digest",claim.binding().get("authorized_graph_digest"));
    result.put("current_graph_revision_id",currentGraphId);
    result.put("current_graph_digest",currentGraphDigest);
    result.put("selection_digest",claim.binding().get("selection_digest"));
    result.put("selection_revision",claim.binding().get("selection_revision"));
    result.put("current_selection_digest",current.selectionDigest());
    result.put("dependency_additions",addedDependencies.stream().map(EdgeValue::asMap).toList());
    result.put("dependency_removals",removedDependencies.stream().map(EdgeValue::asMap).toList());
    result.put("dependency_target_changes",dependencyTargetChanges);
    result.put("failure_domain_additions",addedFailureDomains.stream().map(EdgeValue::asMap).toList());
    result.put("failure_domain_removals",removedFailureDomains.stream().map(EdgeValue::asMap).toList());
    result.put("changes",changes);
    result.put("changes_truncated",changesTruncated);
    result.put("supported_rules",List.of(
        "added DEPENDS_ON edges classify as widening",
        "removed DEPENDS_ON edges classify as narrowing",
        "a changed DEPENDS_ON target or MEMBER_OF_FAILURE_DOMAIN membership classifies as target-changing",
        "legacy IN_FAILURE_DOMAIN membership is interpreted as MEMBER_OF_FAILURE_DOMAIN",
        "no supported delta or mixed unpaired additions/removals classify as indeterminate"));
    result.put("unsupported_domains",unsupportedDomains());
    result.put("authorized_evidence_coverage",authorizedSnapshot.get("s04_summary_coverage"));
    result.put("current_evidence_coverage",current.selectionSnapshot().get("s04_summary_coverage"));
    result.put("freshness_limitation",
        "S04 supplies no evidence-expiration policy. observed_at is retained metadata; QUALIFIED does not certify freshness.");
    result.put("coverage_limitation",
        "The supported graph delta is only a bounded dependency/failure-domain advisory; all listed ontology and freshness domains remain unimplemented.");
    result.put("summary","Supported graph delta classified as "+classification
        +" with "+changes.size()+" bounded change entries; overall coverage is "+completeness+".");
    result.put("reason_code",classification.equals("indeterminate")
        ?"NO_DETERMINATE_SUPPORTED_DELTA":coverageUnknown
        ?"SUPPORTED_DELTA_WITH_INCOMPLETE_COVERAGE":"SUPPORTED_DELTA_WITH_INCOMPLETE_ONTOLOGY");
    Map<String,Object> comparisonSnapshot=new LinkedHashMap<>();
    comparisonSnapshot.put("service_id",claim.binding().get("service_id"));
    comparisonSnapshot.put("authorized_graph_revision_id",claim.binding().get("authorized_graph_revision_id"));
    comparisonSnapshot.put("authorized_graph_digest",claim.binding().get("authorized_graph_digest"));
    comparisonSnapshot.put("current_graph_revision_id",currentGraphId);
    comparisonSnapshot.put("current_graph_digest",currentGraphDigest);
    comparisonSnapshot.put("selection_digest",claim.binding().get("selection_digest"));
    comparisonSnapshot.put("selection_revision",claim.binding().get("selection_revision"));
    comparisonSnapshot.put("authorized_selection_snapshot",authorizedSnapshot);
    comparisonSnapshot.put("current_selection_snapshot",current.selectionSnapshot());
    comparisonSnapshot.put("classification",classification);
    comparisonSnapshot.put("dependency_additions",addedDependencies.stream().map(EdgeValue::asMap).toList());
    comparisonSnapshot.put("dependency_removals",removedDependencies.stream().map(EdgeValue::asMap).toList());
    comparisonSnapshot.put("dependency_target_changes",dependencyTargetChanges);
    comparisonSnapshot.put("failure_domain_additions",addedFailureDomains.stream().map(EdgeValue::asMap).toList());
    comparisonSnapshot.put("failure_domain_removals",removedFailureDomains.stream().map(EdgeValue::asMap).toList());
    comparisonSnapshot.put("changes",changes);
    comparisonSnapshot.put("changes_truncated",changesTruncated);
    comparisonSnapshot.put("authorized_evidence_coverage",authorizedSnapshot.get("s04_summary_coverage"));
    comparisonSnapshot.put("current_evidence_coverage",current.selectionSnapshot().get("s04_summary_coverage"));
    String resultDigest=selectionDigest(comparisonSnapshot);
    result.put("result_digest",resultDigest);
    return new Computation(result,null,comparisonSnapshot,null,resultDigest);
  }

  private void succeed(Claim claim,Computation computation) {
    String resultJson=json.writeValueAsString(computation.result());
    String snapshotJson=computation.selectionSnapshot()==null?null:
        json.writeValueAsString(computation.selectionSnapshot());
    String comparisonJson=computation.comparisonSnapshot()==null?null:
        json.writeValueAsString(computation.comparisonSnapshot());
    transactions.executeWithoutResult(status -> {
      int updated=db.update("UPDATE "+S+"graph_command SET status='SUCCEEDED',"
          +"terminal_outcome='SUCCEEDED',result=?::jsonb,updated_at=now() "
          +"WHERE command_id=?::uuid AND status='RUNNING'",resultJson,claim.commandId());
      if (updated!=1) return;
      db.update("UPDATE "+S+"graph_domain_revision SET status='SUCCEEDED',"
          +"selection_digest=COALESCE(?,selection_digest),selection_snapshot=COALESCE(?::jsonb,selection_snapshot),"
          +"comparison_snapshot=COALESCE(?::jsonb,comparison_snapshot),"
          +"result=?::jsonb,completed_at=now() WHERE command_id=?::uuid AND status='PENDING'",
          computation.selectionDigest(),snapshotJson,comparisonJson,resultJson,claim.commandId());
      db.update("UPDATE "+S+"graph_domain_head h SET current_revision=d.revision,"
          +"pending_command_id=NULL,updated_at=now() FROM "+S+"graph_domain_revision d "
          +"WHERE d.command_id=?::uuid AND h.tenant_id=d.tenant_id AND h.environment_id=d.environment_id "
          +"AND h.operation=d.operation AND h.target_key=d.target_key AND h.pending_command_id=?::uuid",
          claim.commandId(),claim.commandId());
      db.update("UPDATE "+S+"graph_command_outbox SET processed_at=now(),claimed_at=NULL,"
          +"lease_owner=NULL,lease_expires_at=NULL,last_error=NULL "
          +"WHERE event_id=?::uuid AND lease_owner=? AND processed_at IS NULL",
          claim.eventId(),owner);
    });
  }

  private void fail(Claim claim,String reason) {
    transactions.executeWithoutResult(status -> {
      int updated=db.update("UPDATE "+S+"graph_command SET status='FAILED',terminal_outcome='FAILED',"
          +"result=NULL,updated_at=now() WHERE command_id=?::uuid AND status='RUNNING'",
          claim.commandId());
      if (updated!=1) return;
      db.update("DELETE FROM "+S+"graph_domain_revision WHERE command_id=?::uuid AND status='PENDING'",
          claim.commandId());
      db.update("UPDATE "+S+"graph_domain_head SET pending_command_id=NULL,updated_at=now() "
          +"WHERE tenant_id=? AND environment_id=? AND operation=? AND target_key=? AND pending_command_id=?::uuid",
          claim.tenantId(),claim.environmentId(),claim.operation(),claim.targetKey(),claim.commandId());
      db.update("UPDATE "+S+"graph_command_outbox SET processed_at=now(),claimed_at=NULL,"
          +"lease_owner=NULL,lease_expires_at=NULL,last_error=? "
          +"WHERE event_id=?::uuid AND lease_owner=? AND processed_at IS NULL",
          reason,claim.eventId(),owner);
    });
  }

  private void retryOrFail(Claim claim,RuntimeException error) {
    if (claim.attemptCount()>=MAX_ATTEMPTS) {
      fail(claim,"MAX_RETRIES_EXCEEDED: "+error.getClass().getSimpleName());
      return;
    }
    long delaySeconds=Math.min(60,1L<<Math.min(6,claim.attemptCount()-1));
    transactions.executeWithoutResult(status -> {
      int updated=db.update("UPDATE "+S+"graph_command SET status='RETRY_SCHEDULED',"
          +"terminal_outcome=NULL,updated_at=now() WHERE command_id=?::uuid AND status='RUNNING'",
          claim.commandId());
      if (updated==1) db.update("UPDATE "+S+"graph_command_outbox SET claimed_at=NULL,"
          +"lease_owner=NULL,lease_expires_at=NULL,next_attempt_at=clock_timestamp()+(? * interval '1 second'),"
          +"last_error=? WHERE event_id=?::uuid AND lease_owner=? AND processed_at IS NULL",
          delaySeconds,error.getClass().getSimpleName(),claim.eventId(),owner);
    });
  }

  private static List<String> unsupportedDomains() {
    return List.of(
        "SLOs and versioned measurement definitions",
        "customer journeys, journey steps, and customer segments",
        "S02 service-revision snapshots do not bind journey membership, capability links, customer segments, or related catalog associations",
        "impact and outcome measures",
        "controls and enforcement points",
        "approved fallbacks, recovery paths, certification, expiry, and recertification",
        "API, data-store, queue, feature-pipeline, team, provider, essential-function, and outcome graph objects",
        "bounded S04 summaries with an operation-specific freshness threshold");
  }

  private String selectionDigest(Map<String,Object> value) {
    return GraphDigest.sha256(OwnerReceiptSignature.canonicalize(json.writeValueAsString(value)));
  }

  private boolean hasNode(Claim claim,String graphId,String entityType,String entityId) {
    if (entityId==null) return false;
    Integer count=db.queryForObject("SELECT count(*) FROM "+S+"graph_nodes WHERE tenant_id=? "
        +"AND environment_id=? AND graph_id=? AND entity_type=? AND entity_id=?",
        Integer.class,claim.tenantId(),claim.environmentId(),graphId,entityType,entityId);
    return count!=null && count>0;
  }

  private Map<String,Object> mapValue(Object value) {
    if (!(value instanceof Map<?,?> raw)) return Map.of();
    @SuppressWarnings("unchecked") Map<String,Object> result=(Map<String,Object>)raw;
    return result;
  }

  private List<?> asList(Object value) {
    return value instanceof List<?> list?list:List.of();
  }

  private boolean evidenceComplete(Object value) {
    return value instanceof Map<?,?> map && Boolean.TRUE.equals(map.get("complete"));
  }

  private Set<EdgeValue> edges(Map<String,Object> snapshot,String relation) {
    java.util.TreeSet<EdgeValue> result=new java.util.TreeSet<>();
    for (Object value:asList(snapshot.get("edges"))) {
      if (!(value instanceof Map<?,?> edge) || !relation.equals(edge.get("relationship_type"))) continue;
      if (edge.get("source_type") instanceof String sourceType
          && edge.get("source_id") instanceof String sourceId
          && edge.get("target_type") instanceof String targetType
          && edge.get("target_id") instanceof String targetId) {
        result.add(new EdgeValue(sourceType,sourceId,relation,targetType,targetId));
      }
    }
    return result;
  }

  private Set<EdgeValue> failureDomainEdges(Map<String,Object> snapshot) {
    java.util.TreeSet<EdgeValue> result=new java.util.TreeSet<>();
    for (Object value:asList(snapshot.get("edges"))) {
      if (!(value instanceof Map<?,?> edge)
          || !FAILURE_DOMAIN_RELATIONSHIPS.contains(edge.get("relationship_type"))) continue;
      if (edge.get("source_type") instanceof String sourceType
          && edge.get("source_id") instanceof String sourceId
          && edge.get("target_type") instanceof String targetType
          && edge.get("target_id") instanceof String targetId) {
        result.add(new EdgeValue(sourceType,sourceId,"MEMBER_OF_FAILURE_DOMAIN",targetType,targetId));
      }
    }
    return result;
  }

  private Map<String,Object> changeEntry(String type,Object before,Object after) {
    Map<String,Object> value=new LinkedHashMap<>();
    value.put("change_type",type);
    if (before!=null) value.put("before",before);
    if (after!=null) value.put("after",after);
    return value;
  }

  private Map<String,Object> readObject(String value) {
    return json.readValue(value,Map.class);
  }

  private record Computation(Map<String,Object> result,Map<String,Object> selectionSnapshot,
      Map<String,Object> comparisonSnapshot,String selectionDigest,String resultDigest) {}

  private record EdgeValue(String sourceType,String sourceId,String relationshipType,
      String targetType,String targetId) implements Comparable<EdgeValue> {
    boolean sameSourceAndRelationship(EdgeValue other) {
      return sourceType.equals(other.sourceType) && sourceId.equals(other.sourceId)
          && relationshipType.equals(other.relationshipType);
    }
    boolean sameTarget(EdgeValue other) {
      return targetType.equals(other.targetType) && targetId.equals(other.targetId);
    }
    Map<String,Object> asMap() {
      return Map.of("source_type",sourceType,"source_id",sourceId,
          "relationship_type",relationshipType,"target_type",targetType,"target_id",targetId);
    }
    @Override public int compareTo(EdgeValue other) {
      int value=sourceType.compareTo(other.sourceType);
      if (value==0) value=sourceId.compareTo(other.sourceId);
      if (value==0) value=relationshipType.compareTo(other.relationshipType);
      if (value==0) value=targetType.compareTo(other.targetType);
      if (value==0) value=targetId.compareTo(other.targetId);
      return value;
    }
  }

  private record Claim(String eventId,String commandId,String tenantId,String environmentId,
      String operation,Map<String,Object> binding,String targetKey,long revision,int attemptCount) {}
}
