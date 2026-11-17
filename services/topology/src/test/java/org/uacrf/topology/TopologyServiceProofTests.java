package org.uacrf.topology;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.oauth2.jwt.Jwt;
import tools.jackson.databind.ObjectMapper;

class TopologyServiceProofTests {
  private final JdbcTemplate db=mock(JdbcTemplate.class);
  private final TopologyProofClient proofs=mock(TopologyProofClient.class);
  private final TopologyService service=new TopologyService(db,new ObjectMapper(),mock(GraphProjectionStore.class),proofs);
  private Jwt jwt(String id) { return Jwt.withTokenValue("token").header("alg","RS256").subject(id).claim("tenant_id","t").claim("environment_id","e").claim("service_scope","*").claim("actor_issuer","issuer").claim("actor_subject",id).claim("capabilities",List.of("topology.proposal.review","topology.proposal.write","topology.graph.read")).claim("eligible_owner_ids",List.of(id)).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(30)).build(); }

  @Test void rejectsSelfReviewBeforeAnyOwnerCall() {
    when(db.queryForList(contains("SELECT proposer,status,revision"),any(Object[].class))).thenReturn(List.of(Map.of("proposer","alice","status","PROPOSED","revision",1)));
    var error=assertThrows(TopologyException.class,()->service.review(jwt("alice"),"p",new TopologyRequests.Review(TopologyRequests.Decision.ACCEPT,1L)));
    assertEquals("REVIEWER_NOT_INDEPENDENT",error.code());
    verifyNoInteractions(proofs);
  }
  @Test void rejectsUnsupportedTypedRelationshipWithoutCallingOwners() {
    var request=new TopologyRequests.EvidenceBackedPropose("p","service","a","v1","DEPENDS_ON","agent_workload","b","v2","ev","a".repeat(64),1L);
    assertThrows(TopologyException.class,()->service.proposeEvidence(jwt("alice"),request));
    verifyNoInteractions(proofs);
  }
  @Test void rejectsActorWithoutTrustedHumanAssertion() {
    Jwt actor=Jwt.withTokenValue("token").header("alg","RS256").subject("system").claim("tenant_id","t").claim("environment_id","e").claim("service_scope","*").claim("actor_issuer","issuer").claim("actor_subject","system").claim("capabilities",List.of("topology.proposal.write")).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(30)).build();
    var request=new TopologyRequests.EvidenceBackedPropose("p","agent_workload","a","v1","DEPENDS_ON","agent_workload","b","v2","ev","a".repeat(64),1L);
    var error=assertThrows(TopologyException.class,()->service.proposeEvidence(actor,request));
    assertEquals("HUMAN_IDENTITY_UNVERIFIED",error.code());
    verifyNoInteractions(proofs);
  }
  @Test void staleGraphSupportStaysInvalidAfterOwnerRecovers() throws Exception {
    AtomicBoolean invalidated=new AtomicBoolean();
    when(db.queryForList(anyString(),any(Object[].class))).thenAnswer(call->{
      String sql=call.getArgument(0);
      if(sql.contains("FROM uacrf_topology.graph_revisions")) return List.of(Map.of("scope","scope","revision_number",1,"digest","digest","accepted_by","reviewer","accepted_at",Timestamp.from(Instant.now())));
      if(sql.contains("graph_projections")) return List.of(Map.of("status","PROJECTED","verified_digest","digest"));
      if(sql.contains("FOR UPDATE")) return List.of(Map.of("proposal_id","p"));
      if(sql.contains("proposal_evidence")) return List.of(Map.of("source_version","v1","target_version","v2","evidence_id","ev","evidence_digest","a".repeat(64),"evidence_revision",3,"source_receipt","{}","target_receipt","{}","summary","{}","invalidated",invalidated.get()));
      throw new AssertionError(sql);
    });
    when(db.update(anyString(),any(Object[].class))).thenAnswer(call->{ String sql=call.getArgument(0); assertTrue(sql.contains("proposal_evidence SET invalidated=true")); invalidated.set(true); return 1; });
    when(db.query(anyString(),any(RowMapper.class),any(Object[].class))).thenAnswer(call->{
      String sql=call.getArgument(0); RowMapper<?> mapper=call.getArgument(1); ResultSet rs=mock(ResultSet.class);
      if(sql.contains("graph_nodes")) return List.of();
      if(sql.contains("graph_edges")) {
        when(rs.getString(1)).thenReturn("agent_workload"); when(rs.getString(2)).thenReturn("a"); when(rs.getString(3)).thenReturn("DEPENDS_ON"); when(rs.getString(4)).thenReturn("agent_workload"); when(rs.getString(5)).thenReturn("b"); when(rs.getString(6)).thenReturn("p"); return List.of(mapper.mapRow(rs,0));
      }
      if(sql.contains("dependency_proposals")) {
        when(rs.getString("id")).thenReturn("p"); when(rs.getString("source_type")).thenReturn("agent_workload"); when(rs.getString("source_id")).thenReturn("a"); when(rs.getString("relationship_type")).thenReturn("DEPENDS_ON"); when(rs.getString("target_type")).thenReturn("agent_workload"); when(rs.getString("target_id")).thenReturn("b"); when(rs.getString("status")).thenReturn("ACCEPTED"); when(rs.getLong("revision")).thenReturn(2L); return List.of(mapper.mapRow(rs,0));
      }
      throw new AssertionError(sql);
    });
    when(proofs.prepare(any(),eq(TopologyProofClient.CURRENT),any(),any()))
        .thenThrow(TopologyProofClient.failure()).thenThrow(TopologyProofClient.stale());
    assertFalse(service.receipt(jwt("reader"),"graph").eligibility());
    assertFalse(invalidated.get());
    assertFalse(service.receipt(jwt("reader"),"graph").eligibility());
    assertTrue(invalidated.get());
    assertFalse(service.receipt(jwt("reader"),"graph").eligibility());
    verify(proofs,times(2)).prepare(any(),eq(TopologyProofClient.CURRENT),any(),any());
    verify(db,never()).update(contains("graph_revisions"),any(Object[].class));
  }
  @Test void newerReviewedSupportCreatesRevisionForSameEdge() {
    List<Object[]> writes=new ArrayList<>();
    when(db.queryForList(anyString(),any(Object[].class))).thenAnswer(call->{
      String sql=call.getArgument(0);
      if(sql.contains("pg_advisory_xact_lock")) return List.of();
      if(sql.contains("SELECT id,revision_number,digest")) return List.of(Map.of("id","old","revision_number",1,"digest","old-digest"));
      if(sql.contains("SELECT scope,revision_number")) return List.of(Map.of("scope","scope","revision_number",2,"digest","new-digest","accepted_by","reader","accepted_at",Timestamp.from(Instant.now())));
      if(sql.contains("proposal_evidence")) return List.of(Map.of("invalidated",false));
      throw new AssertionError(sql);
    });
    when(db.update(anyString(),any(Object[].class))).thenAnswer(call->{ writes.add(call.getArguments()); return 1; });
    when(db.query(anyString(),any(RowMapper.class),any(Object[].class))).thenAnswer(call->{
      String sql=call.getArgument(0); RowMapper<?> mapper=call.getArgument(1);
      if(sql.contains("dependency_proposals")) {
        ResultSet first=proposalRow("p1"), second=proposalRow("p2");
        return List.of(mapper.mapRow(first,0),mapper.mapRow(second,1));
      }
      if(sql.contains("graph_nodes")) return List.of();
      if(sql.contains("graph_edges")) {
        ResultSet row=mock(ResultSet.class);
        when(row.getString(1)).thenReturn("agent_workload"); when(row.getString(2)).thenReturn("a"); when(row.getString(3)).thenReturn("DEPENDS_ON"); when(row.getString(4)).thenReturn("agent_workload"); when(row.getString(5)).thenReturn("b"); when(row.getString(6)).thenReturn("p1");
        return List.of(mapper.mapRow(row,0));
      }
      throw new AssertionError(sql);
    });
    Jwt accepter=Jwt.withTokenValue("token").header("alg","RS256").subject("reader").claim("tenant_id","t").claim("environment_id","e").claim("service_scope","*").claim("actor_issuer","issuer").claim("actor_subject","reader").claim("capabilities",List.of("topology.graph.accept")).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(30)).build();
    service.accept(org.uacrf.security.DelegationPrincipal.from(accepter),new TopologyRequests.Accept("scope",1L));
    assertTrue(writes.stream().anyMatch(args->args[0].toString().contains("INSERT INTO uacrf_topology.graph_revisions")));
    assertTrue(writes.stream().anyMatch(args->args[0].toString().contains("INSERT INTO uacrf_topology.graph_edges") && java.util.Arrays.asList(args).contains("p2")));
  }
  private ResultSet proposalRow(String id) throws Exception {
    ResultSet row=mock(ResultSet.class);
    when(row.getString("id")).thenReturn(id); when(row.getString("source_type")).thenReturn("agent_workload"); when(row.getString("source_id")).thenReturn("a");
    when(row.getString("relationship_type")).thenReturn("DEPENDS_ON"); when(row.getString("target_type")).thenReturn("agent_workload"); when(row.getString("target_id")).thenReturn("b"); when(row.getString("status")).thenReturn("ACCEPTED");
    return row;
  }
}
