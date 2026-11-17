package org.uacrf.topology;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.uacrf.security.DelegationPrincipal;
import org.uacrf.security.OwnerReceiptSignature;
import tools.jackson.databind.ObjectMapper;

class TopologyProofClientTests {
  private final ObjectMapper json=new ObjectMapper();
  private final List<Map<String,Object>> exchanges=new ArrayList<>();
  private final AtomicBoolean qualified=new AtomicBoolean(true);
  private final AtomicBoolean oversized=new AtomicBoolean();
  private final AtomicInteger revision=new AtomicInteger(2);
  private final AtomicReference<Object> serviceResponseVersion=new AtomicReference<>(7);
  private final AtomicReference<Map<String,Object>> serviceSnapshot=new AtomicReference<>();
  private HttpServer server;
  private KeyPair key;
  private TopologyProofClient client;
  private final String digest="a".repeat(64);
  private Jwt jwt;
  private final String observedAt=Instant.now().minusSeconds(10).toString();

  @BeforeEach void setup() throws Exception {
    KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); key=generator.generateKeyPair();
    server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/internal/v1/delegations:exchange",exchange->{
      Map<String,Object> request=read(exchange.getRequestBody().readAllBytes()); exchanges.add(request);
      assertEquals("Bearer original",exchange.getRequestHeaders().getFirst("Authorization"));
      if (oversized.get()) {
        send(exchange,Map.of("access_token","x".repeat(20*1024)));
        return;
      }
      send(exchange,Map.of("access_token",request.get("audience")+":"+request.get("resource_id")+":"+request.get("purpose")));
    });
    server.createContext("/internal/v1/workloads/",exchange->{
      String id=exchange.getRequestURI().getPath().split("/")[4].replace(":receipt","");
      Map<String,Object> request=read(exchange.getRequestBody().readAllBytes());
      assertEquals("Bearer S02:"+id+":"+request.get("purpose"),exchange.getRequestHeaders().getFirst("Authorization"));
      send(exchange,receipt(id,(String)request.get("requested_version"),(String)request.get("purpose"),Instant.parse((String)request.get("operation_deadline")),revision.get()));
    });
    server.createContext("/internal/v1/services/",exchange->{
      String[] path=exchange.getRequestURI().getPath().split("/");
      String id=path[4];
      Map<String,Object> snapshot=serviceSnapshot.get();
      String serviceDigest=serviceDigest(snapshot);
      if ("GET".equals(exchange.getRequestMethod())) {
        assertEquals("/internal/v1/services/"+id+"/revisions/7",exchange.getRequestURI().getPath());
        assertEquals("Bearer S02:"+id+":"+TopologyProofClient.SUBGRAPH_SELECTION,
            exchange.getRequestHeaders().getFirst("Authorization"));
        send(exchange,Map.of("resource_version",serviceResponseVersion.get(),
            "content_digest",serviceDigest,"snapshot",snapshot));
        return;
      }
      id=id.replace(":receipt","");
      Map<String,Object> request=read(exchange.getRequestBody().readAllBytes());
      assertEquals("Bearer S02:"+id+":"+request.get("purpose"),exchange.getRequestHeaders().getFirst("Authorization"));
      assertEquals(java.util.Set.of("purpose","requested_version","operation_deadline"),request.keySet());
      send(exchange,receipt("service",id,(String)request.get("requested_version"),
          (String)request.get("purpose"),Instant.parse((String)request.get("operation_deadline")),
          revision.get(),serviceDigest));
    });
    server.createContext("/internal/v1/cases/",exchange->{
      String id=exchange.getRequestURI().getPath().split("/")[4].replace(":receipt","");
      assertEquals("Bearer S07:"+id+":"+TopologyProofClient.SUBGRAPH_SELECTION,exchange.getRequestHeaders().getFirst("Authorization"));
      send(exchange,Map.of("case_id",id,"purpose","incident","case_digest","d".repeat(64),
          "workload_id","workload-1","required_source_kinds",List.of("metrics")));
    });
    server.createContext("/internal/v1/qualifications/",exchange->{
      Map<String,Object> request=read(exchange.getRequestBody().readAllBytes());
      assertEquals("Bearer S04:ev:"+request.get("purpose"),exchange.getRequestHeaders().getFirst("Authorization"));
      send(exchange,Map.of("usable_as_evidence",qualified.get(),"summary",Map.of("evidence_id","ev","source_class","PROVIDER","source_ref","source","sequence",1,"observed_at",observedAt,"kind","metric","qualification_state","QUALIFIED","revision",3,"payload_digest",digest)));
    });
    server.start();
    serviceSnapshot.set(serviceSnapshot(List.of("agent-a","agent-b")));
    String base="http://127.0.0.1:"+server.getAddress().getPort();
    client=new TopologyProofClient(json,base,base,base,base,"key-1",Base64.getEncoder().encodeToString(key.getPublic().getEncoded()));
    jwt=Jwt.withTokenValue("original").header("alg","RS256").subject("principal-1").claim("tenant_id","tenant").claim("environment_id","env").claim("actor_issuer","issuer").claim("actor_subject","human-subject").claim("service_scope","*").claim("capabilities",List.of("topology.proposal.write")).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(30)).build();
  }
  @AfterEach void stop() { if(server!=null) server.stop(0); }
  private Map<String,Object> read(byte[] value) { return json.readValue(value,Map.class); }
  private void send(com.sun.net.httpserver.HttpExchange exchange,Map<String,Object> value) throws java.io.IOException {
    byte[] body=json.writeValueAsBytes(value); exchange.getResponseHeaders().set("Content-Type","application/json"); exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body); exchange.close();
  }
  private Map<String,Object> receipt(String id,String version,String purpose,Instant deadline,int rev) {
    return receipt("agent_workload",id,version,purpose,deadline,rev);
  }
  private Map<String,Object> receipt(String resourceType,String id,String version,String purpose,Instant deadline,int rev) {
    return receipt(resourceType,id,version,purpose,deadline,rev,digest);
  }
  private Map<String,Object> receipt(String resourceType,String id,String version,String purpose,
      Instant deadline,int rev,String contentDigest) {
    Instant now=Instant.now();
    Map<String,Object> value=new LinkedHashMap<>(Map.ofEntries(Map.entry("protocol","UACRF_OWNER_RECEIPT_V1"),Map.entry("key_id","key-1"),Map.entry("receipt_id","receipt-1"),Map.entry("issuer_service","S02"),Map.entry("audience_service","S06"),Map.entry("tenant_id","tenant"),Map.entry("environment_id","env"),Map.entry("actor_id","human-subject"),Map.entry("resource_type",resourceType),Map.entry("resource_id",id),Map.entry("resource_version",version),Map.entry("resource_revision",rev),Map.entry("authority_epoch",rev),Map.entry("content_digest",contentDigest),Map.entry("eligible",true),Map.entry("purpose",purpose),Map.entry("issued_at",now.toString()),Map.entry("valid_until",deadline.toString())));
    value.put("signature",OwnerReceiptSignature.sign(json.writeValueAsString(value),(RSAPrivateKey)key.getPrivate())); return value;
  }
  private TopologyProofClient.Binding binding() { return new TopologyProofClient.Binding("w1","v1","w2","v2","ev",digest,3,null,null,null); }

  @Test void exchangesExactResourcesAndRechecksRevisionAndConflict() {
    var result=client.prepare(jwt,TopologyProofClient.PROPOSE,binding(),Instant.now().plusSeconds(5));
    assertEquals(3,exchanges.size());
    assertEquals(List.of("w1","w2","ev"),exchanges.stream().map(m->m.get("resource_id")).toList());
    assertEquals(observedAt,result.summary().get("observed_at"));
    exchanges.forEach(m->assertEquals(java.util.Set.of("audience","resource_type","resource_id","purpose","operation_deadline"),m.keySet()));
    assertNotNull(client.prepare(jwt,TopologyProofClient.REVIEW,result,Instant.now().plusSeconds(5)));
    revision.set(4);
    assertThrows(TopologyException.class,()->client.prepare(jwt,TopologyProofClient.REVIEW,result,Instant.now().plusSeconds(5)));
    revision.set(2); qualified.set(false);
    assertThrows(TopologyException.class,()->client.prepare(jwt,TopologyProofClient.CURRENT,result,Instant.now().plusSeconds(5)));
  }

  @Test void verifiesExactCatalogServiceRevisionAndCurrentS07CaseReceipt() {
    Instant deadline=Instant.now().plusSeconds(5);
    String serviceDigest=serviceDigest(serviceSnapshot.get());
    var service=client.prepareServiceRevision(jwt,"svc-1","7",serviceDigest,
        TopologyProofClient.SUBGRAPH_SELECTION,deadline);
    assertEquals(List.of("agent-a","agent-b"),service.workloadIds());
    assertTrue(service.membershipSupported());
    assertEquals(List.of("agent-a","agent-b"),service.snapshot().get("owned_agent_workload_ids"));
    serviceResponseVersion.set("7");
    assertThrows(TopologyException.class,()->client.prepareServiceRevision(jwt,"svc-1","7",
        serviceDigest,TopologyProofClient.SUBGRAPH_SELECTION,Instant.now().plusSeconds(5)));
    serviceResponseVersion.set(7.5);
    assertThrows(TopologyException.class,()->client.prepareServiceRevision(jwt,"svc-1","7",
        serviceDigest,TopologyProofClient.SUBGRAPH_SELECTION,Instant.now().plusSeconds(5)));
    Map<String,Object> receipt=client.prepareCaseReceipt(jwt,"case-1",
        TopologyProofClient.SUBGRAPH_SELECTION,deadline);
    assertEquals("case-1",receipt.get("case_id"));
    assertEquals("d".repeat(64),receipt.get("case_digest"));
    assertThatExchange("S02","service","svc-1",TopologyProofClient.SUBGRAPH_SELECTION);
    assertThatExchange("S07","assessment_case","case-1",TopologyProofClient.SUBGRAPH_SELECTION);
  }

  @Test void marksOversizedServiceMembershipUnsupported() {
    serviceSnapshot.set(serviceSnapshot(java.util.stream.IntStream.range(0,10_001)
        .mapToObj(index->"agent-"+index).toList()));
    var binding=client.prepareServiceRevision(jwt,"svc-1","7",serviceDigest(serviceSnapshot.get()),
        TopologyProofClient.SUBGRAPH_SELECTION,Instant.now().plusSeconds(5));
    assertFalse(binding.membershipSupported());
    assertTrue(binding.workloadIds().isEmpty());
  }

  @Test void rejectsMalformedSignedServiceSnapshotFields() {
    Map<String,Object> malformed=new LinkedHashMap<>(serviceSnapshot.get());
    malformed.put("criticality","HIGH");
    serviceSnapshot.set(malformed);
    assertThrows(TopologyException.class,()->client.prepareServiceRevision(jwt,"svc-1","7",
        serviceDigest(malformed),TopologyProofClient.SUBGRAPH_SELECTION,Instant.now().plusSeconds(5)));
  }

  private Map<String,Object> serviceSnapshot(List<String> workloadIds) {
    Map<String,Object> snapshot=new LinkedHashMap<>();
    snapshot.put("service_id","svc-1");
    snapshot.put("name","Service");
    snapshot.put("owner_id","owner-1");
    snapshot.put("criticality",3);
    snapshot.put("lifecycle","ACTIVE");
    snapshot.put("revision",7);
    snapshot.put("owned_agent_workload_ids",workloadIds);
    return snapshot;
  }

  private String serviceDigest(Map<String,Object> snapshot) {
    String canonical=OwnerReceiptSignature.canonicalize(json.writeValueAsString(snapshot));
    try {
      return java.util.HexFormat.of().formatHex(
          java.security.MessageDigest.getInstance("SHA-256")
              .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private void assertThatExchange(String audience,String type,String id,String purpose) {
    assertTrue(exchanges.stream().anyMatch(value->audience.equals(value.get("audience"))
        && type.equals(value.get("resource_type")) && id.equals(value.get("resource_id"))
        && purpose.equals(value.get("purpose"))));
  }

  @Test void verifiesCanonicalSignatureClaimsAndDeadline() {
    Instant deadline=Instant.now().plusSeconds(5); var actor=DelegationPrincipal.from(jwt);
    Map<String,Object> valid=receipt("w1","v1",TopologyProofClient.PROPOSE,deadline,2);
    client.validateReceipt(valid,actor,TopologyProofClient.PROPOSE,"w1","v1",deadline,Instant.now());
    var forged=new LinkedHashMap<>(valid); forged.put("content_digest","b".repeat(64));
    assertThrows(TopologyException.class,()->client.validateReceipt(forged,actor,TopologyProofClient.PROPOSE,"w1","v1",deadline,Instant.now()));
    assertThrows(TopologyException.class,()->client.validateReceipt(valid,actor,TopologyProofClient.REVIEW,"w1","v1",deadline,Instant.now()));
    assertThrows(TopologyException.class,()->client.validateReceipt(valid,actor,TopologyProofClient.PROPOSE,"w2","v1",deadline,Instant.now()));
    assertThrows(TopologyException.class,()->client.validateReceipt(valid,actor,TopologyProofClient.PROPOSE,"w1","v1",deadline,deadline));
    var wrongKey=new LinkedHashMap<>(valid); wrongKey.put("key_id","unknown");
    assertThrows(TopologyException.class,()->client.validateReceipt(wrongKey,actor,TopologyProofClient.PROPOSE,"w1","v1",deadline,Instant.now()));
  }
  @Test void rejectsUnsecuredNonLoopbackOwner() {
    assertThrows(IllegalArgumentException.class,()->new TopologyProofClient(json,"http://remote.example","http://localhost:8083","http://localhost:8086","http://localhost:8089","", ""));
  }
  @Test void rejectsOversizedInternalResponses() {
    oversized.set(true);
    assertThrows(TopologyException.class,()->client.prepare(jwt,TopologyProofClient.PROPOSE,binding(),Instant.now().plusSeconds(5)));
  }
}
