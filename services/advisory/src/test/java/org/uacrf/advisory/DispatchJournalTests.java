package org.uacrf.advisory;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class DispatchJournalTests {
  private static final Instant TEST_DEADLINE = Instant.parse("2099-01-01T00:00:00.123456789Z");
  @Container
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
      "postgres@sha256:3a4264520e121176619f54ff867d12774bc81e95e7b84db58d63bccdb6144b81")
      .withDatabaseName("uacrf_advisory").withUsername("advisory_test").withPassword("advisory_test");
  private static DataSource dataSource;

  @BeforeAll
  static void migrate() throws Exception {
    PGSimpleDataSource postgres = new PGSimpleDataSource();
    postgres.setURL(POSTGRES.getJdbcUrl());
    postgres.setUser(POSTGRES.getUsername());
    postgres.setPassword(POSTGRES.getPassword());
    dataSource = postgres;
    Flyway.configure().dataSource(dataSource).target("5").load().migrate();
    var journal = new DispatchJournal(dataSource);
    for (String runId : List.of("pending", "published")) {
      var runKey = new DispatchJournal.RunKey("migration-backfill", "env", runId);
      assertTrue(registerRun(journal, runKey, 1, budget(), TEST_DEADLINE));
      String eventId = "legacy-" + runId;
      String state = runId.equals("published") ? "PUBLISHED" : "PENDING";
      try (Connection connection = dataSource.getConnection();
           var statement = connection.prepareStatement("""
               INSERT INTO uacrf_advisory.outbox_event
                 (event_id, tenant_id, environment_id, run_id, generation, event_type, payload, state, published_at)
               VALUES (?, ?, ?, ?, 1, 'uacrf.s09.run.admitted.v1',
                 jsonb_build_object('run_id', ?, 'generation', 1, 'request_digest', ?), ?,
                 CASE WHEN ? = 'PUBLISHED' THEN clock_timestamp() END)
               """)) {
        statement.setString(1, eventId);
        statement.setString(2, runKey.tenantId());
        statement.setString(3, runKey.environmentId());
        statement.setString(4, runKey.runId());
        statement.setString(5, runKey.runId());
        statement.setString(6, "a".repeat(64));
        statement.setString(7, state);
        statement.setString(8, state);
        assertEquals(1, statement.executeUpdate());
      }
    }
    Flyway.configure().dataSource(dataSource).load().migrate();
  }

  private static DispatchJournal.Intent intent(String tenant, String attempt, String assignment) {
    var packet = new InputPacket("bounded test packet".getBytes(StandardCharsets.UTF_8));
    return intent(tenant, attempt, assignment, AdvisoryBudget.Role.ANALYST, "analyst", packet);
  }

  private static DispatchJournal.Intent intent(String tenant, String attempt, String assignment,
                                               AdvisoryBudget.Role role, String profile, InputPacket packet) {
    return intent(tenant, attempt, assignment, role, profile, packet, 100);
  }

  private static DispatchJournal.Intent intent(String tenant, String attempt, String assignment,
                                               AdvisoryBudget.Role role, String profile, InputPacket packet,
                                               int maxOutputTokens) {
    return intent(tenant, attempt, assignment, role, profile, packet, maxOutputTokens, Duration.ofSeconds(30));
  }

  private static DispatchJournal.Intent intent(String tenant, String attempt, String assignment,
                                               AdvisoryBudget.Role role, String profile, InputPacket packet,
                                               int maxOutputTokens, Duration timeout) {
    return intent(tenant, attempt, assignment, role, profile, packet, maxOutputTokens, timeout, TEST_DEADLINE);
  }

  private static DispatchJournal.Intent intent(String tenant, String attempt, String assignment,
                                               AdvisoryBudget.Role role, String profile, InputPacket packet,
                                               int maxOutputTokens, Duration timeout, Instant runDeadline) {
    var request = new AdvisoryGateway.Request(attempt, "model-a",
        new String(packet.bytes(), StandardCharsets.UTF_8), maxOutputTokens, timeout);
    return new DispatchJournal.Intent(new DispatchJournal.Key(tenant, "env", attempt),
        "run", assignment, 1, runDeadline, role, profile, request, packet.bytesDigest(), packet);
  }

  @Test
  void dispatchContextDigestMustMatchExactPacketBytes() {
    var packet = new InputPacket("bounded test packet".getBytes(StandardCharsets.UTF_8));
    var request = new AdvisoryGateway.Request("attempt", "model-a", "bounded test packet", 100, Duration.ofSeconds(30));
    assertThrows(IllegalArgumentException.class, () -> new DispatchJournal.Intent(
        new DispatchJournal.Key("digest-binding", "env", "attempt"), "run", "assignment", 1,
        TEST_DEADLINE, AdvisoryBudget.Role.ANALYST, "analyst", request, "a".repeat(64), packet));
  }

  private static AdvisoryBudget budget() {
    return new AdvisoryBudget(2, 300, 10, List.of(
        new AdvisoryBudget.Allocation(AdvisoryBudget.Role.ANALYST, "analyst", 100, 4),
        new AdvisoryBudget.Allocation(AdvisoryBudget.Role.REVIEWER, "reviewer", 200, 6)));
  }

  private static AdvisoryRunRequest admissionRequest(Instant deadline, String reason) {
    String digest = "a".repeat(64);
    String reference = "artifact:sha256:" + digest;
    return new AdvisoryRunRequest("case-1", "retrieval-1",
        AdvisoryRunRequest.WorkflowVersion.INDEPENDENT_REVIEW_V1,
        reference, digest, reference, digest, budget(), deadline, reason, List.of("proposal-1"));
  }

  private static List<DispatchJournal.Intent> dispatchPlan(
      DispatchJournal.RunKey runKey, long generation, AdvisoryBudget budget, Instant deadline,
      DispatchJournal.Intent... preparedIntents) {
    Map<AdvisoryBudget.Role, DispatchJournal.Intent> prepared = new EnumMap<>(AdvisoryBudget.Role.class);
    for (DispatchJournal.Intent intent : preparedIntents) {
      var allocation = budget.allocations().stream()
          .filter(candidate -> candidate.role() == intent.role()).findFirst().orElse(null);
      if (intent.runKey().equals(runKey) && intent.generation() == generation
          && intent.runDeadline().equals(deadline) && allocation != null
          && allocation.agentProfileId().equals(intent.profileId())
          && intent.request().maxOutputTokens() <= allocation.tokens()) {
        prepared.putIfAbsent(intent.role(), intent);
      }
    }
    List<DispatchJournal.Intent> plan = new ArrayList<>();
    for (var allocation : budget.allocations()) {
      var intent = prepared.get(allocation.role());
      if (intent == null) {
        String attempt = runKey.runId() + "-plan-" + allocation.role().name();
        String assignment = runKey.runId() + "-plan-assignment-" + allocation.role().name();
        String context = "prepared-plan:" + runKey.runId() + ":" + allocation.role().name();
        var packet = new InputPacket(context.getBytes(StandardCharsets.UTF_8));
        var gatewayRequest = new AdvisoryGateway.Request(
            attempt, "model-a", context, allocation.tokens(), Duration.ofSeconds(30));
        intent = new DispatchJournal.Intent(
            new DispatchJournal.Key(runKey.tenantId(), runKey.environmentId(), attempt),
            runKey.runId(), assignment, generation, deadline, allocation.role(),
            allocation.agentProfileId(), gatewayRequest, packet.bytesDigest(), packet);
      }
      plan.add(intent);
    }
    return List.copyOf(plan);
  }

  private static boolean registerRun(
      DispatchJournal journal, DispatchJournal.RunKey runKey, long generation, AdvisoryBudget budget,
      Instant deadline, DispatchJournal.Intent... preparedIntents) throws SQLException {
    return journal.registerRun(runKey, generation, budget, deadline,
        dispatchPlan(runKey, generation, budget, deadline, preparedIntents));
  }

  private static DispatchJournal.AdmissionResult admit(
      DispatchJournal journal, DispatchJournal.RunKey key, AdvisoryRunRequest request,
      String principalId, String operation, String idempotencyKey) throws SQLException {
    return journal.admitRun(key, 1, request,
        dispatchPlan(key, 1, request.budget(), request.deadline()),
        principalId, operation, idempotencyKey);
  }

  @Test
  void outboxDeliveryMigrationBackfillsPendingAndPublishedEvents() throws Exception {
    try (Connection connection = dataSource.getConnection();
         var statement = connection.prepareStatement("""
             SELECT event.event_id, event.state, delivery.state, delivery.attempt_count,
                    delivery.published_at IS NOT NULL,
                    delivery.available_at <= clock_timestamp()
             FROM uacrf_advisory.outbox_event event
             JOIN uacrf_advisory.outbox_delivery delivery USING (event_id)
             WHERE event.tenant_id = 'migration-backfill'
             ORDER BY event.event_id
             """);
         var result = statement.executeQuery()) {
      assertTrue(result.next());
      assertEquals("legacy-pending", result.getString(1));
      assertEquals("PENDING", result.getString(2));
      assertEquals("PENDING", result.getString(3));
      assertEquals(0, result.getInt(4));
      assertFalse(result.getBoolean(5));
      assertTrue(result.getBoolean(6));

      assertTrue(result.next());
      assertEquals("legacy-published", result.getString(1));
      assertEquals("PUBLISHED", result.getString(2));
      assertEquals("PUBLISHED", result.getString(3));
      assertEquals(0, result.getInt(4));
      assertTrue(result.getBoolean(5));
      assertTrue(result.getBoolean(6));
      assertFalse(result.next());
    }
  }

  @Test
  void outboxDeliveryInsertFailureRollsBackAdmissionTransaction() throws Exception {
    try (Connection connection = dataSource.getConnection();
         var statement = connection.createStatement()) {
      statement.execute("""
          CREATE FUNCTION uacrf_advisory.fail_test_delivery_insert() RETURNS trigger LANGUAGE plpgsql AS $$
          BEGIN
            IF EXISTS (
              SELECT 1 FROM uacrf_advisory.outbox_event
              WHERE event_id = NEW.event_id AND tenant_id = 'admission-delivery-rollback'
            ) THEN
              RAISE EXCEPTION 'simulated delivery persistence failure' USING ERRCODE = '23514';
            END IF;
            RETURN NEW;
          END $$
          """);
      statement.execute("""
          CREATE TRIGGER fail_test_delivery_insert
          BEFORE INSERT ON uacrf_advisory.outbox_delivery
          FOR EACH ROW EXECUTE FUNCTION uacrf_advisory.fail_test_delivery_insert()
          """);
    }

    var runKey = new DispatchJournal.RunKey("admission-delivery-rollback", "env", "run-1");
    try {
      assertThrows(SQLException.class, () -> admit(new DispatchJournal(dataSource), runKey,
          admissionRequest(TEST_DEADLINE, "Delivery persistence failure"),
          "principal-1", "CREATE_ADVISORY_RUN", "idem-delivery-rollback"));
    } finally {
      try (Connection connection = dataSource.getConnection();
           var statement = connection.createStatement()) {
        statement.execute("DROP TRIGGER fail_test_delivery_insert ON uacrf_advisory.outbox_delivery");
        statement.execute("DROP FUNCTION uacrf_advisory.fail_test_delivery_insert()");
      }
    }
    assertEquals(0, countForTenant("uacrf_advisory.run_fence", runKey.tenantId()));
    assertEquals(0, countForTenant("uacrf_advisory.run_idempotency", runKey.tenantId()));
    assertEquals(0, countForTenant("uacrf_advisory.outbox_event", runKey.tenantId()));
    assertEquals(0, countOutboxDeliveryForTenant(runKey.tenantId()));
  }

  @Test
  void admissionAtomicallyPersistsRunIdempotencyAndSchedulingEvent() throws Exception {
    var journal = new DispatchJournal(dataSource);
    var requestedRun = new DispatchJournal.RunKey("admission", "env", "run-1");
    var request = admissionRequest(TEST_DEADLINE, "Run assessment");
    var plan = dispatchPlan(requestedRun, 1, request.budget(), request.deadline());
    var incompleteRun = new DispatchJournal.RunKey("admission", "env", "run-incomplete");
    assertThrows(IllegalArgumentException.class, () -> journal.admitRun(
        incompleteRun, 1, request, plan.subList(0, 1),
        "principal-1", "CREATE_ADVISORY_RUN", "idem-incomplete"));
    assertTrue(journal.findRun(incompleteRun).isEmpty());
    var created = journal.admitRun(requestedRun, 1, request, plan,
        "principal-1", "CREATE_ADVISORY_RUN", "idem-1");
    assertEquals(DispatchJournal.AdmissionStatus.CREATED, created.status());
    assertEquals(java.util.Optional.of(requestedRun), created.runKey());
    var original = plan.get(0);
    var substitutedRequest = new AdvisoryGateway.Request(original.request().attemptId(), "substituted-model",
        original.request().context(), original.request().maxOutputTokens(), original.request().timeout());
    var substituted = new DispatchJournal.Intent(original.key(), original.runId(), original.assignmentId(),
        original.generation(), original.runDeadline(), original.role(), original.profileId(), substitutedRequest,
        original.contextDigest(), original.packet());
    assertFalse(journal.begin(substituted));
    assertTrue(journal.begin(original));

    var replay = journal.admitRun(new DispatchJournal.RunKey("admission", "env", "run-replay"),
        1, request, null, "principal-1", "CREATE_ADVISORY_RUN", "idem-1");
    assertEquals(DispatchJournal.AdmissionStatus.REPLAYED, replay.status());
    assertEquals(java.util.Optional.of(requestedRun), replay.runKey());

    var conflict = journal.admitRun(new DispatchJournal.RunKey("admission", "env", "run-conflict"),
        1, admissionRequest(TEST_DEADLINE, "Changed reason"), null,
        "principal-1", "CREATE_ADVISORY_RUN", "idem-1");
    assertEquals(DispatchJournal.AdmissionStatus.IDEMPOTENCY_CONFLICT, conflict.status());
    assertTrue(conflict.runKey().isEmpty());

    var anotherPrincipal = admit(journal, new DispatchJournal.RunKey("admission", "env", "run-2"),
        request, "principal-2", "CREATE_ADVISORY_RUN", "idem-1");
    assertEquals(DispatchJournal.AdmissionStatus.CREATED, anotherPrincipal.status());
    assertEquals(DispatchJournal.AdmissionStatus.CREATED,
        admit(journal, new DispatchJournal.RunKey("admission", "env", "run-3"), request,
            "principal-1", "CREATE_OTHER_OPERATION", "idem-1").status());
    assertEquals(DispatchJournal.AdmissionStatus.CREATED,
        admit(journal, new DispatchJournal.RunKey("admission", "env-2", "run-4"), request,
            "principal-1", "CREATE_ADVISORY_RUN", "idem-1").status());
    assertEquals(DispatchJournal.AdmissionStatus.CREATED,
        admit(journal, new DispatchJournal.RunKey("admission-other-tenant", "env", "run-5"),
            request, "principal-1", "CREATE_ADVISORY_RUN", "idem-1").status());

    try (Connection connection = dataSource.getConnection();
         var statement = connection.prepareStatement("""
             SELECT
               (SELECT count(*) FROM uacrf_advisory.run_fence WHERE tenant_id = ?),
               (SELECT count(*) FROM uacrf_advisory.run_idempotency WHERE tenant_id = ?),
               (SELECT count(*) FROM uacrf_advisory.outbox_event WHERE tenant_id = ?),
               (SELECT payload->>'run_id' FROM uacrf_advisory.outbox_event
                 WHERE tenant_id = ? AND run_id = 'run-1'),
               (SELECT count(*) FROM uacrf_advisory.run_dispatch_plan
                 WHERE tenant_id = ? AND run_id = 'run-1'),
               (SELECT count(*) FROM uacrf_advisory.outbox_delivery delivery
                 JOIN uacrf_advisory.outbox_event event USING (event_id)
                 WHERE event.tenant_id = ?)
             """)) {
      for (int index = 1; index <= 6; index++) {
        statement.setString(index, "admission");
      }
      try (var result = statement.executeQuery()) {
        assertTrue(result.next());
        assertEquals(4, result.getInt(1));
        assertEquals(4, result.getInt(2));
        assertEquals(4, result.getInt(3));
        assertEquals("run-1", result.getString(4));
        assertEquals(2, result.getInt(5));
        assertEquals(4, result.getInt(6));
      }
    }
    assertEquals(1, countForTenant("uacrf_advisory.run_idempotency", "admission-other-tenant"));
    assertEquals(1, countForTenant("uacrf_advisory.outbox_event", "admission-other-tenant"));
    try (Connection connection = dataSource.getConnection();
         var statement = connection.createStatement()) {
      assertThrows(SQLException.class, () -> statement.executeUpdate(
          "UPDATE uacrf_advisory.run_idempotency SET request_digest = '" + "e".repeat(64)
              + "' WHERE tenant_id = 'admission' AND run_id = 'run-1'"));
      assertThrows(SQLException.class, () -> statement.executeUpdate(
          "DELETE FROM uacrf_advisory.run_idempotency WHERE tenant_id = 'admission' AND run_id = 'run-1'"));
      assertThrows(SQLException.class, () -> statement.executeUpdate(
          "UPDATE uacrf_advisory.run_dispatch_plan SET model_ref = 'substituted'"
              + " WHERE tenant_id = 'admission' AND run_id = 'run-1'"));
      assertThrows(SQLException.class, () -> statement.executeUpdate(
          "DELETE FROM uacrf_advisory.run_dispatch_plan"
              + " WHERE tenant_id = 'admission' AND run_id = 'run-1'"));
      assertThrows(SQLException.class, () -> statement.executeUpdate(
          "UPDATE uacrf_advisory.outbox_event SET payload = '{}'::jsonb"
              + " WHERE tenant_id = 'admission' AND run_id = 'run-1'"));
      assertThrows(SQLException.class, () -> statement.executeUpdate(
          "DELETE FROM uacrf_advisory.outbox_event WHERE tenant_id = 'admission' AND run_id = 'run-1'"));
      assertThrows(SQLException.class, () -> statement.executeUpdate("""
          UPDATE uacrf_advisory.outbox_event
          SET published_at = clock_timestamp()
          WHERE tenant_id = 'admission' AND run_id = 'run-1'
          """));
      assertThrows(SQLException.class, () -> statement.executeUpdate("""
          UPDATE uacrf_advisory.outbox_event
          SET state = 'PUBLISHED', published_at = clock_timestamp()
          WHERE tenant_id = 'admission' AND run_id = 'run-1'
          """));
    }
    var claim = journal.claimPendingOutbox("admission", "env", 100, Duration.ofMinutes(1)).stream()
        .filter(candidate -> candidate.runId().equals("run-1")).findFirst().orElseThrow();
    assertEquals("uacrf.s09.run.admitted.v1", claim.eventType());
    assertTrue(claim.payloadJson().contains("\"run_id\": \"run-1\""));
    assertTrue(journal.markOutboxPublished(claim));
  }

  @Test
  void outboxClaimsAreExclusiveAndPublicationRequiresCurrentLeaseToken() throws Exception {
    var journal = new DispatchJournal(dataSource);
    String tenant = "outbox-claim";
    for (int index = 1; index <= 4; index++) {
      assertEquals(DispatchJournal.AdmissionStatus.CREATED,
          admit(journal, new DispatchJournal.RunKey(tenant, "env", "run-" + index),
              admissionRequest(TEST_DEADLINE, "Outbox claim test"),
              "principal-1", "CREATE_ADVISORY_RUN", "idem-" + index).status());
    }

    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      Future<List<DispatchJournal.OutboxClaim>> first = executor.submit(() -> {
        start.await();
        return journal.claimPendingOutbox(tenant, "env", 2, Duration.ofMinutes(1));
      });
      Future<List<DispatchJournal.OutboxClaim>> second = executor.submit(() -> {
        start.await();
        return journal.claimPendingOutbox(tenant, "env", 2, Duration.ofMinutes(1));
      });
      start.countDown();
      var firstClaims = first.get(20, TimeUnit.SECONDS);
      var secondClaims = second.get(20, TimeUnit.SECONDS);
      assertEquals(2, firstClaims.size());
      assertEquals(2, secondClaims.size());
      for (var firstClaim : firstClaims) {
        assertFalse(secondClaims.stream().anyMatch(claim -> claim.eventId().equals(firstClaim.eventId())));
      }
    }

    String expiringTenant = "outbox-expiry";
    admit(journal, new DispatchJournal.RunKey(expiringTenant, "env", "run-1"),
        admissionRequest(TEST_DEADLINE, "Outbox lease expiry test"),
        "principal-1", "CREATE_ADVISORY_RUN", "idem-expiry");
    var initialClaim = journal.claimPendingOutbox(expiringTenant, "env", 1, Duration.ofMillis(1))
        .getFirst();
    Thread.sleep(20);
    assertFalse(journal.markOutboxPublished(initialClaim));
    assertFalse(journal.renewOutboxClaim(initialClaim, Duration.ofSeconds(1)));
    assertFalse(journal.releaseOutboxClaim(initialClaim, DispatchJournal.outboxRetryDelay(initialClaim)));
    var reclaimed = journal.claimPendingOutbox(expiringTenant, "env", 1, Duration.ofSeconds(1))
        .getFirst();
    assertEquals(initialClaim.eventId(), reclaimed.eventId());
    assertEquals(2, reclaimed.attemptCount());
    assertFalse(initialClaim.claimToken().equals(reclaimed.claimToken()));
    assertFalse(journal.markOutboxPublished(initialClaim));
    assertFalse(journal.renewOutboxClaim(initialClaim, Duration.ofSeconds(1)));
    assertFalse(journal.releaseOutboxClaim(initialClaim, DispatchJournal.outboxRetryDelay(initialClaim)));
    assertTrue(journal.markOutboxPublished(reclaimed));
    assertFalse(journal.markOutboxPublished(reclaimed));
    try (Connection connection = dataSource.getConnection();
         var statement = connection.prepareStatement("""
             SELECT delivery.state, event.state
             FROM uacrf_advisory.outbox_delivery delivery
             JOIN uacrf_advisory.outbox_event event USING (event_id)
             WHERE event.event_id = ?
             """)) {
      statement.setString(1, reclaimed.eventId());
      try (var result = statement.executeQuery()) {
        assertTrue(result.next());
        assertEquals("PUBLISHED", result.getString(1));
        assertEquals("PUBLISHED", result.getString(2));
      }
    }
  }

  @Test
  void currentOutboxClaimCanExtendItsLeaseWithoutReleasingExclusivity() throws Exception {
    var journal = new DispatchJournal(dataSource);
    String tenant = "outbox-renewal";
    admit(journal, new DispatchJournal.RunKey(tenant, "env", "run-1"),
        admissionRequest(TEST_DEADLINE, "Outbox lease renewal test"),
        "principal-1", "CREATE_ADVISORY_RUN", "idem-renewal");
    var claim = journal.claimPendingOutbox(tenant, "env", 1, Duration.ofMillis(100)).getFirst();

    assertTrue(journal.renewOutboxClaim(claim, Duration.ofSeconds(1)));
    Thread.sleep(150);
    assertTrue(journal.claimPendingOutbox(tenant, "env", 1, Duration.ofSeconds(1)).isEmpty());
    assertTrue(journal.markOutboxPublished(claim));
  }

  @Test
  void cancelledRunsCannotBeClaimedOrCompleteAnExistingClaim() throws Exception {
    var journal = new DispatchJournal(dataSource);
    String tenant = "outbox-cancelled-run";
    var claimedRun = new DispatchJournal.RunKey(tenant, "env", "claimed");
    admit(journal, claimedRun, admissionRequest(TEST_DEADLINE, "Cancelled outbox test"),
        "principal-1", "CREATE_ADVISORY_RUN", "idem-claimed");
    var claim = journal.claimPendingOutbox(tenant, "env", 1, Duration.ofMinutes(1)).getFirst();
    assertTrue(journal.cancel(claimedRun, 1, 1, "reviewer", "Cancelled before delivery."));
    assertFalse(journal.renewOutboxClaim(claim, Duration.ofSeconds(1)));
    assertFalse(journal.releaseOutboxClaim(claim, DispatchJournal.outboxRetryDelay(claim)));
    assertFalse(journal.markOutboxPublished(claim));

    var pendingRun = new DispatchJournal.RunKey(tenant, "env", "pending");
    admit(journal, pendingRun, admissionRequest(TEST_DEADLINE, "Cancelled pending outbox test"),
        "principal-1", "CREATE_ADVISORY_RUN", "idem-pending");
    assertTrue(journal.cancel(pendingRun, 1, 1, "reviewer", "Cancelled before claim."));
    assertTrue(journal.claimPendingOutbox(tenant, "env", 10, Duration.ofMinutes(1)).isEmpty());
  }

  @Test
  void outboxClaimSkipsALockedRunFence() throws Exception {
    var journal = new DispatchJournal(dataSource);
    String tenant = "outbox-run-fence-lock";
    var runKey = new DispatchJournal.RunKey(tenant, "env", "run-1");
    admit(journal, runKey, admissionRequest(TEST_DEADLINE, "Outbox run fence lock test"),
        "principal-1", "CREATE_ADVISORY_RUN", "idem-run-fence-lock");

    try (Connection connection = dataSource.getConnection();
         var statement = connection.prepareStatement("""
             SELECT 1 FROM uacrf_advisory.run_fence
             WHERE tenant_id = ? AND environment_id = ? AND run_id = ? FOR UPDATE
             """)) {
      connection.setAutoCommit(false);
      statement.setString(1, tenant);
      statement.setString(2, "env");
      statement.setString(3, runKey.runId());
      try (var result = statement.executeQuery()) {
        assertTrue(result.next());
      }
      assertTrue(journal.claimPendingOutbox(tenant, "env", 1, Duration.ofSeconds(1)).isEmpty());
      connection.rollback();
    }

    assertEquals(1, journal.claimPendingOutbox(tenant, "env", 1, Duration.ofSeconds(1)).size());
  }

  @Test
  void expiredRunsCannotClaimOrAdvanceOutboxDelivery() throws Exception {
    var journal = new DispatchJournal(dataSource);
    String tenant = "outbox-expired-run";
    Instant deadline = Instant.now().plusSeconds(3);
    admit(journal, new DispatchJournal.RunKey(tenant, "env", "run-1"),
        admissionRequest(deadline, "Expiring outbox test"),
        "principal-1", "CREATE_ADVISORY_RUN", "idem-expired");
    var claim = journal.claimPendingOutbox(tenant, "env", 1, Duration.ofMinutes(5)).getFirst();
    long remainingMillis = Duration.between(Instant.now(), deadline).toMillis();
    if (remainingMillis > 0) {
      Thread.sleep(remainingMillis + 100);
    }
    assertTrue(journal.claimPendingOutbox(tenant, "env", 1, Duration.ofSeconds(1)).isEmpty());
    assertFalse(journal.renewOutboxClaim(claim, Duration.ofSeconds(1)));
    assertFalse(journal.releaseOutboxClaim(claim, DispatchJournal.outboxRetryDelay(claim)));
    assertFalse(journal.markOutboxPublished(claim));
  }

  @Test
  void outboxRetryBackoffDoublesCapsAndJitters() {
    assertThrows(IllegalArgumentException.class, () -> DispatchJournal.outboxRetryBackoff(0));
    assertEquals(Duration.ofSeconds(1), DispatchJournal.outboxRetryBackoff(1));
    assertEquals(Duration.ofSeconds(2), DispatchJournal.outboxRetryBackoff(2));
    assertEquals(Duration.ofSeconds(512), DispatchJournal.outboxRetryBackoff(10));
    assertEquals(Duration.ofMinutes(15), DispatchJournal.outboxRetryBackoff(11));
    assertEquals(Duration.ofMinutes(15), DispatchJournal.outboxRetryBackoff(Integer.MAX_VALUE));
    assertEquals(Duration.ofSeconds(1), DispatchJournal.outboxRetryDelay(
        outboxClaimForBackoff(1, 0)));
    assertEquals(Duration.ofMillis(1500), DispatchJournal.outboxRetryDelay(
        outboxClaimForBackoff(1, 500)));
    assertEquals(Duration.ofSeconds(768), DispatchJournal.outboxRetryDelay(
        outboxClaimForBackoff(10, 256_000)));
    assertEquals(Duration.ofMinutes(15), DispatchJournal.outboxRetryDelay(
        outboxClaimForBackoff(11, 0xffff_ffffL)));
  }

  private static DispatchJournal.OutboxClaim outboxClaimForBackoff(int attemptCount, long tokenBits) {
    return new DispatchJournal.OutboxClaim("event", "tenant", "env", "run", 1,
        "uacrf.s09.run.admitted.v1", "{}", new UUID(0, tokenBits), attemptCount);
  }

  @Test
  void failedOutboxClaimCanBeReleasedWithBoundedRetryDelay() throws Exception {
    var journal = new DispatchJournal(dataSource);
    String tenant = "outbox-retry";
    admit(journal, new DispatchJournal.RunKey(tenant, "env", "run-1"),
        admissionRequest(TEST_DEADLINE, "Outbox retry schedule test"),
        "principal-1", "CREATE_ADVISORY_RUN", "idem-outbox-retry");
    var claim = journal.claimPendingOutbox(tenant, "env", 1, Duration.ofMinutes(1)).getFirst();
    assertThrows(IllegalArgumentException.class,
        () -> journal.releaseOutboxClaim(claim, Duration.ZERO));
    assertThrows(IllegalArgumentException.class,
        () -> journal.releaseOutboxClaim(claim, Duration.ofMinutes(16)));
    Duration retryDelay = DispatchJournal.outboxRetryDelay(claim);
    assertThrows(IllegalArgumentException.class,
        () -> journal.releaseOutboxClaim(claim, retryDelay.minusMillis(1)));
    try (Connection connection = dataSource.getConnection();
         var statement = connection.prepareStatement("""
             UPDATE uacrf_advisory.outbox_delivery
             SET state = 'PENDING', claim_token = NULL, lease_expires_at = NULL,
                 available_at = statement_timestamp()
                   + make_interval(secs => ?::double precision / 1000.0)
             WHERE event_id = ? AND state = 'CLAIMED' AND claim_token = ?
             """)) {
      statement.setLong(1, retryDelay.toMillis() - 1);
      statement.setString(2, claim.eventId());
      statement.setObject(3, claim.claimToken());
      SQLException failure = assertThrows(SQLException.class, statement::executeUpdate);
      assertTrue(failure.getMessage().contains("invalid outbox delivery transition"));
    }
    assertTrue(journal.releaseOutboxClaim(claim, retryDelay));
    assertTrue(journal.claimPendingOutbox(tenant, "env", 1, Duration.ofSeconds(1)).isEmpty());
    assertFalse(journal.markOutboxPublished(claim));
    assertFalse(journal.releaseOutboxClaim(claim, retryDelay));

    Thread.sleep(retryDelay.toMillis() + 200);
    var retry = journal.claimPendingOutbox(tenant, "env", 1, Duration.ofSeconds(1)).getFirst();
    assertEquals(claim.eventId(), retry.eventId());
    assertEquals(2, retry.attemptCount());
    assertFalse(claim.claimToken().equals(retry.claimToken()));
    assertTrue(journal.markOutboxPublished(retry));
  }

  @Test
  void outboxClaimsAndAcknowledgmentsRemainBoundToTenantAndEnvironment() throws Exception {
    var journal = new DispatchJournal(dataSource);
    for (var runKey : List.of(
        new DispatchJournal.RunKey("outbox-scope", "env-a", "run-a"),
        new DispatchJournal.RunKey("outbox-scope", "env-b", "run-b"),
        new DispatchJournal.RunKey("outbox-other-tenant", "env-a", "run-other"))) {
      admit(journal, runKey, admissionRequest(TEST_DEADLINE, "Outbox scope test"),
          "principal-1", "CREATE_ADVISORY_RUN", "idem-" + runKey.runId());
    }

    var envA = journal.claimPendingOutbox("outbox-scope", "env-a", 10, Duration.ofMinutes(1));
    var envB = journal.claimPendingOutbox("outbox-scope", "env-b", 10, Duration.ofMinutes(1));
    var otherTenant = journal.claimPendingOutbox("outbox-other-tenant", "env-a", 10, Duration.ofMinutes(1));
    assertEquals(List.of("run-a"), envA.stream().map(DispatchJournal.OutboxClaim::runId).toList());
    assertEquals(List.of("run-b"), envB.stream().map(DispatchJournal.OutboxClaim::runId).toList());
    assertEquals(List.of("run-other"), otherTenant.stream().map(DispatchJournal.OutboxClaim::runId).toList());

    var claim = envA.getFirst();
    var mismatched = new DispatchJournal.OutboxClaim(
        claim.eventId(), "outbox-other-tenant", claim.environmentId(), claim.runId(), claim.generation(),
        claim.eventType(), claim.payloadJson(), claim.claimToken(), claim.attemptCount());
    assertFalse(journal.renewOutboxClaim(mismatched, Duration.ofSeconds(1)));
    assertFalse(journal.markOutboxPublished(mismatched));
    assertTrue(journal.markOutboxPublished(claim));
  }

  @Test
  void eventPublicationFailureRollsBackDeliveryAcknowledgment() throws Exception {
    var journal = new DispatchJournal(dataSource);
    String tenant = "outbox-event-rollback";
    admit(journal, new DispatchJournal.RunKey(tenant, "env", "run-1"),
        admissionRequest(TEST_DEADLINE, "Outbox publication rollback test"),
        "principal-1", "CREATE_ADVISORY_RUN", "idem-outbox-event-rollback");
    var claim = journal.claimPendingOutbox(tenant, "env", 1, Duration.ofMinutes(1)).getFirst();
    try (Connection connection = dataSource.getConnection();
         var statement = connection.createStatement()) {
      statement.execute("""
          CREATE FUNCTION uacrf_advisory.fail_test_outbox_event_publish() RETURNS trigger LANGUAGE plpgsql AS $$
          BEGIN
            IF OLD.tenant_id = 'outbox-event-rollback' AND NEW.state = 'PUBLISHED' THEN
              RAISE EXCEPTION 'simulated event publication failure' USING ERRCODE = '23514';
            END IF;
            RETURN NEW;
          END $$
          """);
      statement.execute("""
          CREATE TRIGGER fail_test_outbox_event_publish
          BEFORE UPDATE OF state ON uacrf_advisory.outbox_event
          FOR EACH ROW EXECUTE FUNCTION uacrf_advisory.fail_test_outbox_event_publish()
          """);
    }

    try {
      assertThrows(SQLException.class, () -> journal.markOutboxPublished(claim));
    } finally {
      try (Connection connection = dataSource.getConnection();
           var statement = connection.createStatement()) {
        statement.execute("DROP TRIGGER fail_test_outbox_event_publish ON uacrf_advisory.outbox_event");
        statement.execute("DROP FUNCTION uacrf_advisory.fail_test_outbox_event_publish()");
      }
    }
    try (Connection connection = dataSource.getConnection();
         var statement = connection.prepareStatement("""
             SELECT delivery.state, event.state
             FROM uacrf_advisory.outbox_delivery delivery
             JOIN uacrf_advisory.outbox_event event USING (event_id)
             WHERE event.event_id = ?
             """)) {
      statement.setString(1, claim.eventId());
      try (var result = statement.executeQuery()) {
        assertTrue(result.next());
        assertEquals("CLAIMED", result.getString(1));
        assertEquals("PENDING", result.getString(2));
      }
    }
    assertTrue(journal.markOutboxPublished(claim));
  }

  @Test
  void concurrentOutboxAcknowledgmentsHaveOneWinner() throws Exception {
    var journal = new DispatchJournal(dataSource);
    String tenant = "outbox-ack-race";
    admit(journal, new DispatchJournal.RunKey(tenant, "env", "run-1"),
        admissionRequest(TEST_DEADLINE, "Concurrent outbox acknowledgment test"),
        "principal-1", "CREATE_ADVISORY_RUN", "idem-outbox-ack-race");
    var claim = journal.claimPendingOutbox(tenant, "env", 1, Duration.ofMinutes(1)).getFirst();
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      Future<Boolean> first = executor.submit(() -> {
        start.await();
        return journal.markOutboxPublished(claim);
      });
      Future<Boolean> second = executor.submit(() -> {
        start.await();
        return journal.markOutboxPublished(claim);
      });
      start.countDown();
      boolean firstResult = first.get(20, TimeUnit.SECONDS);
      boolean secondResult = second.get(20, TimeUnit.SECONDS);
      assertTrue(firstResult != secondResult);
    }

    try (Connection connection = dataSource.getConnection();
         var statement = connection.prepareStatement("""
             SELECT delivery.state, event.state
             FROM uacrf_advisory.outbox_delivery delivery
             JOIN uacrf_advisory.outbox_event event USING (event_id)
             WHERE event.event_id = ?
             """)) {
      statement.setString(1, claim.eventId());
      try (var result = statement.executeQuery()) {
        assertTrue(result.next());
        assertEquals("PUBLISHED", result.getString(1));
        assertEquals("PUBLISHED", result.getString(2));
      }
    }
  }

  @Test
  void concurrentSameKeyAdmissionCreatesOneRunAndOneSchedulingEvent() throws Exception {
    var journal = new DispatchJournal(dataSource);
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      Future<DispatchJournal.AdmissionResult> first = executor.submit(() -> {
        start.await();
        return admit(journal, new DispatchJournal.RunKey("admission-race", "env", "run-a"),
            admissionRequest(TEST_DEADLINE, "Concurrent run"),
            "principal-1", "CREATE_ADVISORY_RUN", "idem-race");
      });
      Future<DispatchJournal.AdmissionResult> second = executor.submit(() -> {
        start.await();
        return admit(journal, new DispatchJournal.RunKey("admission-race", "env", "run-b"),
            admissionRequest(TEST_DEADLINE, "Concurrent run"),
            "principal-1", "CREATE_ADVISORY_RUN", "idem-race");
      });
      start.countDown();
      var firstResult = first.get(20, TimeUnit.SECONDS);
      var secondResult = second.get(20, TimeUnit.SECONDS);
      assertTrue(List.of(firstResult.status(), secondResult.status()).containsAll(
          List.of(DispatchJournal.AdmissionStatus.CREATED, DispatchJournal.AdmissionStatus.REPLAYED)));
      assertEquals(firstResult.runKey(), secondResult.runKey());
      assertTrue(firstResult.runKey().isPresent());
      assertEquals(1, countForTenant("uacrf_advisory.run_fence", "admission-race"));
      assertEquals(1, countForTenant("uacrf_advisory.run_idempotency", "admission-race"));
      assertEquals(1, countForTenant("uacrf_advisory.outbox_event", "admission-race"));
      assertEquals(1, countOutboxDeliveryForTenant("admission-race"));
    }
  }

  @Test
  void failedDeadlineAdmissionLeavesNoRunIdempotencyOrOutboxRows() throws Exception {
    var journal = new DispatchJournal(dataSource);
    var runKey = new DispatchJournal.RunKey("admission-rollback", "env", "run-1");
    assertThrows(SQLException.class, () -> admit(journal, runKey,
        admissionRequest(Instant.EPOCH, "Expired run"),
        "principal-1", "CREATE_ADVISORY_RUN", "idem-rollback"));
    assertTrue(journal.findRun(runKey).isEmpty());
    assertEquals(0, countForTenant("uacrf_advisory.run_idempotency", "admission-rollback"));
    assertEquals(0, countForTenant("uacrf_advisory.outbox_event", "admission-rollback"));
    assertEquals(0, countOutboxDeliveryForTenant("admission-rollback"));
  }

  private static int countOutboxDeliveryForTenant(String tenant) throws SQLException {
    try (Connection connection = dataSource.getConnection();
         var statement = connection.prepareStatement("""
             SELECT count(*)
             FROM uacrf_advisory.outbox_delivery delivery
             JOIN uacrf_advisory.outbox_event event USING (event_id)
             WHERE event.tenant_id = ?
             """)) {
      statement.setString(1, tenant);
      try (var result = statement.executeQuery()) {
        result.next();
        return result.getInt(1);
      }
    }
  }

  private static int countForTenant(String table, String tenant) throws SQLException {
    if (!List.of("uacrf_advisory.run_fence", "uacrf_advisory.run_idempotency", "uacrf_advisory.run_dispatch_plan",
        "uacrf_advisory.outbox_event").contains(table)) {
      throw new IllegalArgumentException("Unexpected test table.");
    }
    try (Connection connection = dataSource.getConnection();
         var statement = connection.prepareStatement("SELECT count(*) FROM " + table + " WHERE tenant_id = ?")) {
      statement.setString(1, tenant);
      try (var result = statement.executeQuery()) {
        result.next();
        return result.getInt(1);
      }
    }
  }

  @Test
  void committedIntentSurvivesReopeningAndNeverAuthorizesDuplicate() throws Exception {
    var journal = new DispatchJournal(dataSource);
    var intent = intent("intent", "attempt", "assignment");
    assertTrue(registerRun(journal, intent.runKey(), intent.generation(), budget(), TEST_DEADLINE, intent));
    assertTrue(journal.begin(intent));

    var reopened = new DispatchJournal(dataSource);
    var retained = reopened.find(intent.key()).orElseThrow();
    assertEquals(intent.key(), retained.intent().key());
    assertEquals(TEST_DEADLINE, retained.intent().runDeadline());
    assertEquals(intent.role(), retained.intent().role());
    assertEquals(intent.profileId(), retained.intent().profileId());
    assertEquals(intent.contextDigest(), retained.intent().contextDigest());
    assertEquals(intent.request(), retained.intent().request());
    assertArrayEquals(intent.packet().bytes(), retained.intent().packet().bytes());
    assertEquals(intent.packet().bytesDigest(), retained.intent().packet().bytesDigest());
    assertTrue(retained.outcome().isEmpty());
    assertTrue(retained.recordingFence().isEmpty());
    assertFalse(reopened.begin(intent));
    assertFalse(reopened.begin(intent("intent", "another-attempt", "assignment")));
    assertTrue(reopened.find(new DispatchJournal.Key("other-tenant", "env", "attempt")).isEmpty());
    assertTrue(reopened.find(new DispatchJournal.Key("intent", "other-env", "attempt")).isEmpty());
    assertFalse(reopened.complete(new DispatchJournal.Key("other-tenant", "env", "attempt"),
        new AdvisoryGateway.Unknown("wrong scope")));
    try (Connection connection = dataSource.getConnection();
         var statement = connection.createStatement()) {
      assertThrows(SQLException.class, () -> statement.executeUpdate(
          "UPDATE uacrf_advisory.dispatch_journal SET context_digest = '" + "b".repeat(64)
              + "' WHERE tenant_id = 'intent'"));
      assertThrows(SQLException.class, () -> statement.executeUpdate(
          "UPDATE uacrf_advisory.dispatch_journal SET model_ref = 'substituted' WHERE tenant_id = 'intent'"));
      assertThrows(SQLException.class, () -> statement.executeUpdate(
          "UPDATE uacrf_advisory.dispatch_journal SET packet_bytes = decode('ff', 'hex')"
              + " WHERE tenant_id = 'intent'"));
      assertThrows(SQLException.class, () -> statement.executeUpdate(
          "DELETE FROM uacrf_advisory.dispatch_journal WHERE tenant_id = 'intent'"));
    }
  }

  @Test
  void legacyIntentCanBeFinalizedButCannotBeReconstructedOrRetried() throws Exception {
    var journal = new DispatchJournal(dataSource);
    var intent = intent("legacy-intent", "attempt", "assignment");
    assertTrue(registerRun(journal, intent.runKey(), intent.generation(), budget(), TEST_DEADLINE, intent));
    assertTrue(journal.begin(intent));
    try (Connection connection = dataSource.getConnection();
         var statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      statement.execute("ALTER TABLE uacrf_advisory.dispatch_journal DISABLE TRIGGER protect_dispatch_journal");
      statement.executeUpdate("""
          UPDATE uacrf_advisory.dispatch_journal
          SET model_ref = NULL, max_output_tokens = NULL, timeout_seconds = NULL, timeout_nanos = NULL
          WHERE tenant_id = 'legacy-intent'
          """);
      statement.execute("ALTER TABLE uacrf_advisory.dispatch_journal ENABLE TRIGGER protect_dispatch_journal");
      connection.commit();
    }

    assertTrue(journal.complete(intent.key(), new AdvisoryGateway.Unknown("legacy delivery uncertain")));
    assertFalse(journal.begin(intent));
    assertThrows(SQLException.class, () -> journal.find(intent.key()));
  }

  @Test
  void persistsResponseAndRejectsReplacement() throws Exception {
    var journal = new DispatchJournal(dataSource);
    var intent = intent("response", "attempt", "assignment");
    var response = new AdvisoryGateway.Response("retained response", 42, 17);
    assertTrue(registerRun(journal, intent.runKey(), intent.generation(), budget(), TEST_DEADLINE, intent));
    assertTrue(journal.begin(intent));
    assertTrue(journal.complete(intent.key(), response));
    assertEquals(response, new DispatchJournal(dataSource).find(intent.key()).orElseThrow()
        .outcome().orElseThrow());
    assertEquals(DispatchJournal.RecordingFence.ACTIVE,
        journal.find(intent.key()).orElseThrow().recordingFence().orElseThrow());
    assertFalse(journal.complete(intent.key(), new AdvisoryGateway.Unknown("replacement")));
    assertFalse(journal.begin(intent));
    assertTrue(journal.cancel(intent.runKey(), 1, 1, "reviewer", "stop"));
    assertTrue(journal.findRun(intent.runKey()).orElseThrow().cancelled());
    assertEquals(DispatchJournal.RecordingFence.ACTIVE,
        journal.find(intent.key()).orElseThrow().recordingFence().orElseThrow());

    try (Connection connection = dataSource.getConnection();
         var statement = connection.createStatement()) {
      assertThrows(SQLException.class, () -> statement.executeUpdate(
          "UPDATE uacrf_advisory.dispatch_journal SET response_text = 'changed' WHERE tenant_id = 'response'"));
      assertThrows(SQLException.class, () -> statement.executeUpdate(
          "DELETE FROM uacrf_advisory.dispatch_journal WHERE tenant_id = 'response'"));
    }
  }

  @Test
  void unknownOutcomeIsRetainedWithoutUsage() throws Exception {
    var journal = new DispatchJournal(dataSource);
    var intent = intent("unknown", "attempt", "assignment");
    assertTrue(registerRun(journal, intent.runKey(), intent.generation(), budget(), TEST_DEADLINE, intent));
    assertTrue(journal.begin(intent));
    assertTrue(journal.complete(intent.key(), new AdvisoryGateway.Unknown("delivery uncertain")));
    assertEquals(new AdvisoryGateway.Unknown("delivery uncertain"),
        journal.find(intent.key()).orElseThrow().outcome().orElseThrow());
    assertEquals(DispatchJournal.ReportedTokenStatus.UNAVAILABLE,
        journal.find(intent.key()).orElseThrow().reportedTokenStatus());
    assertEquals(DispatchJournal.RecordingFence.ACTIVE,
        journal.find(intent.key()).orElseThrow().recordingFence().orElseThrow());
    try (Connection connection = dataSource.getConnection();
         var statement = connection.createStatement();
         var result = statement.executeQuery(
             "SELECT input_tokens, output_tokens FROM uacrf_advisory.dispatch_journal WHERE tenant_id = 'unknown'")) {
      assertTrue(result.next());
      assertEquals(null, result.getObject(1));
      assertEquals(null, result.getObject(2));
    }
  }

  @Test
  void comparesReportedTokensWithSealedAllocationWithoutLosingOutcomes() throws Exception {
    var journal = new DispatchJournal(dataSource);
    var analyst = intent("token-report", "attempt-a", "assignment-a");
    var reviewer = intent("token-report", "attempt-b", "assignment-b", AdvisoryBudget.Role.REVIEWER,
        "reviewer", analyst.packet());
    assertTrue(registerRun(journal, analyst.runKey(), 1, budget(), TEST_DEADLINE, analyst, reviewer));
    assertTrue(journal.begin(analyst));
    assertTrue(journal.begin(reviewer));
    assertEquals(DispatchJournal.ReportedTokenStatus.UNAVAILABLE,
        journal.find(analyst.key()).orElseThrow().reportedTokenStatus());
    var exact = new AdvisoryGateway.Response("exact", 60, 40);
    var exceeded = new AdvisoryGateway.Response("over", Integer.MAX_VALUE, Integer.MAX_VALUE);
    assertTrue(journal.complete(analyst.key(), exact));
    assertTrue(journal.complete(reviewer.key(), exceeded));
    var reopened = new DispatchJournal(dataSource);
    var accepted = reopened.find(analyst.key()).orElseThrow();
    assertEquals(100, accepted.tokenAllocation());
    assertEquals(DispatchJournal.ReportedTokenStatus.WITHIN_ALLOCATION, accepted.reportedTokenStatus());
    assertEquals(exact, accepted.outcome().orElseThrow());
    var over = reopened.find(reviewer.key()).orElseThrow();
    assertEquals(200, over.tokenAllocation());
    assertEquals(DispatchJournal.ReportedTokenStatus.EXCEEDS_ALLOCATION, over.reportedTokenStatus());
    assertEquals(exceeded, over.outcome().orElseThrow());
    var usage = reopened.runUsage(analyst.runKey(), 1).orElseThrow();
    assertEquals(4_294_967_394L, usage.reportedTokens());
    assertEquals(2, usage.responses());
    assertEquals(DispatchJournal.RunUsageStatus.EXCEEDS_BUDGET, usage.status());
    assertFalse(reopened.complete(reviewer.key(), new AdvisoryGateway.Unknown("replace")));
  }

  @Test
  void listsUnresolvedAttemptsForRunWithoutTreatingThemAsRetryable() throws Exception {
    var journal = new DispatchJournal(dataSource);
    var analyst = intent("reconciliation", "attempt-a", "assignment-a");
    var reviewer = intent("reconciliation", "attempt-b", "assignment-b", AdvisoryBudget.Role.REVIEWER,
        "reviewer", analyst.packet());
    assertTrue(registerRun(journal, analyst.runKey(), 1, budget(), TEST_DEADLINE, analyst, reviewer));
    assertTrue(journal.begin(analyst));
    assertTrue(journal.begin(reviewer));
    assertTrue(journal.complete(reviewer.key(), new AdvisoryGateway.Unknown("delivery uncertain")));
    var reopened = new DispatchJournal(dataSource);
    var usageBeforeReconciliation = reopened.runUsage(analyst.runKey(), 1).orElseThrow();
    assertEquals(0, usageBeforeReconciliation.reportedTokens());
    assertEquals(1, usageBeforeReconciliation.unknownOutcomes());
    assertEquals(1, usageBeforeReconciliation.unresolvedIntents());
    assertEquals(DispatchJournal.RunUsageStatus.UNAVAILABLE, usageBeforeReconciliation.status());
    assertEquals(List.of(
        new DispatchJournal.Unresolved(analyst.key(), DispatchJournal.UnresolvedState.DISPATCH_INTENT),
        new DispatchJournal.Unresolved(reviewer.key(), DispatchJournal.UnresolvedState.UNKNOWN)),
        reopened.unresolved(analyst.runKey(), 1));
    assertTrue(reopened.unresolved(new DispatchJournal.RunKey("other", "env", "run"), 1).isEmpty());
    assertTrue(reopened.unresolved(new DispatchJournal.RunKey("reconciliation", "other", "run"), 1).isEmpty());
    assertTrue(reopened.unresolved(new DispatchJournal.RunKey("reconciliation", "env", "other"), 1).isEmpty());
    assertTrue(reopened.unresolved(analyst.runKey(), 2).isEmpty());
    assertThrows(IllegalArgumentException.class, () -> reopened.unresolved(analyst.runKey(), 0));
    assertTrue(journal.complete(analyst.key(), new AdvisoryGateway.Response("answer", 1, 1)));
    assertTrue(journal.cancel(analyst.runKey(), 1, 1, "reviewer", "stop"));
    var usageAfterReconciliation = reopened.runUsage(analyst.runKey(), 1).orElseThrow();
    assertEquals(2, usageAfterReconciliation.reportedTokens());
    assertEquals(0, usageAfterReconciliation.unresolvedIntents());
    assertEquals(1, usageAfterReconciliation.unknownOutcomes());
    assertEquals(DispatchJournal.RunUsageStatus.UNAVAILABLE, usageAfterReconciliation.status());
    assertEquals(List.of(new DispatchJournal.Unresolved(reviewer.key(), DispatchJournal.UnresolvedState.UNKNOWN)),
        reopened.unresolved(analyst.runKey(), 1));
    assertFalse(reopened.begin(reviewer));
  }

  @Test
  void rejectsRetainedPacketWhoseBytesNoLongerMatchItsDigest() throws Exception {
    var journal = new DispatchJournal(dataSource);
    var intent = intent("corrupt-packet", "attempt", "assignment");
    assertTrue(registerRun(journal, intent.runKey(), intent.generation(), budget(), TEST_DEADLINE, intent));
    assertTrue(journal.begin(intent));
    try (Connection connection = dataSource.getConnection();
         var statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      statement.execute("ALTER TABLE uacrf_advisory.dispatch_journal DISABLE TRIGGER protect_dispatch_journal");
      statement.executeUpdate("UPDATE uacrf_advisory.dispatch_journal SET packet_bytes = decode('ff', 'hex')"
          + " WHERE tenant_id = 'corrupt-packet'");
      statement.execute("ALTER TABLE uacrf_advisory.dispatch_journal ENABLE TRIGGER protect_dispatch_journal");
      connection.commit();
    }
    assertThrows(SQLException.class, () -> journal.find(intent.key()));
  }

  @Test
  void concurrentAttemptsForRoleHaveOneWinner() throws Exception {
    var journal = new DispatchJournal(dataSource);
    var planned = intent("race", "attempt-0", "assignment-0");
    assertTrue(registerRun(journal, planned.runKey(), 1, budget(), TEST_DEADLINE, planned));
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(8)) {
      List<Future<Boolean>> results = new ArrayList<>();
      for (int i = 0; i < 8; i++) {
        var candidate = intent("race", "attempt-" + i, "assignment-" + i);
        results.add(executor.submit(() -> {
          start.await();
          return journal.begin(candidate);
        }));
      }
      start.countDown();
      int winners = 0;
      for (var result : results) {
        if (result.get()) {
          winners++;
        }
      }
      assertEquals(1, winners);
    }
  }

  @Test
  void admissionRequiresAnUnusedAllocatedRoleAndMatchingProfile() throws Exception {
    var journal = new DispatchJournal(dataSource);
    var analyst = intent("allocation", "analyst-attempt", "analyst-assignment");
    var reviewer = intent("allocation", "reviewer-attempt", "reviewer-assignment",
        AdvisoryBudget.Role.REVIEWER, "reviewer", analyst.packet());
    assertTrue(registerRun(journal, analyst.runKey(), 1, budget(), TEST_DEADLINE, analyst, reviewer));
    var wrongProfile = intent("allocation", "wrong-profile", "wrong-profile-assignment",
        AdvisoryBudget.Role.ANALYST, "reviewer", analyst.packet());
    var unallocated = intent("allocation", "critic-attempt", "critic-assignment",
        AdvisoryBudget.Role.CRITIC, "critic", analyst.packet());
    var oversizedOutput = intent("allocation", "oversized-output", "oversized-assignment",
        AdvisoryBudget.Role.ANALYST, "analyst", analyst.packet(), 101);
    assertFalse(journal.begin(wrongProfile));
    assertFalse(journal.begin(unallocated));
    assertFalse(journal.begin(oversizedOutput));
    assertTrue(journal.begin(analyst));
    assertFalse(journal.begin(intent("allocation", "analyst-retry", "different-assignment")));
    assertTrue(journal.begin(reviewer));
    try (Connection connection = dataSource.getConnection();
         var statement = connection.createStatement();
         var result = statement.executeQuery("""
             SELECT sum(tokens), sum(cost_units), count(*)
             FROM uacrf_advisory.run_allocation WHERE tenant_id = 'allocation'
             """)) {
      assertTrue(result.next());
      assertEquals(300, result.getLong(1));
      assertEquals(10, result.getLong(2));
      assertEquals(2, result.getLong(3));
    }
    try (Connection connection = dataSource.getConnection();
         var statement = connection.createStatement()) {
      assertThrows(SQLException.class, () -> statement.executeUpdate("""
          INSERT INTO uacrf_advisory.run_allocation
          VALUES ('allocation', 'env', 'run', 1, 'CRITIC', 'critic', 1, 0)
          """));
      assertThrows(SQLException.class, () -> statement.executeUpdate("""
          UPDATE uacrf_advisory.run_allocation SET tokens = 1 WHERE tenant_id = 'allocation'
          """));
    }
  }

  @Test
  void admissionRejectsRequestTimeoutBeyondRemainingRunDeadline() throws Exception {
    var journal = new DispatchJournal(dataSource);
    Instant deadline;
    try (Connection connection = dataSource.getConnection();
         var statement = connection.createStatement();
         var result = statement.executeQuery("SELECT clock_timestamp() + interval '10 seconds'")) {
      assertTrue(result.next());
      deadline = result.getObject(1, OffsetDateTime.class).toInstant();
    }
    var intent = intent("timeout-deadline", "attempt", "assignment", AdvisoryBudget.Role.ANALYST,
        "analyst", new InputPacket("bounded test packet".getBytes(StandardCharsets.UTF_8)),
        100, Duration.ofSeconds(30), deadline);
    assertTrue(registerRun(journal, intent.runKey(), intent.generation(), budget(), deadline, intent));
    assertFalse(journal.begin(intent));
    assertTrue(journal.find(intent.key()).isEmpty());
  }

  @Test
  void admissionCannotExtendThePreparedRunDeadline() throws Exception {
    var journal = new DispatchJournal(dataSource);
    Instant preparedDeadline = Instant.now().plusSeconds(120);
    var intent = intent("deadline-binding", "attempt", "assignment", AdvisoryBudget.Role.ANALYST,
        "analyst", new InputPacket("bounded test packet".getBytes(StandardCharsets.UTF_8)),
        100, Duration.ofSeconds(30), preparedDeadline);
    assertTrue(registerRun(journal, intent.runKey(), intent.generation(), budget(), TEST_DEADLINE));
    assertFalse(journal.begin(intent));
    assertTrue(journal.find(intent.key()).isEmpty());
  }

  @Test
  void admissionReservesOutputAgainstReportedUsageAndStopsAfterUnknownOutcome() throws Exception {
    var journal = new DispatchJournal(dataSource);
    var analyst = intent("aggregate-budget", "analyst-attempt", "analyst-assignment");
    var reviewer = intent("aggregate-budget", "reviewer-attempt", "reviewer-assignment",
        AdvisoryBudget.Role.REVIEWER, "reviewer", analyst.packet(), 200);
    assertTrue(registerRun(journal, analyst.runKey(), 1, budget(), TEST_DEADLINE, analyst, reviewer));
    assertTrue(journal.begin(analyst));
    assertTrue(journal.complete(analyst.key(), new AdvisoryGateway.Response("answer", 250, 10)));
    assertFalse(journal.begin(reviewer));
    assertTrue(journal.find(reviewer.key()).isEmpty());

    var uncertainAnalyst = intent("unknown-blocks-admission", "analyst-attempt", "analyst-assignment");
    var uncertainReviewer = intent("unknown-blocks-admission", "reviewer-attempt", "reviewer-assignment",
        AdvisoryBudget.Role.REVIEWER, "reviewer", uncertainAnalyst.packet());
    assertTrue(registerRun(journal, uncertainAnalyst.runKey(), 1, budget(), TEST_DEADLINE,
        uncertainAnalyst, uncertainReviewer));
    assertTrue(journal.begin(uncertainAnalyst));
    assertTrue(journal.complete(uncertainAnalyst.key(), new AdvisoryGateway.Unknown("delivery uncertain")));
    assertFalse(journal.begin(uncertainReviewer));
    assertTrue(journal.find(uncertainReviewer.key()).isEmpty());
  }

  @Test
  void registrationRollsBackRunWhenAllocationInsertFails() throws Exception {
    var journal = new DispatchJournal(dataSource);
    var run = intent("registration-failure", "attempt", "assignment").runKey();
    var failedIntent = intent("registration-failure", "attempt", "assignment");
    try (Connection connection = dataSource.getConnection();
         var statement = connection.createStatement()) {
      statement.execute("""
          ALTER TABLE uacrf_advisory.run_allocation
          ADD CONSTRAINT test_forced_allocation_failure CHECK (tenant_id <> 'registration-failure')
          """);
      try {
        assertThrows(SQLException.class,
            () -> registerRun(journal, run, 1, budget(), TEST_DEADLINE, failedIntent));
      } finally {
        statement.execute("ALTER TABLE uacrf_advisory.run_allocation DROP CONSTRAINT test_forced_allocation_failure");
      }
    }
    assertTrue(journal.findRun(run).isEmpty());
  }

  @Test
  void databaseRejectsActivationWhenAllocationsExceedBudget() throws Exception {
    try (Connection connection = dataSource.getConnection();
         var statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      for (String failure : List.of("tokens", "cost", "calls")) {
        statement.executeUpdate("""
            INSERT INTO uacrf_advisory.run_fence
            (tenant_id, environment_id, run_id, generation, max_calls, max_tokens, max_cost_units,
             deadline_epoch_second, deadline_nano, state, revision)
            VALUES ('over-budget', 'env', 'run', 1, 1, %d, 0, 4070908800, 0, 'DRAFT', 1)
            """.formatted(failure.equals("calls") ? 2 : 1));
        int tokens = failure.equals("tokens") ? 2 : 1;
        int cost = failure.equals("cost") ? 1 : 0;
        statement.executeUpdate("""
            INSERT INTO uacrf_advisory.run_allocation
            VALUES ('over-budget', 'env', 'run', 1, 'ANALYST', 'analyst', %d, %d)
            """.formatted(tokens, cost));
        if (failure.equals("calls")) {
          statement.executeUpdate("""
              INSERT INTO uacrf_advisory.run_allocation
              VALUES ('over-budget', 'env', 'run', 1, 'REVIEWER', 'reviewer', 1, 0)
              """);
        }
        String packetDigest = "a".repeat(64);
        statement.executeUpdate("""
            INSERT INTO uacrf_advisory.run_dispatch_plan
              (tenant_id, environment_id, run_id, generation, assignment_id, attempt_id, role, profile_id,
               model_ref, max_output_tokens, timeout_seconds, timeout_nanos, context_digest, packet_bytes,
               packet_bytes_digest)
            VALUES ('over-budget', 'env', 'run', 1, 'assignment-analyst', 'attempt-analyst',
              'ANALYST', 'analyst', 'model-a', 1, 30, 0, '%s', decode('00', 'hex'), '%s')
            """.formatted(packetDigest, packetDigest));
        if (failure.equals("calls")) {
          statement.executeUpdate("""
              INSERT INTO uacrf_advisory.run_dispatch_plan
                (tenant_id, environment_id, run_id, generation, assignment_id, attempt_id, role, profile_id,
                 model_ref, max_output_tokens, timeout_seconds, timeout_nanos, context_digest, packet_bytes,
                 packet_bytes_digest)
              VALUES ('over-budget', 'env', 'run', 1, 'assignment-reviewer', 'attempt-reviewer',
                'REVIEWER', 'reviewer', 'model-a', 1, 30, 0, '%s', decode('00', 'hex'), '%s')
              """.formatted(packetDigest, packetDigest));
        }
        assertThrows(SQLException.class, () -> statement.executeUpdate("""
            UPDATE uacrf_advisory.run_fence SET state = 'ACTIVE' WHERE tenant_id = 'over-budget'
            """));
        connection.rollback();
      }
    }
  }

  @Test
  void databaseRejectsNewRunsThatDisablePlanBinding() throws Exception {
    try (Connection connection = dataSource.getConnection();
         var statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      assertThrows(SQLException.class, () -> statement.executeUpdate("""
          INSERT INTO uacrf_advisory.run_fence
            (tenant_id, environment_id, run_id, generation, max_calls, max_tokens, max_cost_units,
             deadline_epoch_second, deadline_nano, dispatch_plan_required, state, revision)
          VALUES ('unplanned-new-run', 'env', 'run-disabled', 1, 1, 1, 0, 4070908800, 0, false, 'DRAFT', 1)
          """));
      connection.rollback();
      statement.executeUpdate("""
          INSERT INTO uacrf_advisory.run_fence
            (tenant_id, environment_id, run_id, generation, max_calls, max_tokens, max_cost_units,
             deadline_epoch_second, deadline_nano, state, revision)
          VALUES ('unplanned-new-run', 'env', 'run-default', 1, 1, 1, 0, 4070908800, 0, 'DRAFT', 1)
          """);
      try (var result = statement.executeQuery("""
          SELECT dispatch_plan_required FROM uacrf_advisory.run_fence
          WHERE tenant_id = 'unplanned-new-run' AND run_id = 'run-default'
          """)) {
        assertTrue(result.next());
        assertTrue(result.getBoolean(1));
      }
      connection.rollback();
    }
  }

  @Test
  void legacyPlanlessRunCannotStartNewDispatches() throws Exception {
    var journal = new DispatchJournal(dataSource);
    var intent = intent("legacy-planless", "attempt", "assignment");
    try (Connection connection = dataSource.getConnection();
         var statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      statement.execute("ALTER TABLE uacrf_advisory.run_fence DISABLE TRIGGER require_dispatch_plan_for_new_run");
      statement.executeUpdate("""
          INSERT INTO uacrf_advisory.run_fence
            (tenant_id, environment_id, run_id, generation, max_calls, max_tokens, max_cost_units,
             deadline_epoch_second, deadline_nano, dispatch_plan_required, state, revision)
          VALUES ('legacy-planless', 'env', 'run', 1, 2, 300, 10, 4070908800, 0, false, 'DRAFT', 1)
          """);
      statement.executeUpdate("""
          INSERT INTO uacrf_advisory.run_allocation
          VALUES ('legacy-planless', 'env', 'run', 1, 'ANALYST', 'analyst', 100, 4)
          """);
      statement.executeUpdate("""
          UPDATE uacrf_advisory.run_fence SET state = 'ACTIVE'
          WHERE tenant_id = 'legacy-planless' AND run_id = 'run'
          """);
      statement.execute("ALTER TABLE uacrf_advisory.run_fence ENABLE TRIGGER require_dispatch_plan_for_new_run");
      connection.commit();
    }

    assertFalse(journal.begin(intent));
    assertTrue(journal.find(intent.key()).isEmpty());
  }

  @Test
  void cancellationRejectsNewIntentsButRetainsLateResults() throws Exception {
    var journal = new DispatchJournal(dataSource);
    var intent = intent("cancel", "attempt", "assignment");
    assertFalse(journal.begin(intent));
    assertTrue(registerRun(journal, intent.runKey(), 1, budget(), TEST_DEADLINE, intent));
    assertFalse(registerRun(journal, intent.runKey(), 2, budget(), TEST_DEADLINE));
    var stale = new DispatchJournal.Intent(intent.key(), intent.runId(), intent.assignmentId(), 2,
        intent.runDeadline(), intent.role(), intent.profileId(), intent.request(),
        intent.contextDigest(), intent.packet());
    assertFalse(journal.begin(stale));
    assertTrue(journal.begin(intent));
    assertFalse(journal.cancel(intent.runKey(), 2, 1, "reviewer", "stale generation"));
    assertFalse(journal.cancel(intent.runKey(), 1, 2, "reviewer", "stale revision"));
    assertThrows(IllegalArgumentException.class,
        () -> journal.cancel(intent.runKey(), 1, 1, "reviewer", "  "));
    assertTrue(journal.cancel(intent.runKey(), 1, 1, "reviewer", "stop"));
    assertFalse(journal.cancel(intent.runKey(), 1, 1, "reviewer", "replay"));
    assertFalse(registerRun(journal, intent.runKey(), 1, budget(), TEST_DEADLINE, intent));
    var reviewer = intent("cancel", "reviewer-attempt", "reviewer-assignment",
        AdvisoryBudget.Role.REVIEWER, "reviewer", intent.packet());
    assertFalse(journal.begin(reviewer));

    var late = new AdvisoryGateway.Response("late response", 3, 5);
    assertTrue(journal.complete(intent.key(), late));
    assertEquals(late, journal.find(intent.key()).orElseThrow().outcome().orElseThrow());
    assertEquals(DispatchJournal.RecordingFence.CANCELLED,
        journal.find(intent.key()).orElseThrow().recordingFence().orElseThrow());
    assertEquals(new DispatchJournal.RunSnapshot(1, 2, TEST_DEADLINE, true, "reviewer", "stop"),
        journal.findRun(intent.runKey()).orElseThrow());
    assertTrue(journal.findRun(new DispatchJournal.RunKey("other", "env", "run")).isEmpty());
    try (Connection connection = dataSource.getConnection();
         var statement = connection.createStatement()) {
      assertThrows(SQLException.class, () -> statement.executeUpdate(
          "DELETE FROM uacrf_advisory.run_fence WHERE tenant_id = 'cancel'"));
      assertThrows(SQLException.class, () -> statement.executeUpdate(
          "UPDATE uacrf_advisory.run_fence SET cancellation_reason = 'changed' WHERE tenant_id = 'cancel'"));
    }
  }

  @Test
  void beginWaitsForConcurrentCancellationAndThenFailsClosed() throws Exception {
    var journal = new DispatchJournal(dataSource);
    var intent = intent("cancel-race", "attempt", "assignment");
    assertTrue(registerRun(journal, intent.runKey(), 1, budget(), TEST_DEADLINE, intent));
    try (Connection connection = dataSource.getConnection();
         var statement = connection.createStatement();
         var executor = Executors.newSingleThreadExecutor()) {
      connection.setAutoCommit(false);
      statement.executeUpdate("""
          UPDATE uacrf_advisory.run_fence
          SET state='CANCELLED', revision=2, cancelled_by='reviewer',
              cancellation_reason='stop', cancelled_at=clock_timestamp()
          WHERE tenant_id='cancel-race'
          """);
      var started = new CountDownLatch(1);
      Future<Boolean> begin = executor.submit(() -> {
        started.countDown();
        return journal.begin(intent);
      });
      started.await();
      try {
        assertThrows(TimeoutException.class, () -> begin.get(200, TimeUnit.MILLISECONDS));
      } finally {
        connection.commit();
      }
      assertFalse(begin.get(5, TimeUnit.SECONDS));
      assertTrue(journal.find(intent.key()).isEmpty());
    }
  }

  @Test
  void completionWaitsForCancellationAndRecordsCancelledFence() throws Exception {
    var journal = new DispatchJournal(dataSource);
    var intent = intent("completion-cancel-race", "attempt", "assignment");
    assertTrue(registerRun(journal, intent.runKey(), 1, budget(), TEST_DEADLINE, intent));
    assertTrue(journal.begin(intent));
    try (Connection connection = dataSource.getConnection();
         var statement = connection.createStatement();
         var executor = Executors.newSingleThreadExecutor()) {
      connection.setAutoCommit(false);
      statement.executeUpdate("""
          UPDATE uacrf_advisory.run_fence
          SET state='CANCELLED', revision=2, cancelled_by='reviewer',
              cancellation_reason='stop', cancelled_at=clock_timestamp()
          WHERE tenant_id='completion-cancel-race'
          """);
      var started = new CountDownLatch(1);
      Future<Boolean> completion = executor.submit(() -> {
        started.countDown();
        return journal.complete(intent.key(), new AdvisoryGateway.Unknown("uncertain"));
      });
      started.await();
      try {
        assertThrows(TimeoutException.class, () -> completion.get(200, TimeUnit.MILLISECONDS));
      } finally {
        connection.commit();
      }
      assertTrue(completion.get(5, TimeUnit.SECONDS));
      assertEquals(DispatchJournal.RecordingFence.CANCELLED,
          journal.find(intent.key()).orElseThrow().recordingFence().orElseThrow());
    }
  }

  @Test
  void concurrentCompletionsRetainExactlyOneOutcome() throws Exception {
    var journal = new DispatchJournal(dataSource);
    var intent = intent("completion-race", "attempt", "assignment");
    assertTrue(registerRun(journal, intent.runKey(), 1, budget(), TEST_DEADLINE, intent));
    assertTrue(journal.begin(intent));
    var response = new AdvisoryGateway.Response("response", 1, 1);
    var unknown = new AdvisoryGateway.Unknown("uncertain");
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      Future<Boolean> first = executor.submit(() -> {
        start.await();
        return journal.complete(intent.key(), response);
      });
      Future<Boolean> second = executor.submit(() -> {
        start.await();
        return journal.complete(intent.key(), unknown);
      });
      start.countDown();
      boolean firstWon = first.get(5, TimeUnit.SECONDS);
      boolean secondWon = second.get(5, TimeUnit.SECONDS);
      assertTrue(firstWon ^ secondWon);
      var retained = journal.find(intent.key()).orElseThrow();
      assertEquals(firstWon ? response : unknown, retained.outcome().orElseThrow());
      assertEquals(DispatchJournal.RecordingFence.ACTIVE, retained.recordingFence().orElseThrow());
    }
  }

  @Test
  void rejectsExpiredRunRegistration() throws Exception {
    var journal = new DispatchJournal(dataSource);
    var run = intent("expired-registration", "attempt", "assignment").runKey();
    assertThrows(SQLException.class, () -> registerRun(journal, run, 1, budget(), Instant.EPOCH));
    assertTrue(journal.findRun(run).isEmpty());
  }

  @Test
  void deadlineIsRecheckedAfterWaitingForRunLock() throws Exception {
    var journal = new DispatchJournal(dataSource);
    var packet = new InputPacket("bounded test packet".getBytes(StandardCharsets.UTF_8));
    try (Connection connection = dataSource.getConnection();
         var statement = connection.createStatement();
         var executor = Executors.newSingleThreadExecutor()) {
      Instant deadline;
      try (var result = statement.executeQuery("SELECT clock_timestamp() + interval '5 seconds'")) {
        assertTrue(result.next());
        deadline = result.getObject(1, OffsetDateTime.class).toInstant();
      }
      var analyst = intent("deadline-race", "analyst-attempt", "analyst-assignment",
          AdvisoryBudget.Role.ANALYST, "analyst", packet, 100, Duration.ofSeconds(1), deadline);
      var reviewer = intent("deadline-race", "reviewer-attempt", "reviewer-assignment",
          AdvisoryBudget.Role.REVIEWER, "reviewer", analyst.packet(), 100, Duration.ofSeconds(30), deadline);
      assertTrue(registerRun(journal, analyst.runKey(), 1, budget(), deadline, analyst, reviewer));
      assertTrue(journal.begin(analyst));
      connection.setAutoCommit(false);
      statement.executeQuery("SELECT 1 FROM uacrf_advisory.run_fence WHERE tenant_id='deadline-race' FOR UPDATE").close();
      var started = new CountDownLatch(1);
      Future<Boolean> begin = executor.submit(() -> {
        started.countDown();
        return journal.begin(reviewer);
      });
      started.await();
      try {
        assertThrows(TimeoutException.class, () -> begin.get(200, TimeUnit.MILLISECONDS));
        statement.executeQuery("SELECT pg_sleep(5.2)").close();
      } finally {
        connection.commit();
      }
      assertFalse(begin.get(5, TimeUnit.SECONDS));
      assertTrue(journal.find(reviewer.key()).isEmpty());
      var late = new AdvisoryGateway.Response("late", 1, 1);
      assertTrue(journal.complete(analyst.key(), late));
      assertEquals(late, journal.find(analyst.key()).orElseThrow().outcome().orElseThrow());
      assertEquals(DispatchJournal.RecordingFence.EXPIRED,
          journal.find(analyst.key()).orElseThrow().recordingFence().orElseThrow());
    }
  }

  @Test
  void rejectsUncommittedConnections() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      var transactional = new SingleConnectionDataSource(connection, true);
      var journal = new DispatchJournal(transactional);
      var intent = intent("transaction", "attempt", "assignment");
      assertThrows(SQLException.class, () -> journal.begin(intent));
      assertTrue(new DispatchJournal(dataSource).find(intent.key()).isEmpty());
      connection.rollback();
    }
  }
}
