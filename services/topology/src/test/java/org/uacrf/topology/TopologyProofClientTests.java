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
    server.createContext("/internal/v1/qualifications/",exchange->{
      Map<String,Object> request=read(exchange.getRequestBody().readAllBytes());
      assertEquals("Bearer S04:ev:"+request.get("purpose"),exchange.getRequestHeaders().getFirst("Authorization"));
      send(exchange,Map.of("usable_as_evidence",qualified.get(),"summary",Map.of("evidence_id","ev","source_class","PROVIDER","source_ref","source","sequence",1,"observed_at",observedAt,"kind","metric","qualification_state","QUALIFIED","revision",3,"payload_digest",digest)));
    });
    server.start();
    String base="http://127.0.0.1:"+server.getAddress().getPort();
    client=new TopologyProofClient(json,base,base,base,"key-1",Base64.getEncoder().encodeToString(key.getPublic().getEncoded()));
    jwt=Jwt.withTokenValue("original").header("alg","RS256").subject("principal-1").claim("tenant_id","tenant").claim("environment_id","env").claim("actor_issuer","issuer").claim("actor_subject","human-subject").claim("service_scope","*").claim("capabilities",List.of("topology.proposal.write")).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(30)).build();
  }
  @AfterEach void stop() { if(server!=null) server.stop(0); }
  private Map<String,Object> read(byte[] value) { return json.readValue(value,Map.class); }
  private void send(com.sun.net.httpserver.HttpExchange exchange,Map<String,Object> value) throws java.io.IOException {
    byte[] body=json.writeValueAsBytes(value); exchange.getResponseHeaders().set("Content-Type","application/json"); exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body); exchange.close();
  }
  private Map<String,Object> receipt(String id,String version,String purpose,Instant deadline,int rev) {
    Instant now=Instant.now();
    Map<String,Object> value=new LinkedHashMap<>(Map.ofEntries(Map.entry("protocol","UACRF_OWNER_RECEIPT_V1"),Map.entry("key_id","key-1"),Map.entry("receipt_id","receipt-1"),Map.entry("issuer_service","S02"),Map.entry("audience_service","S06"),Map.entry("tenant_id","tenant"),Map.entry("environment_id","env"),Map.entry("actor_id","human-subject"),Map.entry("resource_type","agent_workload"),Map.entry("resource_id",id),Map.entry("resource_version",version),Map.entry("resource_revision",rev),Map.entry("authority_epoch",rev),Map.entry("content_digest",digest),Map.entry("eligible",true),Map.entry("purpose",purpose),Map.entry("issued_at",now.toString()),Map.entry("valid_until",deadline.toString())));
    value.put("signature",OwnerReceiptSignature.sign(json.writeValueAsString(value),(RSAPrivateKey)key.getPrivate())); return value;
  }
  private TopologyProofClient.Binding binding() { return new TopologyProofClient.Binding("w1","v1","w2","v2","ev",digest,3,null,null,null); }

  @Test void exchangesExactResourcesAndRechecksRevisionAndConflict() {
    var result=client.prepare(jwt,TopologyProofClient.PROPOSE,binding(),Instant.now().plusSeconds(5));
    assertEquals(3,exchanges.size());
    assertEquals(List.of("w1","w2","ev"),exchanges.stream().map(m->m.get("resource_id")).toList());
    exchanges.forEach(m->assertEquals(java.util.Set.of("audience","resource_type","resource_id","purpose","operation_deadline"),m.keySet()));
    assertNotNull(client.prepare(jwt,TopologyProofClient.REVIEW,result,Instant.now().plusSeconds(5)));
    revision.set(4);
    assertThrows(TopologyException.class,()->client.prepare(jwt,TopologyProofClient.REVIEW,result,Instant.now().plusSeconds(5)));
    revision.set(2); qualified.set(false);
    assertThrows(TopologyException.class,()->client.prepare(jwt,TopologyProofClient.CURRENT,result,Instant.now().plusSeconds(5)));
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
    assertThrows(IllegalArgumentException.class,()->new TopologyProofClient(json,"http://remote.example","http://localhost:8083","http://localhost:8086","", ""));
  }
  @Test void rejectsOversizedInternalResponses() {
    oversized.set(true);
    assertThrows(TopologyException.class,()->client.prepare(jwt,TopologyProofClient.PROPOSE,binding(),Instant.now().plusSeconds(5)));
  }
}
