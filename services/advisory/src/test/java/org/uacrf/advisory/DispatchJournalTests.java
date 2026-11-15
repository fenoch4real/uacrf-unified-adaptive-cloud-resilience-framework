package org.uacrf.advisory;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
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
  static void migrate() {
    PGSimpleDataSource postgres = new PGSimpleDataSource();
    postgres.setURL(POSTGRES.getJdbcUrl());
    postgres.setUser(POSTGRES.getUsername());
    postgres.setPassword(POSTGRES.getPassword());
    dataSource = postgres;
    Flyway.configure().dataSource(dataSource).load().migrate();
  }

  private static DispatchJournal.Intent intent(String tenant, String attempt, String assignment) {
    return new DispatchJournal.Intent(new DispatchJournal.Key(tenant, "env", attempt),
        "run", assignment, 1, AdvisoryBudget.Role.ANALYST, "analyst", "a".repeat(64),
        new InputPacket("bounded test packet".getBytes(StandardCharsets.UTF_8)));
  }

  private static AdvisoryBudget budget() {
    return new AdvisoryBudget(2, 300, 10, List.of(
        new AdvisoryBudget.Allocation(AdvisoryBudget.Role.ANALYST, "analyst", 100, 4),
        new AdvisoryBudget.Allocation(AdvisoryBudget.Role.REVIEWER, "reviewer", 200, 6)));
  }

  @Test
  void committedIntentSurvivesReopeningAndNeverAuthorizesDuplicate() throws Exception {
    var journal = new DispatchJournal(dataSource);
    var intent = intent("intent", "attempt", "assignment");
    assertTrue(journal.registerRun(intent.runKey(), intent.generation(), budget(), TEST_DEADLINE));
    assertTrue(journal.begin(intent));

    var reopened = new DispatchJournal(dataSource);
    var retained = reopened.find(intent.key()).orElseThrow();
    assertEquals(intent.key(), retained.intent().key());
    assertEquals(intent.role(), retained.intent().role());
    assertEquals(intent.profileId(), retained.intent().profileId());
    assertEquals(intent.contextDigest(), retained.intent().contextDigest());
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
          "UPDATE uacrf_advisory.dispatch_journal SET packet_bytes = decode('ff', 'hex')"
              + " WHERE tenant_id = 'intent'"));
      assertThrows(SQLException.class, () -> statement.executeUpdate(
          "DELETE FROM uacrf_advisory.dispatch_journal WHERE tenant_id = 'intent'"));
    }
  }

  @Test
  void persistsResponseAndRejectsReplacement() throws Exception {
    var journal = new DispatchJournal(dataSource);
    var intent = intent("response", "attempt", "assignment");
    var response = new AdvisoryGateway.Response("retained response", 42, 17);
    assertTrue(journal.registerRun(intent.runKey(), intent.generation(), budget(), TEST_DEADLINE));
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
    assertTrue(journal.registerRun(intent.runKey(), intent.generation(), budget(), TEST_DEADLINE));
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
    var reviewer = new DispatchJournal.Intent(new DispatchJournal.Key("token-report", "env", "attempt-b"),
        "run", "assignment-b", 1, AdvisoryBudget.Role.REVIEWER, "reviewer",
        analyst.contextDigest(), analyst.packet());
    assertTrue(journal.registerRun(analyst.runKey(), 1, budget(), TEST_DEADLINE));
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
    assertFalse(reopened.complete(reviewer.key(), new AdvisoryGateway.Unknown("replace")));
  }

  @Test
  void listsUnresolvedAttemptsForRunWithoutTreatingThemAsRetryable() throws Exception {
    var journal = new DispatchJournal(dataSource);
    var analyst = intent("reconciliation", "attempt-a", "assignment-a");
    var reviewer = new DispatchJournal.Intent(new DispatchJournal.Key("reconciliation", "env", "attempt-b"),
        "run", "assignment-b", 1, AdvisoryBudget.Role.REVIEWER, "reviewer",
        analyst.contextDigest(), analyst.packet());
    assertTrue(journal.registerRun(analyst.runKey(), 1, budget(), TEST_DEADLINE));
    assertTrue(journal.begin(analyst));
    assertTrue(journal.begin(reviewer));
    assertTrue(journal.complete(reviewer.key(), new AdvisoryGateway.Unknown("delivery uncertain")));
    var reopened = new DispatchJournal(dataSource);
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
    assertEquals(List.of(new DispatchJournal.Unresolved(reviewer.key(), DispatchJournal.UnresolvedState.UNKNOWN)),
        reopened.unresolved(analyst.runKey(), 1));
    assertFalse(reopened.begin(reviewer));
  }

  @Test
  void rejectsRetainedPacketWhoseBytesNoLongerMatchItsDigest() throws Exception {
    var journal = new DispatchJournal(dataSource);
    var intent = intent("corrupt-packet", "attempt", "assignment");
    assertTrue(journal.registerRun(intent.runKey(), intent.generation(), budget(), TEST_DEADLINE));
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
    assertTrue(journal.registerRun(intent("race", "attempt", "assignment").runKey(), 1, budget(), TEST_DEADLINE));
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
    assertTrue(journal.registerRun(analyst.runKey(), 1, budget(), TEST_DEADLINE));
    var wrongProfile = new DispatchJournal.Intent(new DispatchJournal.Key("allocation", "env", "wrong-profile"),
        "run", "wrong-profile-assignment", 1, AdvisoryBudget.Role.ANALYST, "reviewer",
        analyst.contextDigest(), analyst.packet());
    var unallocated = new DispatchJournal.Intent(new DispatchJournal.Key("allocation", "env", "critic-attempt"),
        "run", "critic-assignment", 1, AdvisoryBudget.Role.CRITIC, "critic",
        analyst.contextDigest(), analyst.packet());
    var reviewer = new DispatchJournal.Intent(new DispatchJournal.Key("allocation", "env", "reviewer-attempt"),
        "run", "reviewer-assignment", 1, AdvisoryBudget.Role.REVIEWER, "reviewer",
        analyst.contextDigest(), analyst.packet());
    assertFalse(journal.begin(wrongProfile));
    assertFalse(journal.begin(unallocated));
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
  void registrationRollsBackRunWhenAllocationInsertFails() throws Exception {
    var journal = new DispatchJournal(dataSource);
    var run = intent("registration-failure", "attempt", "assignment").runKey();
    try (Connection connection = dataSource.getConnection();
         var statement = connection.createStatement()) {
      statement.execute("""
          ALTER TABLE uacrf_advisory.run_allocation
          ADD CONSTRAINT test_forced_allocation_failure CHECK (tenant_id <> 'registration-failure')
          """);
      try {
        assertThrows(SQLException.class, () -> journal.registerRun(run, 1, budget(), TEST_DEADLINE));
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
        assertThrows(SQLException.class, () -> statement.executeUpdate("""
            UPDATE uacrf_advisory.run_fence SET state = 'ACTIVE' WHERE tenant_id = 'over-budget'
            """));
        connection.rollback();
      }
    }
  }

  @Test
  void cancellationRejectsNewIntentsButRetainsLateResults() throws Exception {
    var journal = new DispatchJournal(dataSource);
    var intent = intent("cancel", "attempt", "assignment");
    assertFalse(journal.begin(intent));
    assertTrue(journal.registerRun(intent.runKey(), 1, budget(), TEST_DEADLINE));
    assertFalse(journal.registerRun(intent.runKey(), 2, budget(), TEST_DEADLINE));
    var stale = new DispatchJournal.Intent(intent.key(), intent.runId(), intent.assignmentId(), 2,
        intent.role(), intent.profileId(), intent.contextDigest(), intent.packet());
    assertFalse(journal.begin(stale));
    assertTrue(journal.begin(intent));
    assertFalse(journal.cancel(intent.runKey(), 2, 1, "reviewer", "stale generation"));
    assertFalse(journal.cancel(intent.runKey(), 1, 2, "reviewer", "stale revision"));
    assertThrows(IllegalArgumentException.class,
        () -> journal.cancel(intent.runKey(), 1, 1, "reviewer", "  "));
    assertTrue(journal.cancel(intent.runKey(), 1, 1, "reviewer", "stop"));
    assertFalse(journal.cancel(intent.runKey(), 1, 1, "reviewer", "replay"));
    assertFalse(journal.registerRun(intent.runKey(), 1, budget(), TEST_DEADLINE));
    var reviewer = new DispatchJournal.Intent(new DispatchJournal.Key("cancel", "env", "reviewer-attempt"),
        "run", "reviewer-assignment", 1, AdvisoryBudget.Role.REVIEWER, "reviewer",
        intent.contextDigest(), intent.packet());
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
    assertTrue(journal.registerRun(intent.runKey(), 1, budget(), TEST_DEADLINE));
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
    assertTrue(journal.registerRun(intent.runKey(), 1, budget(), TEST_DEADLINE));
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
    assertTrue(journal.registerRun(intent.runKey(), 1, budget(), TEST_DEADLINE));
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
    assertThrows(SQLException.class, () -> journal.registerRun(run, 1, budget(), Instant.EPOCH));
    assertTrue(journal.findRun(run).isEmpty());
  }

  @Test
  void deadlineIsRecheckedAfterWaitingForRunLock() throws Exception {
    var journal = new DispatchJournal(dataSource);
    var analyst = intent("deadline-race", "analyst-attempt", "analyst-assignment");
    try (Connection connection = dataSource.getConnection();
         var statement = connection.createStatement();
         var executor = Executors.newSingleThreadExecutor()) {
      Instant deadline;
      try (var result = statement.executeQuery("SELECT clock_timestamp() + interval '2 seconds'")) {
        assertTrue(result.next());
        deadline = result.getObject(1, OffsetDateTime.class).toInstant();
      }
      assertTrue(journal.registerRun(analyst.runKey(), 1, budget(), deadline));
      assertTrue(journal.begin(analyst));
      var reviewer = new DispatchJournal.Intent(new DispatchJournal.Key("deadline-race", "env", "reviewer-attempt"),
          "run", "reviewer-assignment", 1, AdvisoryBudget.Role.REVIEWER, "reviewer",
          analyst.contextDigest(), analyst.packet());
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
        statement.executeQuery("SELECT pg_sleep(2.2)").close();
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
