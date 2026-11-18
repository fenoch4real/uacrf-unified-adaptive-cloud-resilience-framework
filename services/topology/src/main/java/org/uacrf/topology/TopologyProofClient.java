package org.uacrf.topology;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
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
import org.springframework.web.util.UriUtils;

@Component
class TopologyProofClient {
  static final String PROPOSE = "topology.dependency-proposal", REVIEW = "topology.dependency-review", CURRENT = "topology.graph.current-use";
  static final String SUBGRAPH_SELECTION = "topology.graph.subgraph-selection";
  static final String DIFFERENCE_CLASSIFICATION = "topology.graph.difference-classification";
  private static final int MAX_RESPONSE_BYTES = 16 * 1024;
  private static final int MAX_SNAPSHOT_RESPONSE_BYTES = 4 * 1024 * 1024;
  private static final int MAX_SERVICE_WORKLOAD_ROOTS = 10_000;
  private static final int MAX_ACTIVE_OBJECTIVES = 100;
  private static final Set<String> RECEIPT_FIELDS = Set.of("protocol", "key_id", "receipt_id", "issuer_service", "audience_service", "tenant_id", "environment_id", "actor_id", "resource_type", "resource_id", "resource_version", "resource_revision", "content_digest", "authority_epoch", "eligible", "purpose", "issued_at", "valid_until", "signature");
  private static final Set<String> SUMMARY_FIELDS = Set.of("evidence_id", "source_class", "source_ref", "sequence", "observed_at", "kind", "qualification_state", "revision", "payload_digest");
  private static final String ID = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$";
  private final ObjectMapper json;
  private final JsonMapper responseJson;
  private final HttpClient http;
  private final String identityUrl, catalogUrl, evidenceUrl, assessmentUrl, objectivesUrl, keyId;
  private final RSAPublicKey publicKey;

  TopologyProofClient(ObjectMapper json,
      @Value("${uacrf.topology.identity-url:http://localhost:8082}") String identityUrl,
      @Value("${uacrf.topology.catalog-url:http://localhost:8083}") String catalogUrl,
      @Value("${uacrf.topology.evidence-url:http://localhost:8086}") String evidenceUrl,
      @Value("${uacrf.topology.assessment-url:http://localhost:8089}") String assessmentUrl,
      @Value("${uacrf.topology.objectives-url:http://localhost:8087}") String objectivesUrl,
      @Value("${uacrf.topology.s02-key-id:}") String keyId,
      @Value("${uacrf.topology.s02-public-key-base64:}") String publicKey) {
    this.json=json; this.identityUrl=ownerUrl(identityUrl); this.catalogUrl=ownerUrl(catalogUrl); this.evidenceUrl=ownerUrl(evidenceUrl); this.assessmentUrl=ownerUrl(assessmentUrl); this.objectivesUrl=ownerUrl(objectivesUrl); this.keyId=keyId;
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

  record ServiceRevisionBinding(Map<String,Object> receipt, Map<String,Object> snapshot,
      List<String> workloadIds, boolean membershipSupported) {}

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

  ServiceRevisionBinding prepareServiceRevision(Jwt jwt, String serviceId, String version,
      String digest, String purpose, Instant deadline) {
    if (version == null || !version.matches("^[1-9][0-9]{0,15}$")) throw failure();
    long requestedVersion=Long.parseLong(version);
    DelegationPrincipal actor=DelegationPrincipal.from(jwt);
    String token=exchange(jwt,"S02","service",serviceId,purpose,deadline);
    Map<String,Object> body=new LinkedHashMap<>();
    body.put("purpose",purpose); body.put("requested_version",version);
    body.put("operation_deadline",deadline.toString());
    String encodedId=UriUtils.encodePathSegment(serviceId,StandardCharsets.UTF_8);
    Map<String,Object> receipt=post(catalogUrl+"/internal/v1/services/"+encodedId+":receipt",token,body,deadline);
    validateReceipt(receipt,actor,purpose,"service",serviceId,version,deadline,Instant.now());
    if (!digest.equals(receipt.get("content_digest"))) throw stale();
    String encodedVersion=UriUtils.encodePathSegment(version,StandardCharsets.UTF_8);
    Map<String,Object> response=get(catalogUrl+"/internal/v1/services/"+encodedId
        +"/revisions/"+encodedVersion,token,deadline);
    if (!response.keySet().equals(Set.of("resource_version","content_digest","snapshot"))
        || !numericVersionMatches(response.get("resource_version"),requestedVersion)
        || !(response.get("content_digest") instanceof String snapshotDigest)
        || !digest.equals(snapshotDigest)
        || !(response.get("snapshot") instanceof Map<?,?> rawSnapshot)) throw stale();
    @SuppressWarnings("unchecked") Map<String,Object> snapshot=(Map<String,Object>)rawSnapshot;
    try {
      if (!serviceId.equals(snapshot.get("service_id"))
          || !numericVersionMatches(snapshot.get("revision"),requestedVersion)
          || !digest.equals(sha256(OwnerReceiptSignature.canonicalize(json.writeValueAsString(snapshot)))))
        throw stale();
    } catch (JacksonException | NumberFormatException e) {
      throw failure();
    }

    Set<String> legacySnapshotFields=Set.of(
        "service_id","name","owner_id","criticality","lifecycle","revision");
    Set<String> membershipSnapshotFields=Set.of(
        "service_id","name","owner_id","criticality","lifecycle","revision",
        "owned_agent_workload_ids");
    if (!snapshot.keySet().equals(legacySnapshotFields)
        && !snapshot.keySet().equals(membershipSnapshotFields)) throw failure();
    if (!(snapshot.get("name") instanceof String name) || name.isBlank() || name.length()>200
        || !validId(snapshot.get("owner_id"))
        || !(snapshot.get("criticality") instanceof Integer criticality)
        || criticality<1 || criticality>5
        || !(snapshot.get("lifecycle") instanceof String lifecycle)
        || !Set.of("DRAFT","ACTIVE","SUSPENDED","RETIRED").contains(lifecycle))
      throw failure();
    Object rawMembership=snapshot.get("owned_agent_workload_ids");
    if (!(rawMembership instanceof List<?> values)) {
      return new ServiceRevisionBinding(Map.copyOf(receipt),Map.copyOf(snapshot),List.of(),false);
    }
    if (values.size()>MAX_SERVICE_WORKLOAD_ROOTS)
      return new ServiceRevisionBinding(Map.copyOf(receipt),Map.copyOf(snapshot),List.of(),false);
    java.util.TreeSet<String> unique=new java.util.TreeSet<>();
    for (Object value:values) {
      if (!(value instanceof String id) || !id.matches(ID) || !unique.add(id))
        return new ServiceRevisionBinding(Map.copyOf(receipt),Map.copyOf(snapshot),List.of(),false);
    }
    if (!values.equals(List.copyOf(unique)))
      return new ServiceRevisionBinding(Map.copyOf(receipt),Map.copyOf(snapshot),List.of(),false);
    return new ServiceRevisionBinding(Map.copyOf(receipt),Map.copyOf(snapshot),
        List.copyOf(unique),true);
  }

  Map<String,Object> prepareCaseReceipt(Jwt jwt, String caseId, String purpose, Instant deadline) {
    DelegationPrincipal actor=DelegationPrincipal.from(jwt);
    String token=exchange(jwt,"S07","assessment_case",caseId,purpose,deadline);
    String encodedCase=UriUtils.encodePathSegment(caseId,StandardCharsets.UTF_8);
    Map<String,Object> receipt=post(assessmentUrl+"/internal/v1/cases/"+encodedCase+":receipt",token,Map.of(),deadline);
    if (!receipt.keySet().equals(Set.of("case_id","purpose","case_digest","workload_id","required_source_kinds"))
        || !caseId.equals(receipt.get("case_id"))
        || !(receipt.get("purpose") instanceof String casePurpose) || casePurpose.isBlank()
        || casePurpose.length()>2000
        || !(receipt.get("case_digest") instanceof String digest) || !digest.matches("^[a-f0-9]{64}$")
        || !(receipt.get("workload_id") instanceof String workloadId) || !workloadId.matches(ID)
        || !(receipt.get("required_source_kinds") instanceof java.util.List<?> kinds)
        || kinds.isEmpty() || kinds.size()>100) throw failure();
    Set<String> unique=new java.util.HashSet<>();
    for (Object kind:kinds)
      if (!(kind instanceof String value) || value.isBlank() || value.length()>200 || !unique.add(value)) throw failure();
    if (!Instant.now().isBefore(deadline)) throw failure();
    return Map.copyOf(receipt);
  }

  Map<String,Object> prepareObjectiveSnapshot(
      Jwt jwt, String workloadId, String purpose, Instant deadline) {
    if (!validId(workloadId)) throw failure();
    String token=exchange(jwt,"S05","agent_workload",workloadId,purpose,deadline);
    String encodedWorkload=UriUtils.encodePathSegment(workloadId,StandardCharsets.UTF_8);
    Map<String,Object> response=post(objectivesUrl+"/internal/v1/workloads/"+encodedWorkload
        +"/objectives:receipt",token,Map.of("purpose",purpose),deadline);
    return validateObjectiveSnapshot(response,workloadId,purpose,deadline);
  }

  private Map<String,Object> validateObjectiveSnapshot(
      Map<String,Object> response, String workloadId, String purpose, Instant deadline) {
    if (!response.keySet().equals(Set.of("workload_id","purpose","objectives","set_digest"))
        || !workloadId.equals(response.get("workload_id"))
        || !purpose.equals(response.get("purpose"))
        || !(response.get("objectives") instanceof List<?> rawObjectives)
        || rawObjectives.size()>MAX_ACTIVE_OBJECTIVES
        || !(response.get("set_digest") instanceof String setDigest)
        || !setDigest.matches("^[a-f0-9]{64}$")) throw failure();

    List<Map<String,Object>> objectives=new java.util.ArrayList<>();
    String previousMetric=null;
    Set<String> ids=new java.util.HashSet<>();
    StringBuilder digestMaterial=new StringBuilder(workloadId);
    Set<String> fields=Set.of(
        "id","metric","comparator","target","unit","window_seconds","revision","content_digest");
    for (Object raw:rawObjectives) {
      if (!(raw instanceof Map<?,?> objective) || !objective.keySet().equals(fields)
          || !(objective.get("id") instanceof String id) || !id.matches(ID)
          || !ids.add(id)
          || !(objective.get("metric") instanceof String metric)
          || !metric.matches("^[a-z][a-z0-9_.-]{0,99}$")
          || (previousMetric!=null && previousMetric.compareTo(metric)>=0)
          || !(objective.get("comparator") instanceof String comparator)
          || !Set.of("GTE","LTE").contains(comparator)
          || !(objective.get("target") instanceof Number target)
          || decimal(target)==null
          || !(objective.get("unit") instanceof String unit)
          || !unit.matches("^[A-Za-z0-9_./%-]{1,30}$")
          || !positiveInteger(objective.get("window_seconds"),Integer.MAX_VALUE)
          || !positiveInteger(objective.get("revision"),Long.MAX_VALUE)
          || !(objective.get("content_digest") instanceof String contentDigest)
          || !contentDigest.matches("^[a-f0-9]{64}$")) throw failure();
      long objectiveRevision=((Number)objective.get("revision")).longValue();
      Map<String,Object> validated=new LinkedHashMap<>();
      validated.put("id",id);
      validated.put("metric",metric);
      validated.put("comparator",comparator);
      validated.put("target",decimal(target));
      validated.put("unit",unit);
      validated.put("window_seconds",((Number)objective.get("window_seconds")).intValue());
      validated.put("revision",objectiveRevision);
      validated.put("content_digest",contentDigest);
      objectives.add(Map.copyOf(validated));
      digestMaterial.append('|').append(id).append(':').append(objectiveRevision)
          .append(':').append(contentDigest);
      previousMetric=metric;
    }
    if (!setDigest.equals(sha256(digestMaterial.toString()))
        || !Instant.now().isBefore(deadline)) throw stale();
    Map<String,Object> snapshot=new LinkedHashMap<>();
    snapshot.put("workload_id",workloadId);
    snapshot.put("purpose",purpose);
    snapshot.put("objectives",List.copyOf(objectives));
    snapshot.put("set_digest",setDigest);
    return Map.copyOf(snapshot);
  }

  private static BigDecimal decimal(Object value) {
    if (!(value instanceof Number number)) return null;
    try {
      return new BigDecimal(number.toString());
    } catch (NumberFormatException error) {
      return null;
    }
  }

  private static boolean positiveInteger(Object value,long maximum) {
    if (!(value instanceof Number number)) return false;
    try {
      long result=new BigDecimal(number.toString()).longValueExact();
      return result>0 && result<=maximum;
    } catch (NumberFormatException | ArithmeticException error) {
      return false;
    }
  }

  private Map<String,Object> owner(Jwt jwt, DelegationPrincipal actor, String purpose, String id, String version, Map<String,Object> prior, Instant deadline) {
    String token=exchange(jwt,"S02","agent_workload",id,purpose,deadline);
    Map<String,Object> body=new LinkedHashMap<>(); body.put("purpose",purpose); body.put("requested_version",version); body.put("operation_deadline",deadline.toString());
    if (prior!=null) body.put("known_revision",prior.get("resource_revision"));
    Map<String,Object> receipt=post(catalogUrl+"/internal/v1/workloads/"+id+":receipt",token,body,deadline);
    validateReceipt(receipt,actor,purpose,"agent_workload",id,version,deadline,Instant.now());
    if (prior!=null && (!prior.get("resource_revision").equals(receipt.get("resource_revision")) || !prior.get("authority_epoch").equals(receipt.get("authority_epoch")) || !prior.get("content_digest").equals(receipt.get("content_digest")))) throw stale();
    return receipt;
  }

  private String exchange(Jwt jwt, String audience, String type, String id, String purpose, Instant deadline) {
    Map<String,Object> response=post(identityUrl+"/internal/v1/delegations:exchange",jwt.getTokenValue(),Map.of("audience",audience,"resource_type",type,"resource_id",id,"purpose",purpose,"operation_deadline",deadline.toString()),deadline);
    if (!(response.get("access_token") instanceof String token) || token.isBlank()) throw failure();
    return token;
  }

  private Map<String,Object> post(String url, String token, Map<String,Object> body, Instant deadline) {
    return request(url,token,deadline,MAX_RESPONSE_BYTES,
        HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)),"POST");
  }

  private Map<String,Object> get(String url,String token,Instant deadline) {
    return request(url,token,deadline,MAX_SNAPSHOT_RESPONSE_BYTES,
        HttpRequest.BodyPublishers.noBody(),"GET");
  }

  private Map<String,Object> request(String url,String token,Instant deadline,int maxBytes,
      HttpRequest.BodyPublisher body,String method) {
    Duration remaining=Duration.between(Instant.now(),deadline);
    if (remaining.isNegative() || remaining.isZero()) throw failure();
    Duration timeout=remaining.compareTo(Duration.ofSeconds(2))<0?remaining:Duration.ofSeconds(2);
    try {
      HttpRequest.Builder builder=HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
          .header("Authorization","Bearer "+token);
      if ("POST".equals(method)) builder.header("Content-Type","application/json").POST(body);
      else builder.GET();
      HttpRequest request=builder.build();
      HttpResponse<byte[]> response=http.send(request,
          HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), maxBytes));
      if (response.statusCode()!=200) {
        if (url.startsWith(catalogUrl+"/internal/v1/services/") && (response.statusCode()==404 || response.statusCode()==409))
          throw new TopologyException(HttpStatus.CONFLICT,"SERVICE_REVISION_UNAVAILABLE",
              "The requested immutable S02 service revision is unavailable or its receipt is stale.");
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

  private static String sha256(String value) {
    try {
      return java.util.HexFormat.of().formatHex(
          java.security.MessageDigest.getInstance("SHA-256")
              .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  void validateReceipt(Map<String,Object> receipt, DelegationPrincipal actor, String purpose, String id, String version, Instant deadline, Instant now) {
    validateReceipt(receipt,actor,purpose,"agent_workload",id,version,deadline,now);
  }

  void validateReceipt(Map<String,Object> receipt, DelegationPrincipal actor, String purpose,
      String resourceType, String id, String version, Instant deadline, Instant now) {
    if (!receipt.keySet().equals(RECEIPT_FIELDS) || publicKey==null || keyId.isBlank()
        || !"UACRF_OWNER_RECEIPT_V1".equals(receipt.get("protocol")) || !keyId.equals(receipt.get("key_id"))
        || !"S02".equals(receipt.get("issuer_service")) || !"S06".equals(receipt.get("audience_service"))
        || !actor.tenantId().equals(receipt.get("tenant_id")) || !actor.environmentId().equals(receipt.get("environment_id"))
        || !actor.actorSubject().equals(receipt.get("actor_id")) || !resourceType.equals(receipt.get("resource_type"))
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
  private static boolean numericVersionMatches(Object value,long expected) {
    if (!(value instanceof Number number)) return false;
    try { return new BigDecimal(number.toString()).longValueExact()==expected; }
    catch (NumberFormatException | ArithmeticException e) { return false; }
  }
  private static boolean validNumber(Object value) { return value instanceof Integer i && i>=0 || value instanceof Long l && l>=0 && l<=9007199254740991L; }
  static TopologyException failure() { return new TopologyException(HttpStatus.CONFLICT,"PROOF_UNAVAILABLE_OR_STALE","Current owner proof is unavailable or does not match the retained proposal."); }
  static TopologyException stale() { return new TopologyException(HttpStatus.CONFLICT,"PROOF_STALE","The retained owner proof is no longer current."); }
}
