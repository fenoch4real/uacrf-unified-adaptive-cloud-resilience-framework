package org.uacrf.topology;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.security.oauth2.jwt.Jwt;
import org.uacrf.security.DelegationKeyMaterial;
import org.uacrf.security.DelegationPrincipal;
import org.uacrf.security.OwnerReceiptSignature;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Component
class TopologyProofClient {
  static final String PROPOSE = "topology.dependency-proposal", REVIEW = "topology.dependency-review", CURRENT = "topology.graph.current-use";
  private static final int MAX_RESPONSE_BYTES = 16 * 1024;
  private static final Set<String> RECEIPT_FIELDS = Set.of("protocol", "key_id", "receipt_id", "issuer_service", "audience_service", "tenant_id", "environment_id", "actor_id", "resource_type", "resource_id", "resource_version", "resource_revision", "content_digest", "authority_epoch", "eligible", "purpose", "issued_at", "valid_until", "signature");
  private static final Set<String> SUMMARY_FIELDS = Set.of("evidence_id", "source_class", "source_ref", "sequence", "observed_at", "kind", "qualification_state", "revision", "payload_digest");
  private static final String ID = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$";
  private final ObjectMapper json;
  private final JsonMapper responseJson;
  private final HttpClient http;
  private final String identityUrl, catalogUrl, evidenceUrl, keyId;
  private final RSAPublicKey publicKey;

  TopologyProofClient(ObjectMapper json,
      @Value("${uacrf.topology.identity-url:http://localhost:8082}") String identityUrl,
      @Value("${uacrf.topology.catalog-url:http://localhost:8083}") String catalogUrl,
      @Value("${uacrf.topology.evidence-url:http://localhost:8086}") String evidenceUrl,
      @Value("${uacrf.topology.s02-key-id:}") String keyId,
      @Value("${uacrf.topology.s02-public-key-base64:}") String publicKey) {
    this.json=json; this.identityUrl=ownerUrl(identityUrl); this.catalogUrl=ownerUrl(catalogUrl); this.evidenceUrl=ownerUrl(evidenceUrl); this.keyId=keyId;
    if (!keyId.isBlank()) DelegationKeyMaterial.validateKeyId(keyId);
    this.publicKey=publicKey.isBlank()?null:DelegationKeyMaterial.decodePublicKey(publicKey);
    this.responseJson=JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build();
    this.http=HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }
  private static String ownerUrl(String value) {
    URI uri=URI.create(value);
    String host=uri.getHost();
    if (host==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
        || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))
        || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme())
            && Set.of("localhost","127.0.0.1","[::1]").contains(host))))
      throw new IllegalArgumentException("Owner URLs require HTTPS, except for loopback development.");
    return value.endsWith("/")?value.substring(0,value.length()-1):value;
  }

  record Binding(String sourceId, String sourceVersion, String targetId, String targetVersion,
      String evidenceId, String evidenceDigest, long evidenceRevision,
      Map<String,Object> sourceReceipt, Map<String,Object> targetReceipt, Map<String,Object> summary) {}

  Binding prepare(Jwt jwt, String purpose, Binding expected, Instant deadline) {
    DelegationPrincipal actor=DelegationPrincipal.from(jwt);
    try {
      Map<String,Object> source=owner(jwt,actor,purpose,expected.sourceId(),expected.sourceVersion(),expected.sourceReceipt(),deadline);
      Map<String,Object> target=owner(jwt,actor,purpose,expected.targetId(),expected.targetVersion(),expected.targetReceipt(),deadline);
      String token=exchange(jwt,"S04","evidence_event",expected.evidenceId(),purpose,deadline);
      Instant cutoff=Instant.now();
      Map<String,Object> result=post(evidenceUrl+"/internal/v1/qualifications/"+expected.evidenceId()+":topology-summary", token,
          Map.of("purpose",purpose,"expected_digest",expected.evidenceDigest(),"expected_revision",expected.evidenceRevision(),"cutoff",cutoff.toString()),deadline);
      if (Boolean.FALSE.equals(result.get("usable_as_evidence"))) throw stale();
      if (!Boolean.TRUE.equals(result.get("usable_as_evidence")) || !(result.get("summary") instanceof Map<?,?> raw)) throw failure();
      @SuppressWarnings("unchecked") Map<String,Object> summary=(Map<String,Object>)raw;
      validateSummary(summary,expected,cutoff);
      if (expected.summary()!=null && !expected.summary().equals(summary)) throw stale();
      Instant now=Instant.now();
      validateReceipt(source,actor,purpose,expected.sourceId(),expected.sourceVersion(),deadline,now);
      validateReceipt(target,actor,purpose,expected.targetId(),expected.targetVersion(),deadline,now);
      if (!now.isBefore(deadline)) throw failure();
      return new Binding(expected.sourceId(),expected.sourceVersion(),expected.targetId(),expected.targetVersion(),expected.evidenceId(),expected.evidenceDigest(),expected.evidenceRevision(),source,target,Map.copyOf(summary));
    } catch (TopologyException e) { throw e; }
    catch (RuntimeException e) { throw failure(); }
  }

  private Map<String,Object> owner(Jwt jwt, DelegationPrincipal actor, String purpose, String id, String version, Map<String,Object> prior, Instant deadline) {
    String token=exchange(jwt,"S02","agent_workload",id,purpose,deadline);
    Map<String,Object> body=new LinkedHashMap<>(); body.put("purpose",purpose); body.put("requested_version",version); body.put("operation_deadline",deadline.toString());
    if (prior!=null) body.put("known_revision",prior.get("resource_revision"));
    Map<String,Object> receipt=post(catalogUrl+"/internal/v1/workloads/"+id+":receipt",token,body,deadline);
    validateReceipt(receipt,actor,purpose,id,version,deadline,Instant.now());
    if (prior!=null && (!prior.get("resource_revision").equals(receipt.get("resource_revision")) || !prior.get("authority_epoch").equals(receipt.get("authority_epoch")) || !prior.get("content_digest").equals(receipt.get("content_digest")))) throw stale();
    return receipt;
  }

  private String exchange(Jwt jwt, String audience, String type, String id, String purpose, Instant deadline) {
    Map<String,Object> response=post(identityUrl+"/internal/v1/delegations:exchange",jwt.getTokenValue(),Map.of("audience",audience,"resource_type",type,"resource_id",id,"purpose",purpose,"operation_deadline",deadline.toString()),deadline);
    if (!(response.get("access_token") instanceof String token) || token.isBlank()) throw failure();
    return token;
  }

  private Map<String,Object> post(String url, String token, Map<String,Object> body, Instant deadline) {
    Duration remaining=Duration.between(Instant.now(),deadline);
    if (remaining.isNegative() || remaining.isZero()) throw failure();
    Duration timeout=remaining.compareTo(Duration.ofSeconds(2))<0?remaining:Duration.ofSeconds(2);
    try {
      HttpRequest request=HttpRequest.newBuilder(URI.create(url)).timeout(timeout).header("Authorization","Bearer "+token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
      HttpResponse<byte[]> response=http.send(request,
          HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), MAX_RESPONSE_BYTES));
      if (response.statusCode()!=200) {
        if (url.startsWith(catalogUrl+"/internal/v1/workloads/") && response.statusCode()==409) {
          Object error=responseJson.readValue(response.body(),Object.class);
          if (error instanceof Map<?,?> map && Set.of("STALE_WORKLOAD_VERSION","STALE_WORKLOAD_REVISION","OWNER_INACTIVE","WORKLOAD_VERSION_INACTIVE","WORKLOAD_MANIFEST_INVALID").contains(map.get("code"))) throw stale();
        }
        throw failure();
      }
      if (!Instant.now().isBefore(deadline)) throw failure();
      Object value=responseJson.readValue(response.body(),Object.class);
      if (!(value instanceof Map<?,?> raw)) throw failure();
      @SuppressWarnings("unchecked") Map<String,Object> result=(Map<String,Object>)raw;
      return result;
    } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw failure(); }
    catch (TopologyException e) { throw e; }
    catch (IOException | JacksonException | IllegalArgumentException e) { throw failure(); }
  }

  void validateReceipt(Map<String,Object> receipt, DelegationPrincipal actor, String purpose, String id, String version, Instant deadline, Instant now) {
    if (!receipt.keySet().equals(RECEIPT_FIELDS) || publicKey==null || keyId.isBlank()
        || !"UACRF_OWNER_RECEIPT_V1".equals(receipt.get("protocol")) || !keyId.equals(receipt.get("key_id"))
        || !"S02".equals(receipt.get("issuer_service")) || !"S06".equals(receipt.get("audience_service"))
        || !actor.tenantId().equals(receipt.get("tenant_id")) || !actor.environmentId().equals(receipt.get("environment_id"))
        || !actor.actorSubject().equals(receipt.get("actor_id")) || !"agent_workload".equals(receipt.get("resource_type"))
        || !id.equals(receipt.get("resource_id")) || !version.equals(receipt.get("resource_version")) || !purpose.equals(receipt.get("purpose"))
        || !(receipt.get("eligible") instanceof Boolean) || !validId(receipt.get("receipt_id"))
        || !(receipt.get("content_digest") instanceof String digest) || !digest.matches("^[a-f0-9]{64}$")
        || !validNumber(receipt.get("resource_revision")) || !validNumber(receipt.get("authority_epoch"))
        || !receipt.get("resource_revision").equals(receipt.get("authority_epoch")) || !(receipt.get("signature") instanceof String signature)) throw failure();
    try {
      Instant issued=Instant.parse((String)receipt.get("issued_at")), until=Instant.parse((String)receipt.get("valid_until"));
      if (issued.isAfter(now) || !until.isAfter(now) || !until.isAfter(issued) || until.isAfter(issued.plusSeconds(5)) || until.isAfter(deadline)) throw failure();
      Map<String,Object> unsigned=new LinkedHashMap<>(receipt); unsigned.remove("signature");
      if (!OwnerReceiptSignature.verify(json.writeValueAsString(unsigned),signature,publicKey)) throw failure();
    } catch (RuntimeException e) { throw failure(); }
    if (!Boolean.TRUE.equals(receipt.get("eligible"))) throw stale();
  }

  private void validateSummary(Map<String,Object> summary, Binding expected, Instant cutoff) {
    if (!summary.keySet().equals(SUMMARY_FIELDS) || !expected.evidenceId().equals(summary.get("evidence_id"))
        || !(summary.get("payload_digest") instanceof String digest) || !digest.matches("^[a-f0-9]{64}$")
        || !(summary.get("qualification_state") instanceof String state)
        || !(summary.get("revision") instanceof Number n) || !validNumber(n)
        || !(summary.get("sequence") instanceof Number sequence) || !validNumber(sequence) || sequence.longValue()<1
        || !(summary.get("source_class") instanceof String sc) || sc.isBlank()
        || !(summary.get("source_ref") instanceof String ref) || ref.isBlank() || ref.length()>500
        || !(summary.get("kind") instanceof String kind) || kind.isBlank() || kind.length()>100
        || !(summary.get("observed_at") instanceof String observed)) throw failure();
    try { if (Instant.parse(observed).isAfter(cutoff)) throw failure(); } catch (RuntimeException e) { throw failure(); }
    if (!expected.evidenceDigest().equals(digest) || !"QUALIFIED".equals(state) || n.longValue()!=expected.evidenceRevision()) throw stale();
  }
  private static boolean validId(Object value) { return value instanceof String s && s.matches(ID); }
  private static boolean validNumber(Object value) { return value instanceof Integer i && i>=0 || value instanceof Long l && l>=0 && l<=9007199254740991L; }
  static TopologyException failure() { return new TopologyException(HttpStatus.CONFLICT,"PROOF_UNAVAILABLE_OR_STALE","Current owner proof is unavailable or does not match the retained proposal."); }
  static TopologyException stale() { return new TopologyException(HttpStatus.CONFLICT,"PROOF_STALE","The retained owner proof is no longer current."); }
}
