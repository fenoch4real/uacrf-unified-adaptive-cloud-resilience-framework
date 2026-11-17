package org.uacrf.topology;

import static org.uacrf.topology.TopologyRequests.*;
import static org.uacrf.topology.TopologyResponses.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uacrf.security.DelegationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import tools.jackson.databind.ObjectMapper;

@Service
class TopologyService {
  private static final String S = "uacrf_topology.";
  private static final String PC = "id,source_type,source_id,relationship_type,target_type,target_id,provenance_kind,source_system,source_revision,verified_at,status,proposer,reviewer,revision";
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final GraphProjectionStore projectionStore;
  private final TopologyProofClient proofs;
  TopologyService(JdbcTemplate db, ObjectMapper json, GraphProjectionStore projectionStore, TopologyProofClient proofs) { this.db=db; this.json=json; this.projectionStore=projectionStore; this.proofs=proofs; }

  @Transactional
  Proposal proposeEvidence(Jwt jwt, EvidenceBackedPropose r) {
    DelegationPrincipal a=DelegationPrincipal.from(jwt);
    permit(a,"topology.proposal.write",true);
    requireHuman(a);
    if (!"agent_workload".equals(r.sourceType()) || !"agent_workload".equals(r.targetType()) || !"DEPENDS_ON".equals(r.relationshipType()))
      throw bad("INVALID_RELATIONSHIP","Evidence-backed proposals only support agent workload dependencies.");
    var expected=new TopologyProofClient.Binding(r.sourceId(),r.sourceVersion(),r.targetId(),r.targetVersion(),r.evidenceId(),r.evidenceDigest(),r.evidenceRevision(),null,null,null);
    Instant deadline=Instant.now().plusSeconds(5);
    var proof=proofs.prepare(jwt,TopologyProofClient.PROPOSE,expected,deadline);
    db.update("INSERT INTO "+S+"dependency_proposals (tenant_id,environment_id,id,source_type,source_id,relationship_type,target_type,target_id,provenance_kind,status,proposer,revision) VALUES (?,?,?,?,?,?,?,?,'AUTHORED','PROPOSED',?,1)",a.tenantId(),a.environmentId(),r.id(),r.sourceType(),r.sourceId(),r.relationshipType(),r.targetType(),r.targetId(),a.principalId());
    db.update("INSERT INTO "+S+"proposal_evidence (tenant_id,environment_id,proposal_id,source_version,target_version,evidence_id,evidence_digest,evidence_revision,source_receipt,target_receipt,summary) VALUES (?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb)",a.tenantId(),a.environmentId(),r.id(),r.sourceVersion(),r.targetVersion(),r.evidenceId(),r.evidenceDigest(),r.evidenceRevision(),json.writeValueAsString(proof.sourceReceipt()),json.writeValueAsString(proof.targetReceipt()),json.writeValueAsString(proof.summary()));
    Proposal result=findProposal(a,r.id());
    checkProof(a,TopologyProofClient.PROPOSE,proof,deadline);
    return result;
  }

  @Transactional
  Proposal propose(DelegationPrincipal a, Propose r) {
    permit(a,"topology.proposal.write",true);
    boolean any = r.sourceSystem()!=null || r.sourceRevision()!=null || r.verifiedAt()!=null;
    if (r.provenanceKind()==ProvenanceKind.AUTHORED && any || r.provenanceKind()==ProvenanceKind.OBSERVED && (r.sourceSystem()==null || r.sourceSystem().isBlank() || r.sourceRevision()==null || r.sourceRevision().isBlank() || r.verifiedAt()==null))
      throw bad("INVALID_PROVENANCE", "Observed provenance requires source system, revision and verification time; authored provenance must be absent.");
    Integer allowed=db.queryForObject("SELECT count(*) FROM "+S+"permitted_relationships WHERE source_type=? AND relationship_type=? AND target_type=?",Integer.class,r.sourceType(),r.relationshipType(),r.targetType());
    if (allowed==null || allowed==0) throw bad("INVALID_RELATIONSHIP", "The relationship endpoint roles are not permitted.");
    db.update("INSERT INTO "+S+"dependency_proposals (tenant_id,environment_id,id,source_type,source_id,relationship_type,target_type,target_id,provenance_kind,source_system,source_revision,verified_at,status,proposer,revision) VALUES (?,?,?,?,?,?,?,?,?,?,?,?, 'PROPOSED',?,1)", a.tenantId(),a.environmentId(),r.id(),r.sourceType(),r.sourceId(),r.relationshipType(),r.targetType(),r.targetId(),r.provenanceKind().name(),r.sourceSystem(),r.sourceRevision(),r.verifiedAt()==null?null:Timestamp.from(r.verifiedAt()),a.principalId());
    return findProposal(a,r.id());
  }
  @Transactional
  Proposal review(Jwt jwt, String id, Review r) {
    DelegationPrincipal a=DelegationPrincipal.from(jwt);
    permit(a,"topology.proposal.review",true);
    List<Map<String,Object>> rows=db.queryForList("SELECT proposer,status,revision FROM "+S+"dependency_proposals WHERE tenant_id=? AND environment_id=? AND id=? FOR UPDATE",a.tenantId(),a.environmentId(),id);
    if(rows.isEmpty()) throw missing();
    Map<String,Object> row=rows.getFirst();
    if(a.principalId().equals(row.get("proposer"))) throw conflict("REVIEWER_NOT_INDEPENDENT", "The proposer cannot review their own proposal.");
    if(((Number)row.get("revision")).longValue()!=r.expectedRevision()) throw conflict("REVISION_MISMATCH", "The proposal revision changed.");
    if(!row.get("status").equals("PROPOSED")) throw conflict("INVALID_STATE", "The proposal is already reviewed.");
    Proposal proposal=findProposal(a,id);
    TopologyProofClient.Binding binding=binding(a,proposal);
    if(binding!=null) requireHuman(a);
    Instant deadline=Instant.now().plusSeconds(5);
    TopologyProofClient.Binding checked=binding==null?null:proofs.prepare(jwt,TopologyProofClient.REVIEW,binding,deadline);
    db.update("UPDATE "+S+"dependency_proposals SET status=?,reviewer=?,revision=revision+1,reviewed_at=now() WHERE tenant_id=? AND environment_id=? AND id=?",r.decision()==Decision.ACCEPT?"ACCEPTED":"REJECTED",a.principalId(),a.tenantId(),a.environmentId(),id);
    Proposal result=findProposal(a,id);
    if(checked!=null) checkProof(a,TopologyProofClient.REVIEW,checked,deadline);
    return result;
  }
  @Transactional(readOnly=true)
  Proposal proposal(DelegationPrincipal a,String id) { permit(a,"topology.graph.read",false); return findProposal(a,id); }
  private Proposal findProposal(DelegationPrincipal a,String id) {
    List<Proposal> rows=db.query("SELECT "+PC+" FROM "+S+"dependency_proposals WHERE tenant_id=? AND environment_id=? AND id=?",(rs,n)->proposalRow(rs),a.tenantId(),a.environmentId(),id);
    if(rows.isEmpty()) throw missing(); return enrich(a,rows.getFirst());
  }
  private static Proposal proposalRow(java.sql.ResultSet rs) throws java.sql.SQLException {
    Timestamp t=rs.getTimestamp("verified_at");
    return new Proposal(rs.getString("id"),rs.getString("source_type"),rs.getString("source_id"),rs.getString("relationship_type"),rs.getString("target_type"),rs.getString("target_id"),rs.getString("provenance_kind"),rs.getString("source_system"),rs.getString("source_revision"),t==null?null:t.toInstant().toString(),rs.getString("status"),rs.getString("proposer"),rs.getString("reviewer"),rs.getLong("revision"),null);
  }
  private Map<String,Object> evidenceRow(DelegationPrincipal a,String id) {
    List<Map<String,Object>> rows=db.queryForList("SELECT source_version,target_version,evidence_id,evidence_digest,evidence_revision,source_receipt::text,target_receipt::text,summary::text,invalidated FROM "+S+"proposal_evidence WHERE tenant_id=? AND environment_id=? AND proposal_id=?",a.tenantId(),a.environmentId(),id);
    return rows.isEmpty()?null:rows.getFirst();
  }
  @SuppressWarnings("unchecked")
  private Map<String,Object> object(Object value) { return json.readValue((String)value,Map.class); }
  private Proposal enrich(DelegationPrincipal a,Proposal p) {
    Map<String,Object> e=evidenceRow(a,p.id());
    if(e==null) return p;
    var details=new EvidenceDetails((String)e.get("source_version"),(String)object(e.get("source_receipt")).get("receipt_id"),(String)e.get("target_version"),(String)object(e.get("target_receipt")).get("receipt_id"),(String)e.get("evidence_id"),(String)e.get("evidence_digest"),((Number)e.get("evidence_revision")).longValue(),object(e.get("summary")));
    return new Proposal(p.id(),p.sourceType(),p.sourceId(),p.relationshipType(),p.targetType(),p.targetId(),p.provenanceKind(),p.sourceSystem(),p.sourceRevision(),p.verifiedAt(),p.status(),p.proposer(),p.reviewer(),p.revision(),details);
  }
  private TopologyProofClient.Binding binding(DelegationPrincipal a,Proposal p) {
    Map<String,Object> e=evidenceRow(a,p.id());
    if(e==null) return null;
    if(Boolean.TRUE.equals(e.get("invalidated"))) throw TopologyProofClient.failure();
    return new TopologyProofClient.Binding(p.sourceId(),(String)e.get("source_version"),p.targetId(),(String)e.get("target_version"),(String)e.get("evidence_id"),(String)e.get("evidence_digest"),((Number)e.get("evidence_revision")).longValue(),object(e.get("source_receipt")),object(e.get("target_receipt")),object(e.get("summary")));
  }
  private void checkProof(DelegationPrincipal actor,String purpose,TopologyProofClient.Binding proof,Instant deadline) {
    Instant now=Instant.now();
    if(!now.isBefore(deadline)) throw TopologyProofClient.failure();
    proofs.validateReceipt(proof.sourceReceipt(),actor,purpose,proof.sourceId(),proof.sourceVersion(),deadline,now);
    proofs.validateReceipt(proof.targetReceipt(),actor,purpose,proof.targetId(),proof.targetVersion(),deadline,now);
  }
  @Transactional(readOnly=true)
  Page<Proposal> list(DelegationPrincipal a,int size,String cursor) {
    permit(a,"topology.graph.read",false);
    if(size<1||size>100) throw bad("INVALID_REQUEST","pageSize must be between 1 and 100.");
    String after="";
    if(cursor!=null && !cursor.isBlank()) try { after=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8); if(after.isBlank()||after.length()>200) throw new IllegalArgumentException(); } catch(IllegalArgumentException ex) { throw bad("INVALID_REQUEST","Invalid cursor."); }
    List<Proposal> rows=db.query("SELECT "+PC+" FROM "+S+"dependency_proposals WHERE tenant_id=? AND environment_id=? AND id>? ORDER BY id LIMIT ?",(rs,n)->proposalRow(rs),a.tenantId(),a.environmentId(),after,size+1);
    boolean more=rows.size()>size; List<Proposal> items=more?List.copyOf(rows.subList(0,size)):rows;
    return new Page<>(items.stream().map(p->enrich(a,p)).toList(),more?Base64.getUrlEncoder().withoutPadding().encodeToString(items.getLast().id().getBytes(StandardCharsets.UTF_8)):null);
  }
  private record EdgeKey(String st,String si,String rel,String tt,String ti) implements Comparable<EdgeKey> {
    public int compareTo(EdgeKey other) { return compare(List.of(st,si,rel,tt,ti),List.of(other.st,other.si,other.rel,other.tt,other.ti)); }
  }
  private record NodeKey(String type,String id) implements Comparable<NodeKey> {
    public int compareTo(NodeKey other) { return compare(List.of(type,id),List.of(other.type,other.id)); }
    Node node() { return new Node(type,id); }
  }
  private static int compare(List<String> a,List<String> b) { for(int i=0;i<a.size();i++){ int c=a.get(i).compareTo(b.get(i)); if(c!=0)return c; } return 0; }
  private static EdgeKey key(Proposal p) { return new EdgeKey(p.sourceType(),p.sourceId(),p.relationshipType(),p.targetType(),p.targetId()); }
  private static EdgeKey key(Edge e) { return new EdgeKey(e.sourceType(),e.sourceId(),e.relationshipType(),e.targetType(),e.targetId()); }
  private static List<Object> provenance(Proposal p) { return Arrays.asList(p.provenanceKind(),p.sourceSystem(),p.sourceRevision(),p.verifiedAt()); }
  @Transactional
  Graph accept(DelegationPrincipal a,Accept r) {
    permit(a,"topology.graph.accept",true);
    db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",a.tenantId().length()+":"+a.tenantId()+a.environmentId().length()+":"+a.environmentId()+r.scope().length()+":"+r.scope());
    List<Map<String,Object>> latest=db.queryForList("SELECT id,revision_number,digest FROM "+S+"graph_revisions WHERE tenant_id=? AND environment_id=? AND scope=? ORDER BY revision_number DESC LIMIT 1",a.tenantId(),a.environmentId(),r.scope());
    long current=latest.isEmpty()?0:((Number)latest.getFirst().get("revision_number")).longValue();
    if(current!=r.expectedRevisionNumber()) throw conflict("REVISION_MISMATCH","The graph revision changed.");
    List<Proposal> proposals=db.query("SELECT "+PC+" FROM "+S+"dependency_proposals WHERE tenant_id=? AND environment_id=? AND status='ACCEPTED' ORDER BY reviewed_at, id COLLATE \"C\"",(rs,n)->proposalRow(rs),a.tenantId(),a.environmentId());
    TreeMap<EdgeKey,String> edges=new TreeMap<>();
    if(!latest.isEmpty()) for(Edge e:edges(a,(String)latest.getFirst().get("id"))) edges.put(key(e),e.proposalId());
    Map<EdgeKey,List<Proposal>> grouped=new TreeMap<>();
    List<Proposal> evidenceBacked=new ArrayList<>();
    for(Proposal p:proposals.stream().sorted(Comparator.comparing(Proposal::id)).toList()) {
      Map<String,Object> support=evidenceRow(a,p.id());
      if(support!=null && !Boolean.TRUE.equals(support.get("invalidated"))) evidenceBacked.add(p);
      else if(support==null) edges.putIfAbsent(key(p),p.id());
      grouped.computeIfAbsent(key(p),x->new ArrayList<>()).add(p);
    }
    Set<String> supported=evidenceBacked.stream().map(Proposal::id).collect(java.util.stream.Collectors.toSet());
    for(Proposal p:proposals) if(supported.contains(p.id())) edges.put(key(p),p.id());
    TreeSet<NodeKey> nodes=new TreeSet<>();
    for(EdgeKey e:edges.keySet()){nodes.add(new NodeKey(e.st,e.si)); nodes.add(new NodeKey(e.tt,e.ti));}
    List<Node> graphNodes=nodes.stream().map(NodeKey::node).toList();
    List<Edge> graphEdges=edges.entrySet().stream().map(entry -> { EdgeKey e=entry.getKey(); return new Edge(e.st,e.si,e.rel,e.tt,e.ti,entry.getValue()); }).toList();
    String digest=GraphDigest.digest(graphNodes,graphEdges);
    boolean same=!latest.isEmpty() && digest.equals(latest.getFirst().get("digest"));
    String graphId=same?(String)latest.getFirst().get("id"):UUID.randomUUID().toString();
    long revision=same?current:current+1;
    if(!same) {
      db.update("INSERT INTO "+S+"graph_revisions (tenant_id,environment_id,id,scope,revision_number,digest,accepted_by,digest_version) VALUES (?,?,?,?,?,?,?,2)",a.tenantId(),a.environmentId(),graphId,r.scope(),revision,digest,a.principalId());
      db.update("INSERT INTO "+S+"graph_projections (tenant_id,environment_id,graph_id,status) VALUES (?,?,?,'PENDING')",a.tenantId(),a.environmentId(),graphId);
      for(NodeKey n:nodes) db.update("INSERT INTO "+S+"graph_nodes VALUES (?,?,?,?,?)",a.tenantId(),a.environmentId(),graphId,n.type,n.id);
      for(var entry:edges.entrySet()) { EdgeKey e=entry.getKey(); db.update("INSERT INTO "+S+"graph_edges VALUES (?,?,?,?,?,?,?,?,?)",a.tenantId(),a.environmentId(),graphId,e.st,e.si,e.rel,e.tt,e.ti,entry.getValue()); }
      event(a,graphId,revision,digest,"uacrf.s06.graph.accepted.v1",Map.of("scope",r.scope(),"status","ACCEPTED"));
    }
    for(List<Proposal> group:grouped.values()) {
      group.sort(Comparator.comparing(Proposal::id));
      for(int i=0;i<group.size();i++) for(int j=i+1;j<group.size();j++) {
      Proposal x=group.get(i), y=group.get(j);
      if(!provenance(x).equals(provenance(y))) {
        int inserted=db.update("INSERT INTO "+S+"graph_edge_conflicts (tenant_id,environment_id,graph_id,proposal_id,conflicting_proposal_id) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING",a.tenantId(),a.environmentId(),graphId,x.id(),y.id());
        if(inserted>0) event(a,graphId,revision,digest,"uacrf.s06.graph.conflict.v1",Map.of("proposal_id",x.id(),"conflicting_proposal_id",y.id()));
      }
    }
    }
    return findGraph(a,graphId);
  }
  private void event(DelegationPrincipal a,String id,long revision,String digest,String type,Map<String,Object> details) {
    Map<String,Object> body=new LinkedHashMap<>(); body.put("owned_resource_id",id); body.put("owned_revision",revision); body.put("owned_digest",digest); body.put("minimal_outcome_metadata",details);
    String payload=json.writeValueAsString(body);
    db.update("INSERT INTO "+S+"outbox_event (event_id,tenant_id,environment_id,aggregate_type,aggregate_id,aggregate_sequence,event_type,payload,payload_digest) VALUES (?,?,?,'GRAPH',?,?,?,?::jsonb,?)",UUID.randomUUID(),a.tenantId(),a.environmentId(),id,revision,type,payload,GraphDigest.sha256(payload));
  }
  @Transactional(readOnly=true)
  Graph graph(DelegationPrincipal a,String id) { permit(a,"topology.graph.read",false); return findGraph(a,id); }
  private Graph findGraph(DelegationPrincipal a,String id) {
    List<Map<String,Object>> rows=db.queryForList("SELECT scope,revision_number,digest,accepted_by,accepted_at FROM "+S+"graph_revisions WHERE tenant_id=? AND environment_id=? AND id=?",a.tenantId(),a.environmentId(),id);
    if(rows.isEmpty()) throw missing(); Map<String,Object> r=rows.getFirst();
    List<Node> nodes=db.query("SELECT entity_type,entity_id FROM "+S+"graph_nodes WHERE tenant_id=? AND environment_id=? AND graph_id=? ORDER BY entity_type,entity_id",(rs,n)->new Node(rs.getString(1),rs.getString(2)),a.tenantId(),a.environmentId(),id);
    return new Graph(id,(String)r.get("scope"),((Number)r.get("revision_number")).longValue(),(String)r.get("digest"),(String)r.get("accepted_by"),((Timestamp)r.get("accepted_at")).toInstant().toString(),nodes,edges(a,id));
  }
  private List<Edge> edges(DelegationPrincipal a,String id) {
    return db.query("SELECT source_type,source_id,relationship_type,target_type,target_id,proposal_id FROM "+S+"graph_edges WHERE tenant_id=? AND environment_id=? AND graph_id=? ORDER BY source_type,source_id,relationship_type,target_type,target_id",(rs,n)->new Edge(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6)),a.tenantId(),a.environmentId(),id);
  }
  @Transactional
  Receipt receipt(Jwt jwt,String id) {
    DelegationPrincipal a=DelegationPrincipal.from(jwt);
    permit(a,"topology.graph.read",false);
    Graph g=findGraph(a,id);
    List<Map<String,Object>> rows=db.queryForList("SELECT status,verified_digest FROM "+S+"graph_projections WHERE tenant_id=? AND environment_id=? AND graph_id=?",a.tenantId(),a.environmentId(),id);
    if(rows.isEmpty()) throw missing();
    String projectionStatus=(String)rows.getFirst().get("status"), verified=(String)rows.getFirst().get("verified_digest");
    boolean eligible="PROJECTED".equals(projectionStatus)&&g.digest().equals(verified);
    Instant deadline=Instant.now().plusSeconds(5);
    Map<String,TopologyProofClient.Binding> checked=new LinkedHashMap<>();
    for(Edge edge:g.edges()) {
      Proposal p=findProposal(a,edge.proposalId());
      Map<String,Object> row=evidenceRow(a,p.id());
      if(row==null) continue;
      try {
        db.queryForList("SELECT proposal_id FROM "+S+"proposal_evidence WHERE tenant_id=? AND environment_id=? AND proposal_id=? FOR UPDATE",a.tenantId(),a.environmentId(),p.id());
        var binding=binding(a,p);
        checked.put(p.id(),proofs.prepare(jwt,TopologyProofClient.CURRENT,binding,deadline));
      } catch(TopologyException failure) {
        eligible=false;
        if("PROOF_STALE".equals(failure.code()))
          db.update("UPDATE "+S+"proposal_evidence SET invalidated=true WHERE tenant_id=? AND environment_id=? AND proposal_id=?",a.tenantId(),a.environmentId(),p.id());
      }
    }
    for(var entry:checked.entrySet()) try {
      checkProof(a,TopologyProofClient.CURRENT,entry.getValue(),deadline);
    } catch(RuntimeException failure) {
      eligible=false;
    }
    return new Receipt(id,g.scope(),g.revisionNumber(),g.digest(),"ACCEPTED",projectionStatus,eligible);
  }

  @Transactional
  Reconciliation reconciliation(DelegationPrincipal a,String id) {
    permit(a,"topology.graph.read",false);
    Graph expected=findGraph(a,id);
    List<Map<String,Object>> projections=db.queryForList("SELECT status FROM "+S+"graph_projections WHERE tenant_id=? AND environment_id=? AND graph_id=?",a.tenantId(),a.environmentId(),id);
    if(projections.isEmpty()) throw missing();
    String projectionStatus=(String)projections.getFirst().get("status");
    GraphProjectionStore.GraphData actual=projectionStore.read(a.tenantId(),a.environmentId(),id);
    Map<EdgeIdentity,Edge> expectedEdges=edgeMap(expected.edges()), actualEdges=edgeMap(actual.edges());
    Set<Node> expectedNodes=new TreeSet<>(Comparator.comparing(Node::entityType).thenComparing(Node::entityId)); expectedNodes.addAll(expected.nodes());
    Set<Node> actualNodes=new TreeSet<>(Comparator.comparing(Node::entityType).thenComparing(Node::entityId)); actualNodes.addAll(actual.nodes());
    List<Node> missingNodes=difference(expectedNodes,actualNodes), extraNodes=difference(actualNodes,expectedNodes);
    List<Edge> missingEdges=edgesMissing(expectedEdges,actualEdges), extraEdges=edgesMissing(actualEdges,expectedEdges);
    Integer digestVersion=db.queryForObject("SELECT digest_version FROM "+S+"graph_revisions WHERE tenant_id=? AND environment_id=? AND id=?",Integer.class,a.tenantId(),a.environmentId(),id);
    String actualDigest=GraphDigest.digest(actual.nodes(),actual.edges(),digestVersion);
    boolean drift= !missingNodes.isEmpty() || !extraNodes.isEmpty() || !missingEdges.isEmpty() || !extraEdges.isEmpty()
        || expected.nodes().size()!=actual.nodes().size() || expected.edges().size()!=actual.edges().size()
        || !actual.contentIntegrityValid() || !expected.digest().equals(actualDigest);
    if(drift && "PROJECTED".equals(projectionStatus)) {
      db.update("UPDATE "+S+"graph_projections SET status='DRIFTED',projected_node_count=?,projected_edge_count=?,verified_digest=?,last_error='Reconciliation detected projected graph differences',updated_at=now() WHERE tenant_id=? AND environment_id=? AND graph_id=? AND status='PROJECTED'",actual.nodes().size(),actual.edges().size(),actualDigest,a.tenantId(),a.environmentId(),id);
      projectionStatus="DRIFTED";
    }
    return new Reconciliation(id,projectionStatus,expected.digest(),actualDigest,actual.contentIntegrityValid(),missingNodes,extraNodes,missingEdges,extraEdges);
  }

  private record EdgeIdentity(String sourceType,String sourceId,String relationshipType,String targetType,String targetId) {}
  private static Map<EdgeIdentity,Edge> edgeMap(List<Edge> edges) {
    Map<EdgeIdentity,Edge> result=new LinkedHashMap<>();
    for(Edge edge:edges) result.put(new EdgeIdentity(edge.sourceType(),edge.sourceId(),edge.relationshipType(),edge.targetType(),edge.targetId()),edge);
    return result;
  }
  private static <T> List<T> difference(Collection<T> left,Collection<T> right) { List<T> values=new ArrayList<>(left); values.removeAll(right); return List.copyOf(values); }
  private static List<Edge> edgesMissing(Map<EdgeIdentity,Edge> left,Map<EdgeIdentity,Edge> right) { return left.entrySet().stream().filter(entry->!right.containsKey(entry.getKey())).map(Map.Entry::getValue).toList(); }
  @Transactional(readOnly=true)
  Subgraph subgraph(DelegationPrincipal a,String id,String type,String rootId,int depth) {
    permit(a,"topology.graph.read",false); if(depth<0||depth>5) throw bad("INVALID_DEPTH","depth must be between 0 and 5.");
    Graph g=findGraph(a,id); Node root=new Node(type,rootId); if(!g.nodes().contains(root)) throw missing();
    Set<Node> seen=new LinkedHashSet<>(); seen.add(root); Set<Edge> selected=new LinkedHashSet<>(); Set<Node> frontier=Set.of(root);
    for(int i=0;i<depth;i++) { Set<Node> next=new LinkedHashSet<>(); for(Edge e:g.edges()) if(frontier.contains(new Node(e.sourceType(),e.sourceId()))) { selected.add(e); Node target=new Node(e.targetType(),e.targetId()); if(seen.add(target)) next.add(target); } frontier=next; }
    return new Subgraph(id,root,depth,List.copyOf(seen),List.copyOf(selected));
  }
  private record Walk(Node node,int flags,List<Node> path) {}
  @Transactional(readOnly=true)
  Impact impact(DelegationPrincipal a,String id,String type,String entityId) {
    permit(a,"topology.graph.read",false); Graph g=findGraph(a,id); Node root=new Node(type,entityId); if(!g.nodes().contains(root)) throw missing();
    ArrayDeque<Walk> queue=new ArrayDeque<>(); queue.add(new Walk(root,flag(root),List.of(root))); List<Path> paths=new ArrayList<>(); Set<Node> models=new LinkedHashSet<>(),identities=new LinkedHashSet<>(),cloud=new LinkedHashSet<>(),regions=new LinkedHashSet<>(),domains=new LinkedHashSet<>();
    while(!queue.isEmpty()) { Walk w=queue.remove(); paths.add(new Path(w.node,w.flags==3?"MIXED":w.flags==2?"AGENT_ONLY":"SERVICE_ONLY",w.path));
      switch(w.node.entityType()){case "model"->models.add(w.node);case "identity"->identities.add(w.node);case "cloud_resource"->cloud.add(w.node);case "region"->regions.add(w.node);case "failure_domain"->domains.add(w.node);default->{}}
      for(Edge e:g.edges()) if(e.sourceType().equals(w.node.entityType())&&e.sourceId().equals(w.node.entityId())) { Node n=new Node(e.targetType(),e.targetId()); if(w.path.contains(n)) continue; List<Node> path=new ArrayList<>(w.path); path.add(n); queue.add(new Walk(n,w.flags|flag(n),List.copyOf(path))); }
    }
    return new Impact(id,root,paths,List.copyOf(models),List.copyOf(identities),List.copyOf(cloud),List.copyOf(regions),List.copyOf(domains));
  }
  private static int flag(Node n) { return n.entityType().equals("service")?1:n.entityType().equals("agent_workload")?2:0; }
  private static void permit(DelegationPrincipal a,String capability,boolean write) { if(!a.capabilities().contains(capability)||write&&!a.serviceScope().equals("*")) throw new TopologyException(HttpStatus.FORBIDDEN,"PERMISSION_DENIED","The current principal cannot perform this topology operation."); }
  private static void requireHuman(DelegationPrincipal a) {
    if(!a.eligibleOwnerIds().contains(a.principalId()))
      throw new TopologyException(HttpStatus.FORBIDDEN,"HUMAN_IDENTITY_UNVERIFIED","A signed S01 eligible-human assertion is required.");
  }
  private static TopologyException missing() { return new TopologyException(HttpStatus.NOT_FOUND,"NOT_FOUND","The topology record does not exist."); }
  private static TopologyException bad(String code,String message) { return new TopologyException(HttpStatus.BAD_REQUEST,code,message); }
  private static TopologyException conflict(String code,String message) { return new TopologyException(HttpStatus.CONFLICT,code,message); }
}
