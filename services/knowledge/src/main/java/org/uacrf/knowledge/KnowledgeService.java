package org.uacrf.knowledge;

import static org.uacrf.knowledge.KnowledgeRequests.*;
import static org.uacrf.knowledge.KnowledgeResponses.*;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.uacrf.security.DelegationPrincipal;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
class KnowledgeService {
  private static final String SCHEMA = "uacrf_knowledge.";
  private static final int MAX_CONTENT_BYTES = 256 * 1024;
  private static final int MAX_CHUNK_CHARS = 1000;
  private static final Pattern TERMS = Pattern.compile("[\\p{L}\\p{N}]+");
  private static final Pattern PURPOSE =
      Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$");

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final CaseReceiptVerifier caseReceiptVerifier;

  KnowledgeService(JdbcTemplate jdbc, ObjectMapper objectMapper, CaseReceiptVerifier caseReceiptVerifier) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.caseReceiptVerifier = caseReceiptVerifier;
  }

  @Transactional
  ItemVersion stageIdempotent(DelegationPrincipal actor, StageItem request, String key) {
    requireCapability(actor, "knowledge.item.write");
    requireGlobalScope(actor);
    if (key == null || !key.matches("^[\\x21-\\x7e]{1,200}$")) {
      throw invalid("INVALID_REQUEST", "Idempotency-Key must be a non-empty token of at most 200 characters.");
    }
    String digest = digestJson(request);
    int inserted = jdbc.update(
        "INSERT INTO " + SCHEMA
            + "stage_idempotency (tenant_id, environment_id, actor_id, idempotency_key, payload_digest) "
            + "VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
        actor.tenantId(), actor.environmentId(), actor.principalId(), key, digest);
    if (inserted == 0) {
      List<StageReceipt> prior = jdbc.query(
          "SELECT payload_digest, item_id, version, receipt::text AS receipt FROM " + SCHEMA
              + "stage_idempotency WHERE tenant_id = ? AND environment_id = ? "
              + "AND actor_id = ? AND idempotency_key = ? FOR UPDATE",
          (rs, row) -> new StageReceipt(rs.getString("payload_digest"), rs.getString("item_id"),
              rs.getString("version"), rs.getString("receipt")),
          actor.tenantId(), actor.environmentId(), actor.principalId(), key);
      if (prior.isEmpty() || !digest.equals(prior.getFirst().digest())) {
        throw conflict("IDEMPOTENCY_CONFLICT", "The idempotency key was already used for different content.");
      }
      StageReceipt retained = prior.getFirst();
      if (retained.receipt() == null) {
        throw conflict("COMMAND_IN_PROGRESS", "The original request has not completed.");
      }
      try {
        ItemVersion receipt = objectMapper.readValue(retained.receipt(), ItemVersion.class);
        if (receipt == null || !request.itemId().equals(retained.itemId())
            || !request.version().equals(retained.version())
            || !request.itemId().equals(receipt.itemId()) || !request.version().equals(receipt.version())
            || !"CANDIDATE".equals(receipt.state()) || receipt.revision() != 1) {
          throw new IllegalStateException("Retained staging receipt does not match the request.");
        }
        return receipt;
      } catch (JacksonException error) {
        throw new IllegalStateException("Retained staging receipt is invalid.", error);
      }
    }
    ItemVersion receipt = stage(actor, request);
    jdbc.update("UPDATE " + SCHEMA
            + "stage_idempotency SET item_id = ?, version = ?, receipt = ?::jsonb "
            + "WHERE tenant_id = ? AND environment_id = ? AND actor_id = ? AND idempotency_key = ?",
        receipt.itemId(), receipt.version(), writeJson(receipt),
        actor.tenantId(), actor.environmentId(), actor.principalId(), key);
    return receipt;
  }

  private record StageReceipt(String digest, String itemId, String version, String receipt) {}

  @Transactional
  ItemVersion stage(DelegationPrincipal actor, StageItem request) {
    requireCapability(actor, "knowledge.item.write");
    requireGlobalScope(actor);
    byte[] contentBytes = validatePlainText(request.content());
    validateSet(request.allowedPurposes(), "allowed_purposes", PURPOSE);
    validateSet(request.targetWorkloadIds(), "target_workload_ids", null);
    if (!request.reviewDeadline().isAfter(Instant.now())) {
      throw invalid("REVIEW_DEADLINE_EXPIRED", "review_deadline must be in the future.");
    }

    int itemUpdated =
        jdbc.update(
            "INSERT INTO " + SCHEMA
                + "knowledge_item (tenant_id, environment_id, item_id, revision, withdrawn) "
                + "VALUES (?, ?, ?, 1, false) "
                + "ON CONFLICT (tenant_id, environment_id, item_id) DO UPDATE "
                + "SET revision = " + SCHEMA + "knowledge_item.revision + 1 "
                + "WHERE " + SCHEMA + "knowledge_item.withdrawn = false",
            actor.tenantId(),
            actor.environmentId(),
            request.itemId());
    if (itemUpdated == 0) {
      throw conflict("ITEM_WITHDRAWN", "A withdrawn knowledge item cannot be changed.");
    }
    String sourceDigest = sha256(contentBytes);
    String sql =
        "INSERT INTO " + SCHEMA
            + "knowledge_version (tenant_id, environment_id, item_id, version, title, source_ref, "
            + "content, classification, allowed_purposes, target_workload_ids, review_deadline, reason, "
            + "source_digest, state, revision, proposed_by) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CANDIDATE', 1, ?)";
    jdbc.update(
        sql,
        statement -> {
          statement.setString(1, actor.tenantId());
          statement.setString(2, actor.environmentId());
          statement.setString(3, request.itemId());
          statement.setString(4, request.version());
          statement.setString(5, request.title());
          statement.setString(6, request.sourceRef());
          statement.setString(7, request.content());
          statement.setString(8, request.classification().name());
          statement.setArray(9, stringArray(statement, request.allowedPurposes()));
          statement.setArray(10, stringArray(statement, request.targetWorkloadIds()));
          statement.setTimestamp(11, Timestamp.from(request.reviewDeadline()));
          statement.setString(12, request.reason());
          statement.setString(13, sourceDigest);
          statement.setString(14, actor.principalId());
        });
    return findVersion(actor, request.itemId(), request.version());
  }

  @Transactional(readOnly = true)
  List<ItemVersion> list(DelegationPrincipal actor, String itemId, String purpose) {
    requireCapability(actor, "knowledge.item.read");
    if (purpose != null && !PURPOSE.matcher(purpose).matches()) {
      throw invalid("INVALID_REQUEST", "purpose is malformed.");
    }
    StringBuilder sql =
        new StringBuilder(
            "SELECT v.item_id, v.version, v.title, v.source_ref, v.classification, "
                + "v.allowed_purposes, v.target_workload_ids, v.review_deadline, v.reason, "
                + "v.source_digest, v.state, v.revision, v.proposed_by, v.reviewed_by, "
                + "v.review_reason, v.withdrawn_by, v.withdrawal_reason, v.created_at "
                + "FROM " + SCHEMA + "knowledge_version v "
                + "JOIN " + SCHEMA + "knowledge_item i USING (tenant_id, environment_id, item_id) "
                + "WHERE v.tenant_id = ? AND v.environment_id = ?");
    List<Object> args = new ArrayList<>(List.of(actor.tenantId(), actor.environmentId()));
    if (itemId != null && !itemId.isBlank()) {
      sql.append(" AND v.item_id = ?");
      args.add(itemId);
    }
    if (purpose != null) {
      sql.append(" AND ? = ANY(v.allowed_purposes)");
      args.add(purpose);
    }
    sql.append(" ORDER BY v.item_id, v.version");
    int readableClassification = classificationRank(maxClassification(actor));
    return jdbc.query(sql.toString(), (rs, row) -> itemVersion(rs), args.toArray()).stream()
        .filter(item -> classificationRank(item.classification()) <= readableClassification)
        .toList();
  }

  @Transactional
  ItemVersion review(DelegationPrincipal actor, String itemId, ReviewItem request) {
    return review(actor, itemId, request, null);
  }

  @Transactional
  ItemVersion reviewBound(DelegationPrincipal actor, String itemId, BoundReviewItem request) {
    requireCapability(actor, "knowledge.item.read");
    return review(actor, itemId, new ReviewItem(request.decision(), request.expectedRevision(), request.reason()), request);
  }

  @Transactional
  CandidateContent candidateContent(DelegationPrincipal actor, String itemId, String version) {
    requireCapability(actor, "knowledge.item.read");
    requireCapability(actor, "knowledge.item.review");
    requireGlobalScope(actor);
    VersionRow candidate = lockCandidate(actor, itemId);
    if (!candidate.version().equals(version)) throw notFound();
    requireClassification(actor, candidate.classification());
    if (!sha256(candidate.content().getBytes(StandardCharsets.UTF_8)).equals(candidate.sourceDigest())) {
      throw conflict("SOURCE_INTEGRITY_MISMATCH", "Candidate content does not match its stored digest.");
    }
    return new CandidateContent(findVersion(actor, itemId, version), candidate.content());
  }

  private ItemVersion review(DelegationPrincipal actor, String itemId, ReviewItem request, BoundReviewItem bound) {
    requireCapability(actor, "knowledge.item.review");
    requireGlobalScope(actor);
    VersionRow candidate = lockCandidate(actor, itemId);
    if (bound != null) {
      requireClassification(actor, candidate.classification());
      if (!candidate.version().equals(bound.expectedVersion())
          || !candidate.sourceDigest().equals(bound.expectedSourceDigest())
          || !sha256(candidate.content().getBytes(StandardCharsets.UTF_8)).equals(candidate.sourceDigest())) {
        throw conflict("CANDIDATE_MISMATCH", "The candidate differs from the version presented for review.");
      }
    }
    requireRevision(candidate.revision(), request.expectedRevision());
    if (actor.principalId().equals(candidate.proposedBy())) {
      throw conflict("REVIEWER_NOT_INDEPENDENT", "The proposer cannot review their own knowledge version.");
    }
    if (!candidate.reviewDeadline().isAfter(Instant.now())) {
      throw conflict("REVIEW_DEADLINE_EXPIRED", "The review deadline has passed.");
    }
    if (request.decision() == Decision.APPROVE) {
      if (itemWithdrawn(actor, itemId)) {
        throw conflict("ITEM_WITHDRAWN", "A withdrawn knowledge item cannot be approved.");
      }
      List<VersionRow> approved =
          jdbc.query(
              "SELECT item_id, version, state, revision, proposed_by, review_deadline, "
                  + "source_digest, content, classification, allowed_purposes, target_workload_ids "
                  + "FROM " + SCHEMA + "knowledge_version WHERE tenant_id = ? AND environment_id = ? "
                  + "AND item_id = ? AND state = 'APPROVED' FOR UPDATE",
              (rs, row) -> versionRow(rs),
              actor.tenantId(),
              actor.environmentId(),
              itemId);
      for (VersionRow old : approved) {
        jdbc.update(
            "UPDATE " + SCHEMA + "knowledge_version SET state = 'SUPERSEDED', revision = revision + 1 "
                + "WHERE tenant_id = ? AND environment_id = ? AND item_id = ? AND version = ? "
                + "AND state = 'APPROVED' AND revision = ?",
            actor.tenantId(),
            actor.environmentId(),
            itemId,
            old.version(),
            old.revision());
      }
    }
    String state = request.decision().name().equals("APPROVE") ? "APPROVED" : "REJECTED";
    int changed =
        jdbc.update(
            "UPDATE " + SCHEMA + "knowledge_version SET state = ?, revision = revision + 1, "
                + "reviewed_by = ?, review_reason = ? WHERE tenant_id = ? AND environment_id = ? "
                + "AND item_id = ? AND version = ? AND state = 'CANDIDATE' AND revision = ?",
            state,
            actor.principalId(),
            request.reason(),
            actor.tenantId(),
            actor.environmentId(),
            itemId,
            candidate.version(),
            request.expectedRevision());
    if (changed != 1) {
      throw conflict("REVISION_MISMATCH", "The knowledge version changed since it was read.");
    }
    long itemRevision = advanceItemRevision(actor, itemId, false);
    appendOutbox(
        actor,
        itemId,
        itemRevision,
        "uacrf.s08.knowledge.review.changed.v1",
        Map.of(
            "owned_resource_id", itemId,
            "owned_revision", candidate.revision() + 1,
            "owned_digest", candidate.sourceDigest(),
            "minimal_outcome_metadata",
                Map.of("version", candidate.version(), "state", state, "decision", request.decision().name())));
    return findVersion(actor, itemId, candidate.version());
  }

  @Transactional
  ItemVersion withdraw(DelegationPrincipal actor, String itemId, WithdrawItem request) {
    return withdraw(actor, itemId, request, null);
  }

  @Transactional
  ItemVersion withdrawBound(DelegationPrincipal actor, String itemId, BoundWithdrawItem request) {
    requireCapability(actor, "knowledge.item.read");
    return withdraw(actor, itemId, new WithdrawItem(request.expectedRevision(), request.reason()), request);
  }

  private ItemVersion withdraw(DelegationPrincipal actor, String itemId, WithdrawItem request, BoundWithdrawItem bound) {
    requireCapability(actor, "knowledge.item.withdraw");
    requireGlobalScope(actor);
    List<VersionRow> approved =
        jdbc.query(
            "SELECT item_id, version, state, revision, proposed_by, review_deadline, "
                + "source_digest, content, classification, allowed_purposes, target_workload_ids "
                + "FROM " + SCHEMA + "knowledge_version WHERE tenant_id = ? AND environment_id = ? "
                + "AND item_id = ? AND state = 'APPROVED' FOR UPDATE",
            (rs, row) -> versionRow(rs),
            actor.tenantId(),
            actor.environmentId(),
            itemId);
    if (approved.isEmpty()) {
      throw notFound();
    }
    VersionRow current = approved.getFirst();
    if (bound != null) {
      requireClassification(actor, current.classification());
      if (!current.version().equals(bound.expectedVersion())
          || !current.sourceDigest().equals(bound.expectedSourceDigest())
          || !sha256(current.content().getBytes(StandardCharsets.UTF_8)).equals(current.sourceDigest())) {
        throw conflict("APPROVED_VERSION_MISMATCH", "The approved version differs from the version presented for withdrawal.");
      }
    }
    requireRevision(current.revision(), request.expectedRevision());
    if (itemWithdrawn(actor, itemId)) {
      throw conflict("ITEM_WITHDRAWN", "The knowledge item is already withdrawn.");
    }
    int changed =
        jdbc.update(
            "UPDATE " + SCHEMA + "knowledge_version SET state = 'WITHDRAWN', revision = revision + 1, "
                + "withdrawn_by = ?, withdrawal_reason = ? WHERE tenant_id = ? AND environment_id = ? "
                + "AND item_id = ? AND version = ? AND state = 'APPROVED' AND revision = ?",
            actor.principalId(),
            request.reason(),
            actor.tenantId(),
            actor.environmentId(),
            itemId,
            current.version(),
            request.expectedRevision());
    if (changed != 1) {
      throw conflict("REVISION_MISMATCH", "The knowledge version changed since it was read.");
    }
    long itemRevision = advanceItemRevision(actor, itemId, true);
    appendOutbox(
        actor,
        itemId,
        itemRevision,
        "uacrf.s08.knowledge.review.changed.v1",
        Map.of(
            "owned_resource_id", itemId,
            "owned_revision", current.revision() + 1,
            "owned_digest", current.sourceDigest(),
            "minimal_outcome_metadata",
                Map.of("version", current.version(), "state", "WITHDRAWN")));
    return findVersion(actor, itemId, current.version());
  }

  @Transactional
  Retrieval runIdempotent(DelegationPrincipal actor, RunRetrieval request, String key) {
    requireCapability(actor, "knowledge.retrieval.run");
    requireGlobalScope(actor);
    requireClassification(actor, request.maxClassification());
    if (key == null || !key.matches("^[\\x21-\\x7e]{1,200}$")) {
      throw invalid("INVALID_REQUEST", "Idempotency-Key must be a non-empty token of at most 200 characters.");
    }
    String digest = digestJson(request);
    int inserted = jdbc.update(
        "INSERT INTO " + SCHEMA
            + "retrieval_idempotency (tenant_id, environment_id, actor_id, idempotency_key, payload_digest) "
            + "VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
        actor.tenantId(), actor.environmentId(), actor.principalId(), key, digest);
    if (inserted == 0) {
      List<RetrievalKey> prior = jdbc.query(
          "SELECT payload_digest, retrieval_id FROM " + SCHEMA
              + "retrieval_idempotency WHERE tenant_id = ? AND environment_id = ? "
              + "AND actor_id = ? AND idempotency_key = ? FOR UPDATE",
          (rs, row) -> new RetrievalKey(rs.getString("payload_digest"), rs.getString("retrieval_id")),
          actor.tenantId(), actor.environmentId(), actor.principalId(), key);
      if (prior.isEmpty() || !digest.equals(prior.getFirst().digest())) {
        throw conflict("IDEMPOTENCY_CONFLICT", "The idempotency key was already used for different content.");
      }
      if (prior.getFirst().retrievalId() == null) {
        throw conflict("COMMAND_IN_PROGRESS", "The original request has not completed.");
      }
      return loadRetrieval(actor, prior.getFirst().retrievalId(), true);
    }
    Retrieval retrieval = run(actor, request);
    jdbc.update("UPDATE " + SCHEMA
            + "retrieval_idempotency SET retrieval_id = ? "
            + "WHERE tenant_id = ? AND environment_id = ? AND actor_id = ? AND idempotency_key = ?",
        retrieval.retrievalId(), actor.tenantId(), actor.environmentId(), actor.principalId(), key);
    return retrieval;
  }

  private record RetrievalKey(String digest, String retrievalId) {}

  @Transactional
  Retrieval run(DelegationPrincipal actor, RunRetrieval request) {
    requireCapability(actor, "knowledge.retrieval.run");
    requireGlobalScope(actor);
    requireClassification(actor, request.maxClassification());
    if (request.workloadId() != null) {
      throw invalid(
          "WORKLOAD_SCOPE_UNVERIFIED",
          "Workload-targeted retrieval requires an owner-verified workload scope.");
    }
    CaseReceiptVerifier.CaseReceipt trustedCase = null;
    if (caseReceiptVerifier.enabled()) {
      if (request.caseId() == null) {
        throw conflict("CASE_UNVERIFIED", "A verified assessment case is required for retrieval.");
      }
      CaseReceiptVerifier.Verification verification =
          caseReceiptVerifier.verify(request.caseId(), request.purpose(), actor.tenantId(), actor.environmentId());
      if (!verification.verified()) {
        String reason = verification.reason() == null ? "CASE_UNVERIFIED" : verification.reason();
        if ("OWNER_UNAVAILABLE".equals(reason)) {
          throw conflict(reason, "The assessment case owner is unavailable.");
        }
        throw conflict("CASE_UNVERIFIED", "The assessment case receipt could not be verified.");
      }
      trustedCase = verification.receipt();
      if (!validCaseReceipt(trustedCase, request.caseId(), request.purpose())) {
        throw conflict("CASE_UNVERIFIED", "The assessment case receipt did not match the retrieval request.");
      }
    }
    if (request.maxResults() < 1 || request.maxResults() > 10) {
      throw invalid("INVALID_REQUEST", "max_results must be between 1 and 10.");
    }
    List<String> terms = terms(request.query());
    if (terms.isEmpty()) {
      throw invalid("QUERY_HAS_NO_TERMS", "query must contain at least one alphanumeric term.");
    }
    List<VersionRow> versions =
        jdbc.query(
            "SELECT v.item_id, v.version, v.state, v.revision, v.proposed_by, v.review_deadline, "
                + "v.source_digest, v.content, v.classification, v.allowed_purposes, v.target_workload_ids "
                + "FROM " + SCHEMA + "knowledge_version v JOIN " + SCHEMA
                + "knowledge_item i USING (tenant_id, environment_id, item_id) "
                + "WHERE v.tenant_id = ? AND v.environment_id = ? AND v.state = 'APPROVED' "
                + "AND i.withdrawn = false ORDER BY v.item_id, v.version",
            (rs, row) -> versionRow(rs),
            actor.tenantId(),
            actor.environmentId());
    List<RankedChunk> ranked = new ArrayList<>();
    for (VersionRow version : versions) {
      if (!version.reviewDeadline().isAfter(Instant.now())
          || !version.allowedPurposes().contains(request.purpose())
          || classificationRank(version.classification()) > classificationRank(request.maxClassification())
          || (!version.targetWorkloadIds().isEmpty()
              && (trustedCase == null || !version.targetWorkloadIds().contains(trustedCase.workloadId())))) {
        continue;
      }
      if (!sha256(version.content().getBytes(StandardCharsets.UTF_8)).equals(version.sourceDigest())) {
        throw conflict("SOURCE_DIGEST_MISMATCH", "A retained source version failed its digest check.");
      }
      List<Chunk> chunks = chunks(version.content());
      for (int index = 0; index < chunks.size(); index++) {
        Chunk chunk = chunks.get(index);
        int score = score(chunk.text(), terms);
        if (score > 0) {
          ranked.add(
              new RankedChunk(
                  version.itemId(),
                  version.version(),
                  index,
                  chunk.start(),
                  chunk.end(),
                  version.sourceDigest(),
                  sha256(chunk.text().getBytes(StandardCharsets.UTF_8)),
                  version.allowedPurposes(),
                  version.classification(),
                  score,
                  chunk.text()));
        }
      }
    }
    ranked.sort(
        Comparator.comparingInt(RankedChunk::score)
            .reversed()
            .thenComparing(RankedChunk::itemId)
            .thenComparing(RankedChunk::version)
            .thenComparingInt(RankedChunk::chunkIndex));
    List<RankedChunk> selected = ranked.stream().limit(request.maxResults()).toList();
    String retrievalId = UUID.randomUUID().toString();
    Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    List<ManifestEntry> manifestEntries = selected.stream().map(RankedChunk::manifestEntry).toList();
    String caseBinding = trustedCase == null ? "NOT_VERIFIED" : "VERIFIED";
    String caseDigest = trustedCase == null ? null : trustedCase.caseDigest();
    String trustedWorkloadId = trustedCase == null ? null : trustedCase.workloadId();
    String manifestDigest =
        digestJson(
            new Manifest(
                retrievalId,
                request.caseId(),
                caseBinding,
                caseDigest,
                request.purpose(),
                request.maxClassification(),
                request.query(),
                trustedWorkloadId,
                createdAt.toString(),
                manifestEntries));
    jdbc.update(
        "INSERT INTO " + SCHEMA
            + "retrieval (tenant_id, environment_id, retrieval_id, case_id, case_binding, case_digest, purpose, "
            + "max_classification, query_text, workload_id, manifest_digest, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        actor.tenantId(),
        actor.environmentId(),
        retrievalId,
        request.caseId(),
        caseBinding,
        caseDigest,
        request.purpose(),
        request.maxClassification().name(),
        request.query(),
        trustedWorkloadId,
        manifestDigest,
        Timestamp.from(createdAt));
    for (int index = 0; index < selected.size(); index++) {
      RankedChunk chunk = selected.get(index);
      int resultOrder = index;
      jdbc.update(
          "INSERT INTO " + SCHEMA
              + "retrieval_result (tenant_id, environment_id, retrieval_id, result_order, item_id, "
              + "version, chunk_index, start_offset, end_offset, source_digest, excerpt_digest, "
              + "allowed_purposes, classification, score, excerpt) "
              + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
          statement -> bindResult(statement, actor, retrievalId, resultOrder, chunk));
    }
    appendOutbox(
        actor,
        retrievalId,
        1,
        "uacrf.s08.retrieval.retained.v1",
        Map.of(
            "owned_resource_id", retrievalId,
            "owned_revision", 1,
            "owned_digest", manifestDigest,
            "minimal_outcome_metadata",
                Map.of("purpose", request.purpose(), "result_count", selected.size(), "case_binding", caseBinding)));
    return loadRetrieval(actor, retrievalId, false);
  }

  @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
  Retrieval getRetrieval(DelegationPrincipal actor, String retrievalId) {
    requireCapability(actor, "knowledge.retrieval.read");
    return loadRetrieval(actor, retrievalId, true);
  }

  @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
  RetrievalReceipt receipt(DelegationPrincipal actor, String retrievalId, ReceiptRequest request) {
    requireCapability(actor, "knowledge.retrieval.read");
    StoredRetrieval retrieval = loadStoredRetrieval(actor, retrievalId);
    requireClassification(actor, retrieval.maxClassification());
    List<StoredResult> storedResults = getStoredResults(actor, retrievalId);
    requireValidManifest(retrieval, storedResults);
    if (retrieval.caseId() == null
        || !retrieval.caseId().equals(request.caseId())
        || !retrieval.purpose().equals(request.purpose())
        || !retrieval.maxClassification().equals(request.classification())
        || !retrieval.manifestDigest().equals(request.manifestDigest())) {
      throw conflict("MANIFEST_MISMATCH", "The receipt request does not match the retained retrieval manifest.");
    }
    String reason = "CASE_NOT_BOUND";
    boolean eligible = false;
    if ("VERIFIED".equals(retrieval.caseBinding())) {
      if (!caseReceiptVerifier.enabled()
          || retrieval.caseDigest() == null
          || retrieval.workloadId() == null
          || !liveCaseMatches(actor, retrieval)) {
        reason = "CASE_UNVERIFIED";
      } else {
        storedResults = getStoredResults(actor, retrievalId);
        requireValidManifest(retrieval, storedResults);
        if (!allSourcesEligible(actor, retrieval, storedResults)) {
          reason = "SOURCE_NOT_ELIGIBLE";
        } else {
          eligible = true;
          reason = "ELIGIBLE";
        }
      }
    }
    List<ReceiptResult> results =
        storedResults.stream()
            .map(
                result ->
                    new ReceiptResult(
                        result.itemId(),
                        result.version(),
                        result.chunkIndex(),
                        result.startOffset(),
                        result.endOffset(),
                        result.sourceDigest(),
                        result.excerptDigest(),
                        result.allowedPurposes(),
                        result.classification(),
                        result.score()))
            .toList();
    return new RetrievalReceipt(
        retrievalId,
        request.caseId(),
        retrieval.caseId(),
        retrieval.caseBinding(),
        retrieval.caseDigest(),
        retrieval.workloadId(),
        retrieval.purpose(),
        retrieval.maxClassification(),
        retrieval.manifestDigest(),
        retrieval.query(),
        retrieval.createdAt().toString(),
        results,
        eligible,
        reason);
  }

  private Retrieval loadRetrieval(DelegationPrincipal actor, String retrievalId, boolean recheckCase) {
    StoredRetrieval stored = loadStoredRetrieval(actor, retrievalId);
    List<StoredResult> storedResults = getStoredResults(actor, retrievalId);
    requireValidManifest(stored, storedResults);
    boolean bound =
        "NOT_VERIFIED".equals(stored.caseBinding())
            || !recheckCase
            || (caseReceiptVerifier.enabled() && liveCaseMatches(actor, stored));
    if (bound && recheckCase && "VERIFIED".equals(stored.caseBinding())) {
      storedResults = getStoredResults(actor, retrievalId);
      requireValidManifest(stored, storedResults);
    }
    List<RetrievalResult> results = new ArrayList<>();
    for (StoredResult result : storedResults) {
      String excerpt =
          bound && sourceEligible(actor, stored, result) ? result.excerpt() : null;
      results.add(
          new RetrievalResult(
              result.itemId(),
              result.version(),
              result.chunkIndex(),
              result.startOffset(),
              result.endOffset(),
              result.sourceDigest(),
              result.excerptDigest(),
              result.allowedPurposes(),
              result.classification(),
              result.score(),
              excerpt));
    }
    return new Retrieval(
        stored.retrievalId(),
        stored.caseId(),
        stored.caseBinding(),
        stored.caseDigest(),
        stored.workloadId(),
        stored.purpose(),
        stored.maxClassification(),
        stored.query(),
        stored.createdAt().toString(),
        stored.manifestDigest(),
        results);
  }

  private StoredRetrieval loadStoredRetrieval(DelegationPrincipal actor, String retrievalId) {
    List<StoredRetrieval> rows =
        jdbc.query(
            "SELECT retrieval_id, case_id, case_binding, case_digest, purpose, max_classification, query_text, "
                + "manifest_digest, workload_id, created_at FROM " + SCHEMA
                + "retrieval WHERE tenant_id = ? AND environment_id = ? AND retrieval_id = ?",
            (rs, row) ->
                new StoredRetrieval(
                    rs.getString("retrieval_id"),
                    rs.getString("case_id"),
                    rs.getString("case_binding"),
                    rs.getString("case_digest") == null ? null : rs.getString("case_digest").trim(),
                    rs.getString("purpose"),
                    Classification.valueOf(rs.getString("max_classification")),
                    rs.getString("query_text"),
                    rs.getString("manifest_digest").trim(),
                    rs.getString("workload_id"),
                    rs.getTimestamp("created_at").toInstant()),
            actor.tenantId(),
            actor.environmentId(),
            retrievalId);
    if (rows.isEmpty()) {
      throw notFound();
    }
    return rows.getFirst();
  }

  private List<StoredResult> getStoredResults(DelegationPrincipal actor, String retrievalId) {
    return jdbc.query(
        "SELECT r.item_id, r.version, r.chunk_index, r.start_offset, r.end_offset, r.source_digest, "
            + "r.excerpt_digest, r.allowed_purposes, r.classification, r.score, r.excerpt, "
            + "v.content AS source_content, v.state AS source_state, i.withdrawn AS item_withdrawn, "
            + "v.review_deadline AS source_review_deadline, "
            + "v.source_digest AS current_source_digest, v.classification AS source_classification, "
            + "v.allowed_purposes AS source_allowed_purposes, "
            + "v.target_workload_ids AS source_target_workload_ids "
            + "FROM " + SCHEMA + "retrieval_result r "
            + "JOIN " + SCHEMA
            + "knowledge_version v ON v.tenant_id = r.tenant_id AND v.environment_id = r.environment_id "
            + "AND v.item_id = r.item_id AND v.version = r.version "
            + "JOIN " + SCHEMA
            + "knowledge_item i ON i.tenant_id = v.tenant_id AND i.environment_id = v.environment_id "
            + "AND i.item_id = v.item_id WHERE r.tenant_id = ? AND r.environment_id = ? "
            + "AND r.retrieval_id = ? ORDER BY r.result_order",
        (rs, row) -> storedResult(rs),
        actor.tenantId(),
        actor.environmentId(),
        retrievalId);
  }

  private void requireValidManifest(StoredRetrieval retrieval, List<StoredResult> results) {
    List<ManifestEntry> entries =
        results.stream()
            .map(
                result ->
                    new ManifestEntry(
                        result.itemId(),
                        result.version(),
                        result.chunkIndex(),
                        result.startOffset(),
                        result.endOffset(),
                        result.sourceDigest(),
                        result.excerptDigest(),
                        result.allowedPurposes(),
                        result.classification(),
                        result.score()))
            .toList();
    String calculated =
        digestJson(
            new Manifest(
                retrieval.retrievalId(),
                retrieval.caseId(),
                retrieval.caseBinding(),
                retrieval.caseDigest(),
                retrieval.purpose(),
                retrieval.maxClassification(),
                retrieval.query(),
                retrieval.workloadId(),
                retrieval.createdAt().toString(),
                entries));
    if (!calculated.equals(retrieval.manifestDigest())) {
      throw conflict("MANIFEST_DIGEST_MISMATCH", "The retained retrieval manifest failed its digest check.");
    }
  }

  private boolean liveCaseMatches(DelegationPrincipal actor, StoredRetrieval retrieval) {
    CaseReceiptVerifier.Verification verification =
        caseReceiptVerifier.verify(
            retrieval.caseId(), retrieval.purpose(), actor.tenantId(), actor.environmentId());
    CaseReceiptVerifier.CaseReceipt receipt = verification.receipt();
    return verification.verified()
        && validCaseReceipt(receipt, retrieval.caseId(), retrieval.purpose())
        && receipt != null
        && receipt.caseId().equals(retrieval.caseId())
        && receipt.purpose().equals(retrieval.purpose())
        && receipt.caseDigest().equals(retrieval.caseDigest())
        && receipt.workloadId().equals(retrieval.workloadId());
  }

  private static boolean validCaseReceipt(
      CaseReceiptVerifier.CaseReceipt receipt, String expectedCaseId, String expectedPurpose) {
    if (receipt == null
        || !expectedCaseId.equals(receipt.caseId())
        || !expectedPurpose.equals(receipt.purpose())
        || receipt.caseDigest() == null
        || !receipt.caseDigest().matches("[0-9a-f]{64}")
        || receipt.workloadId() == null
        || receipt.workloadId().isBlank()
        || receipt.workloadId().length() > 200
        || receipt.requiredSourceKinds() == null
        || receipt.requiredSourceKinds().isEmpty()
        || receipt.requiredSourceKinds().size() > 100) {
      return false;
    }
    Set<String> kinds = new LinkedHashSet<>(receipt.requiredSourceKinds());
    return kinds.size() == receipt.requiredSourceKinds().size()
        && receipt.requiredSourceKinds().stream()
            .allMatch(kind -> kind != null && !kind.isBlank() && kind.length() <= 200);
  }

  private boolean allSourcesEligible(
      DelegationPrincipal actor, StoredRetrieval retrieval, List<StoredResult> results) {
    return !results.isEmpty() && results.stream().allMatch(result -> sourceEligible(actor, retrieval, result));
  }

  private boolean sourceEligible(
      DelegationPrincipal actor, StoredRetrieval retrieval, StoredResult result) {
    if (!"APPROVED".equals(result.sourceState())
        || result.itemWithdrawn()
        || !result.sourceReviewDeadline().isAfter(Instant.now())
        || !result.allowedPurposes().contains(retrieval.purpose())
        || !result.sourceAllowedPurposes().contains(retrieval.purpose())
        || (!result.sourceTargetWorkloadIds().isEmpty()
            && !result.sourceTargetWorkloadIds().contains(retrieval.workloadId()))
        || classificationRank(result.classification()) > classificationRank(retrieval.maxClassification())
        || classificationRank(result.sourceClassification()) > classificationRank(retrieval.maxClassification())
        || classificationRank(result.classification()) > classificationRank(maxClassification(actor))
        || !result.sourceDigest().equals(result.currentSourceDigest())
        || !sha256(result.sourceContent().getBytes(StandardCharsets.UTF_8)).equals(result.sourceDigest())) {
      return false;
    }
    int sourceCharacters = result.sourceContent().codePointCount(0, result.sourceContent().length());
    if (result.startOffset() > result.endOffset() || result.endOffset() > sourceCharacters) return false;
    int start = result.sourceContent().offsetByCodePoints(0, result.startOffset());
    int end = result.sourceContent().offsetByCodePoints(0, result.endOffset());
    String actualExcerpt = result.sourceContent().substring(start, end);
    return actualExcerpt.equals(result.excerpt())
        && sha256(actualExcerpt.getBytes(StandardCharsets.UTF_8)).equals(result.excerptDigest());
  }

  private VersionRow lockCandidate(DelegationPrincipal actor, String itemId) {
    List<VersionRow> rows =
        jdbc.query(
            "SELECT item_id, version, state, revision, proposed_by, review_deadline, source_digest, "
                + "content, classification, allowed_purposes, target_workload_ids FROM " + SCHEMA
                + "knowledge_version WHERE tenant_id = ? AND environment_id = ? AND item_id = ? "
                + "AND state = 'CANDIDATE' FOR UPDATE",
            (rs, row) -> versionRow(rs),
            actor.tenantId(),
            actor.environmentId(),
            itemId);
    if (rows.isEmpty()) {
      throw notFound();
    }
    return rows.getFirst();
  }

  private ItemVersion findVersion(DelegationPrincipal actor, String itemId, String version) {
    List<ItemVersion> rows =
        jdbc.query(
            "SELECT item_id, version, title, source_ref, classification, allowed_purposes, "
                + "target_workload_ids, review_deadline, reason, source_digest, state, revision, "
                + "proposed_by, reviewed_by, review_reason, withdrawn_by, withdrawal_reason, created_at "
                + "FROM " + SCHEMA + "knowledge_version WHERE tenant_id = ? AND environment_id = ? "
                + "AND item_id = ? AND version = ?",
            (rs, row) -> itemVersion(rs),
            actor.tenantId(),
            actor.environmentId(),
            itemId,
            version);
    if (rows.isEmpty()) {
      throw notFound();
    }
    return rows.getFirst();
  }

  private boolean itemWithdrawn(DelegationPrincipal actor, String itemId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT withdrawn FROM " + SCHEMA
                + "knowledge_item WHERE tenant_id = ? AND environment_id = ? AND item_id = ?",
            Boolean.class,
            actor.tenantId(),
            actor.environmentId(),
            itemId));
  }

  private long advanceItemRevision(DelegationPrincipal actor, String itemId, boolean withdrawn) {
    int changed =
        jdbc.update(
            "UPDATE " + SCHEMA + "knowledge_item SET revision = revision + 1, "
                + "withdrawn = CASE WHEN ? THEN true ELSE withdrawn END "
                + "WHERE tenant_id = ? AND environment_id = ? AND item_id = ? AND withdrawn = false",
            withdrawn,
            actor.tenantId(),
            actor.environmentId(),
            itemId);
    if (changed != 1) {
      throw conflict("ITEM_WITHDRAWN", "A withdrawn knowledge item cannot be changed.");
    }
    return jdbc.queryForObject(
        "SELECT revision FROM " + SCHEMA
            + "knowledge_item WHERE tenant_id = ? AND environment_id = ? AND item_id = ?",
        Long.class,
        actor.tenantId(),
        actor.environmentId(),
        itemId);
  }

  private void appendOutbox(
      DelegationPrincipal actor,
      String aggregateId,
      long sequence,
      String eventType,
      Map<String, Object> payload) {
    String encoded = writeJson(payload);
    jdbc.update(
        "INSERT INTO " + SCHEMA
            + "outbox_event (event_id, tenant_id, environment_id, aggregate_type, aggregate_id, "
            + "aggregate_sequence, event_type, payload, payload_digest) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)",
        UUID.randomUUID(),
        actor.tenantId(),
        actor.environmentId(),
        "uacrf.s08.retrieval.retained.v1".equals(eventType) ? "RETRIEVAL" : "KNOWLEDGE_ITEM",
        aggregateId,
        sequence,
        eventType,
        encoded,
        sha256(encoded.getBytes(StandardCharsets.UTF_8)));
  }

  private static ItemVersion itemVersion(ResultSet rs) throws SQLException {
    return new ItemVersion(
        rs.getString("item_id"),
        rs.getString("version"),
        rs.getString("title"),
        rs.getString("source_ref"),
        Classification.valueOf(rs.getString("classification")),
        strings(rs.getArray("allowed_purposes")),
        strings(rs.getArray("target_workload_ids")),
        rs.getTimestamp("review_deadline").toInstant().toString(),
        rs.getString("reason"),
        rs.getString("source_digest").trim(),
        rs.getString("state"),
        rs.getLong("revision"),
        rs.getString("proposed_by"),
        rs.getString("reviewed_by"),
        rs.getString("review_reason"),
        rs.getString("withdrawn_by"),
        rs.getString("withdrawal_reason"),
        rs.getTimestamp("created_at").toInstant().toString());
  }

  private static VersionRow versionRow(ResultSet rs) throws SQLException {
    return new VersionRow(
        rs.getString("item_id"),
        rs.getString("version"),
        rs.getString("state"),
        rs.getLong("revision"),
        rs.getString("proposed_by"),
        rs.getTimestamp("review_deadline").toInstant(),
        rs.getString("source_digest").trim(),
        rs.getString("content"),
        Classification.valueOf(rs.getString("classification")),
        strings(rs.getArray("allowed_purposes")),
        strings(rs.getArray("target_workload_ids")));
  }

  private static StoredResult storedResult(ResultSet rs) throws SQLException {
    return new StoredResult(
        rs.getString("item_id"),
        rs.getString("version"),
        rs.getInt("chunk_index"),
        rs.getInt("start_offset"),
        rs.getInt("end_offset"),
        rs.getString("source_digest").trim(),
        rs.getString("excerpt_digest").trim(),
        strings(rs.getArray("allowed_purposes")),
        Classification.valueOf(rs.getString("classification")),
        rs.getInt("score"),
        rs.getString("excerpt"),
        rs.getString("source_content"),
        rs.getString("source_state"),
        rs.getBoolean("item_withdrawn"),
        rs.getTimestamp("source_review_deadline").toInstant(),
        rs.getString("current_source_digest").trim(),
        Classification.valueOf(rs.getString("source_classification")),
        strings(rs.getArray("source_allowed_purposes")),
        strings(rs.getArray("source_target_workload_ids")));
  }

  private static Array stringArray(PreparedStatement statement, List<String> values) throws SQLException {
    return statement.getConnection().createArrayOf("text", values.toArray(String[]::new));
  }

  private static void bindResult(
      PreparedStatement statement, DelegationPrincipal actor, String retrievalId, int order, RankedChunk chunk)
      throws SQLException {
    statement.setString(1, actor.tenantId());
    statement.setString(2, actor.environmentId());
    statement.setString(3, retrievalId);
    statement.setInt(4, order);
    statement.setString(5, chunk.itemId());
    statement.setString(6, chunk.version());
    statement.setInt(7, chunk.chunkIndex());
    statement.setInt(8, chunk.startOffset());
    statement.setInt(9, chunk.endOffset());
    statement.setString(10, chunk.sourceDigest());
    statement.setString(11, chunk.excerptDigest());
    statement.setArray(12, statement.getConnection().createArrayOf("text", chunk.allowedPurposes().toArray(String[]::new)));
    statement.setString(13, chunk.classification().name());
    statement.setInt(14, chunk.score());
    statement.setString(15, chunk.excerpt());
  }

  private static List<String> strings(Array array) throws SQLException {
    Object[] values = (Object[]) array.getArray();
    List<String> strings = new ArrayList<>(values.length);
    for (Object value : values) {
      strings.add((String) value);
    }
    return List.copyOf(strings);
  }

  private static byte[] validatePlainText(String content) {
    if (content.isBlank()) {
      throw invalid("INVALID_CONTENT", "content must contain non-whitespace plaintext.");
    }
    for (int i = 0; i < content.length(); i++) {
      char value = content.charAt(i);
      if (Character.isISOControl(value) && value != '\n' && value != '\r' && value != '\t') {
        throw invalid("UNSUPPORTED_CONTENT", "content must be bounded UTF-8 plaintext.");
      }
    }
    try {
      ByteBuffer bytes =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(content));
      if (bytes.remaining() > MAX_CONTENT_BYTES) {
        throw invalid("CONTENT_TOO_LARGE", "content must not exceed 256 KiB when encoded as UTF-8.");
      }
      byte[] contentBytes = new byte[bytes.remaining()];
      bytes.get(contentBytes);
      return contentBytes;
    } catch (CharacterCodingException error) {
      throw invalid("UNSUPPORTED_CONTENT", "content must be valid UTF-8 plaintext.");
    }
  }

  private static void validateSet(List<String> values, String name, Pattern pattern) {
    Set<String> unique = new LinkedHashSet<>(values);
    if (unique.size() != values.size()
        || values.stream().anyMatch(value -> pattern != null && !pattern.matcher(value).matches())) {
      throw invalid("INVALID_REQUEST", name + " must contain unique valid values.");
    }
  }

  private static List<String> terms(String query) {
    var matcher = TERMS.matcher(query.toLowerCase(Locale.ROOT));
    LinkedHashSet<String> terms = new LinkedHashSet<>();
    while (matcher.find()) {
      terms.add(matcher.group());
    }
    return List.copyOf(terms);
  }

  private static List<Chunk> chunks(String content) {
    List<Chunk> chunks = new ArrayList<>();
    int startIndex = 0;
    int startOffset = 0;
    while (startIndex < content.length()) {
      int count = Math.min(MAX_CHUNK_CHARS, content.codePointCount(startIndex, content.length()));
      int endIndex = content.offsetByCodePoints(startIndex, count);
      int endOffset = startOffset + count;
      chunks.add(new Chunk(startOffset, endOffset, content.substring(startIndex, endIndex)));
      startIndex = endIndex;
      startOffset = endOffset;
    }
    return List.copyOf(chunks);
  }

  private static int score(String content, List<String> queryTerms) {
    Set<String> found = new LinkedHashSet<>(terms(content));
    int score = 0;
    for (String term : queryTerms) {
      if (found.contains(term)) {
        score++;
      }
    }
    return score;
  }

  private static int classificationRank(Classification classification) {
    return switch (classification) {
      case PUBLIC -> 0;
      case INTERNAL -> 1;
      case RESTRICTED -> 2;
    };
  }

  private static Classification maxClassification(DelegationPrincipal actor) {
    if (actor.capabilities().contains("knowledge.classification.restricted.read")) {
      return Classification.RESTRICTED;
    }
    if (actor.capabilities().contains("knowledge.classification.internal.read")) {
      return Classification.INTERNAL;
    }
    return Classification.PUBLIC;
  }

  private static void requireClassification(
      DelegationPrincipal actor, Classification requested) {
    if (classificationRank(requested) > classificationRank(maxClassification(actor))) {
      throw new KnowledgeException(
          HttpStatus.FORBIDDEN,
          "PERMISSION_DENIED",
          "The current principal cannot access the requested knowledge classification.");
    }
  }

  private String digestJson(Object value) {
    return sha256(writeJson(value).getBytes(StandardCharsets.UTF_8));
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException error) {
      throw new IllegalStateException("Could not serialize the retained knowledge manifest.", error);
    }
  }

  private static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is required by the runtime.", error);
    }
  }

  private static void requireRevision(long actual, long expected) {
    if (actual != expected) {
      throw conflict("REVISION_MISMATCH", "The knowledge version changed since it was read.");
    }
  }

  private static void requireCapability(DelegationPrincipal actor, String capability) {
    if (!actor.capabilities().contains(capability)) {
      throw new KnowledgeException(
          HttpStatus.FORBIDDEN,
          "PERMISSION_DENIED",
          "The current principal cannot perform this knowledge operation.");
    }
  }

  private static void requireGlobalScope(DelegationPrincipal actor) {
    if (!"*".equals(actor.serviceScope())) {
      throw new KnowledgeException(
          HttpStatus.FORBIDDEN,
          "PERMISSION_DENIED",
          "This knowledge operation requires a global service scope.");
    }
  }

  private static KnowledgeException notFound() {
    return new KnowledgeException(HttpStatus.NOT_FOUND, "NOT_FOUND", "The knowledge record does not exist.");
  }

  private static KnowledgeException invalid(String code, String message) {
    return new KnowledgeException(HttpStatus.BAD_REQUEST, code, message);
  }

  private static KnowledgeException conflict(String code, String message) {
    return new KnowledgeException(HttpStatus.CONFLICT, code, message);
  }

  private record Chunk(int start, int end, String text) {}

  private record VersionRow(
      String itemId,
      String version,
      String state,
      long revision,
      String proposedBy,
      Instant reviewDeadline,
      String sourceDigest,
      String content,
      Classification classification,
      List<String> allowedPurposes,
      List<String> targetWorkloadIds) {}

  private record RankedChunk(
      String itemId,
      String version,
      int chunkIndex,
      int startOffset,
      int endOffset,
      String sourceDigest,
      String excerptDigest,
      List<String> allowedPurposes,
      Classification classification,
      int score,
      String excerpt) {
    ManifestEntry manifestEntry() {
      return new ManifestEntry(
          itemId,
          version,
          chunkIndex,
          startOffset,
          endOffset,
          sourceDigest,
          excerptDigest,
          allowedPurposes,
          classification,
          score);
    }
  }

  private record ManifestEntry(
      String itemId,
      String version,
      int chunkIndex,
      int startOffset,
      int endOffset,
      String sourceDigest,
      String excerptDigest,
      List<String> allowedPurposes,
      Classification classification,
      int score) {}

  private record Manifest(
      String retrievalId,
      String caseId,
      String caseBinding,
      String caseDigest,
      String purpose,
      Classification maxClassification,
      String query,
      String workloadId,
      String createdAt,
      List<ManifestEntry> results) {}

  private record StoredRetrieval(
      String retrievalId,
      String caseId,
      String caseBinding,
      String caseDigest,
      String purpose,
      Classification maxClassification,
      String query,
      String manifestDigest,
      String workloadId,
      Instant createdAt) {}

  private record StoredResult(
      String itemId,
      String version,
      int chunkIndex,
      int startOffset,
      int endOffset,
      String sourceDigest,
      String excerptDigest,
      List<String> allowedPurposes,
      Classification classification,
      int score,
      String excerpt,
      String sourceContent,
      String sourceState,
      boolean itemWithdrawn,
      Instant sourceReviewDeadline,
      String currentSourceDigest,
      Classification sourceClassification,
      List<String> sourceAllowedPurposes,
      List<String> sourceTargetWorkloadIds) {}
}
