package org.uacrf.connections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.uacrf.connections.CollectionCheckpointStore.LeasedSource;
import org.uacrf.connections.ProviderCollector.Batch;
import org.uacrf.connections.ProviderCollector.Observation;

class CollectionRunnerTests {
  @Test
  void rejectsBlankClientSecretWhenTheRunnerIsEnabled() {
    assertThatThrownBy(
            () ->
                new CollectionRunner(
                    mock(CollectionCheckpointStore.class),
                    List.of(),
                    "http://localhost/token",
                    "collector-client",
                    "  ",
                    "http://localhost/delegations",
                    "http://localhost/observations",
                    60000,
                    300000,
                    10,
                    100))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Collection runner client secret must be configured when enabled.");
  }

  @Test
  void rejectsBlankClientIdWhenTheRunnerIsEnabled() {
    assertThatThrownBy(
            () ->
                new CollectionRunner(
                    mock(CollectionCheckpointStore.class),
                    List.of(),
                    "http://localhost/token",
                    " ",
                    "collector-secret",
                    "http://localhost/delegations",
                    "http://localhost/observations",
                    60000,
                    300000,
                    10,
                    100))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Collection runner client ID must be configured when enabled.");
  }

  @Test
  void rejectsRelativeIdentityEndpointWhenTheRunnerIsEnabled() {
    assertThatThrownBy(
            () ->
                new CollectionRunner(
                    mock(CollectionCheckpointStore.class),
                    List.of(),
                    "token",
                    "collector-client",
                    "collector-secret",
                    "http://localhost/delegations",
                    "http://localhost/observations",
                    60000,
                    300000,
                    10,
                    100))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Collection runner token URL must be an absolute HTTP(S) URL.");
  }

  @Test
  void submitsCollectedObservationsUsingANarrowS01Delegation() throws Exception {
    List<String> calls = new CopyOnWriteArrayList<>();
    HttpServer server = startServer(calls);
    try {
      String baseUrl = "http://localhost:" + server.getAddress().getPort();
      CollectionCheckpointStore checkpoints = mock(CollectionCheckpointStore.class);
      LeasedSource source = source();
      when(checkpoints.claimDue(1, Duration.ofMillis(2000))).thenReturn(List.of(source));
      ProviderCollector collector =
          new ProviderCollector() {
            @Override
            public ConnectionsRequests.Provider provider() {
              return ConnectionsRequests.Provider.AWS;
            }

            @Override
            public Batch collect(Request request) {
              assertThat(request.readOnlyIdentityRef()).isEqualTo("identity-ref");
              assertThat(request.readOnlyExternalId()).isEqualTo("uacrf-external-id");
              assertThat(request.lastSequence()).isEqualTo(7);
              assertThat(request.checkpoint()).isEqualTo("previous-page");
              return new Batch(
                  Batch.Status.COLLECTED,
                  List.of(
                      new Observation(
                          "obs-1",
                          8,
                          Instant.parse("2026-11-17T17:00:00Z"),
                          "aws.config.resources",
                          "{\"resource\":\"fixture\"}")),
                  "next-page",
                  null);
            }
          };
      CollectionRunner runner =
          runner(checkpoints, collector, baseUrl, 1, 2000, 10);

      assertThat(runner.runDue()).isEqualTo(1);

      verify(checkpoints, atLeastOnce())
          .renew(eq(source), eq(Duration.ofMillis(2000)));
      assertThat(calls)
          .anyMatch(
              call ->
                  call.startsWith("delegation:Bearer identity-token")
                      && call.contains("\"audience\":\"S03\"")
                      && call.contains("connections.observation.write"));
      assertThat(calls)
          .anyMatch(
              call ->
                  call.startsWith("observation:Bearer delegation-token")
                      && call.contains("\"source_ref\":\"source-1\"")
                      && call.contains("\"sequence\":8")
                      && call.contains("aws.config.resources"));
      verify(checkpoints).storePending(eq(source), any(), eq("next-page"), any());
      verify(checkpoints)
          .complete(
              eq(source),
              eq("COLLECTED"),
              eq("Batch submitted to observation intake."),
              eq("next-page"),
              any(),
              any(),
              eq(true));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void recordsUnimplementedCollectorWithoutAdvancingItsCheckpoint() {
    CollectionCheckpointStore checkpoints = mock(CollectionCheckpointStore.class);
    LeasedSource source = source();
    when(checkpoints.claimDue(1, Duration.ofMillis(2000))).thenReturn(List.of(source));
    ProviderCollector collector =
        new ProviderCollector() {
          @Override
          public ConnectionsRequests.Provider provider() {
            return ConnectionsRequests.Provider.AWS;
          }

          @Override
          public Batch collect(Request request) {
            return Batch.notImplemented("Not ready");
          }
        };
    CollectionRunner runner =
        runner(
            checkpoints,
            collector,
            "http://127.0.0.1:1",
            1,
            2000,
            10);

    assertThat(runner.runDue()).isEqualTo(1);

    verify(checkpoints)
        .complete(
            eq(source),
            eq("NOT_IMPLEMENTED"),
            eq("The provider collector is not implemented."),
            eq("previous-page"),
            eq(source.lastCollectedAt()),
            any(Instant.class),
            eq(false));
  }

  @Test
  void continuesProcessingWhenAnotherRunnerHasTakenAClaimedLease() {
    CollectionCheckpointStore checkpoints = mock(CollectionCheckpointStore.class);
    LeasedSource first = source();
    LeasedSource second =
        new LeasedSource(
            first.tenantId(),
            first.environmentId(),
            "connection-2",
            "source-2",
            first.provider(),
            first.readOnlyIdentityRef(),
            first.awsExternalId(),
            first.checkpoint(),
            first.lastCollectedAt(),
            first.lastSequence(),
            UUID.randomUUID(),
            null,
            null,
            null);
    when(checkpoints.claimDue(1, Duration.ofMillis(2000)))
        .thenReturn(List.of(first), List.of(second));
    doAnswer(
            invocation -> {
              LeasedSource lease = invocation.getArgument(0);
              if (first.connectionId().equals(lease.connectionId())) {
                throw new IllegalStateException("Lease was taken by another runner.");
              }
              return null;
            })
        .when(checkpoints)
        .renew(any(), any());
    AtomicInteger collectionCalls = new AtomicInteger();
    ProviderCollector collector =
        new ProviderCollector() {
          @Override
          public ConnectionsRequests.Provider provider() {
            return ConnectionsRequests.Provider.AWS;
          }

          @Override
          public Batch collect(Request request) {
            collectionCalls.incrementAndGet();
            return Batch.notImplemented("No provider collection in this test.");
          }
        };
    CollectionRunner runner =
        runner(
            checkpoints,
            collector,
            "http://127.0.0.1:1",
            2,
            2000,
            10);

    assertThat(runner.runDue()).isEqualTo(2);

    assertThat(collectionCalls).hasValue(1);
    verify(checkpoints)
        .complete(
            eq(second),
            eq("NOT_IMPLEMENTED"),
            eq("The provider collector is not implemented."),
            eq(second.checkpoint()),
            eq(second.lastCollectedAt()),
            any(Instant.class),
            eq(false));
  }

  @Test
  void providerRetryTimeIsNotShortenedToTheRunnerInterval() {
    CollectionCheckpointStore checkpoints = mock(CollectionCheckpointStore.class);
    LeasedSource source = source();
    when(checkpoints.claimDue(1, Duration.ofMillis(2000))).thenReturn(List.of(source));
    Instant retryAt = Instant.now().plusSeconds(120);
    ProviderCollector collector =
        new ProviderCollector() {
          @Override
          public ConnectionsRequests.Provider provider() {
            return ConnectionsRequests.Provider.AWS;
          }

          @Override
          public Batch collect(Request request) {
            return new Batch(Batch.Status.RATE_LIMITED, List.of(), null, "429", retryAt);
          }
        };
    CollectionRunner runner =
        runner(
            checkpoints,
            collector,
            "http://127.0.0.1:1",
            1,
            2000,
            10);

    assertThat(runner.runDue()).isEqualTo(1);

    ArgumentCaptor<Instant> nextCollectionAt = ArgumentCaptor.forClass(Instant.class);
    verify(checkpoints)
        .complete(
            eq(source),
            eq("RATE_LIMITED"),
            eq("Provider rate limit reached; retry scheduled."),
            eq("previous-page"),
            eq(source.lastCollectedAt()),
            nextCollectionAt.capture(),
            eq(false));
    assertThat(nextCollectionAt.getValue()).isAfterOrEqualTo(retryAt);
  }

  @Test
  void failedIntakeRetainsThePreviousCheckpoint() throws Exception {
    List<String> calls = new CopyOnWriteArrayList<>();
    HttpServer server = startServer(calls, 500, "{\"error\":\"temporary\"}");
    try {
      String baseUrl = "http://localhost:" + server.getAddress().getPort();
      CollectionCheckpointStore checkpoints = mock(CollectionCheckpointStore.class);
      LeasedSource source = source();
      when(checkpoints.claimDue(1, Duration.ofMillis(2000))).thenReturn(List.of(source));
      ProviderCollector collector =
          new ProviderCollector() {
            @Override
            public ConnectionsRequests.Provider provider() {
              return ConnectionsRequests.Provider.AWS;
            }

            @Override
            public Batch collect(Request request) {
              return new Batch(
                  Batch.Status.COLLECTED,
                  List.of(
                      new Observation(
                          "obs-1",
                          8,
                          Instant.parse("2026-11-17T17:00:00Z"),
                          "aws.config.resources",
                          "{\"resource\":\"fixture\"}")),
                  "next-page",
                  null);
            }
          };
      CollectionRunner runner = runner(checkpoints, collector, baseUrl, 1, 2000, 10);

      assertThat(runner.runDue()).isEqualTo(1);

      verify(checkpoints)
          .storePending(
              eq(source),
              eq(
                  List.of(
                      new Observation(
                          "obs-1",
                          8,
                          Instant.parse("2026-11-17T17:00:00Z"),
                          "aws.config.resources",
                          "{\"resource\":\"fixture\"}"))),
              eq("next-page"),
              any());
      verify(checkpoints)
          .complete(
              eq(source),
              eq("FAILED"),
              eq("Collection attempt failed; retry scheduled."),
              eq("previous-page"),
              eq(source.lastCollectedAt()),
              any(Instant.class),
              eq(false));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void timesOutSlowIdentityEndpointWithoutAdvancingCheckpoint() throws Exception {
    CountDownLatch releaseTokenRequest = new CountDownLatch(1);
    HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/token",
        exchange -> {
          try {
            releaseTokenRequest.await(10, TimeUnit.SECONDS);
            exchange.close();
          } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            exchange.close();
          }
        });
    server.start();
    try {
      String baseUrl = "http://localhost:" + server.getAddress().getPort();
      CollectionCheckpointStore checkpoints = mock(CollectionCheckpointStore.class);
      LeasedSource source = source();
      when(checkpoints.claimDue(1, Duration.ofMillis(2000))).thenReturn(List.of(source));
      ProviderCollector collector =
          new ProviderCollector() {
            @Override
            public ConnectionsRequests.Provider provider() {
              return ConnectionsRequests.Provider.AWS;
            }

            @Override
            public Batch collect(Request request) {
              return new Batch(
                  Batch.Status.COLLECTED,
                  List.of(
                      new Observation(
                          "obs-1",
                          8,
                          Instant.parse("2026-11-17T17:00:00Z"),
                          "aws.config.resources",
                          "{\"resource\":\"fixture\"}")),
                  "next-page",
                  null);
            }
          };
      CollectionRunner runner =
          runner(checkpoints, collector, baseUrl, 1, 2000, 10);
      long startedAt = System.nanoTime();

      assertThat(runner.runDue()).isEqualTo(1);

      assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
          .isLessThan(Duration.ofSeconds(8));
      verify(checkpoints).storePending(eq(source), any(), eq("next-page"), any());
      verify(checkpoints)
          .complete(
              eq(source),
              eq("FAILED"),
              eq("Collection attempt failed; retry scheduled."),
              eq("previous-page"),
              eq(source.lastCollectedAt()),
              any(Instant.class),
              eq(false));
    } finally {
      releaseTokenRequest.countDown();
      server.stop(0);
    }
  }

  @Test
  void retriesPersistedObservationsWithoutRecollecting() throws Exception {
    List<String> calls = new CopyOnWriteArrayList<>();
    HttpServer server =
        startServer(
            calls,
            202,
            "{\"id\":\"obs-1\",\"status\":\"DUPLICATE\",\"reason\":\"Duplicate.\",\"sequence\":8}");
    try {
      String baseUrl = "http://localhost:" + server.getAddress().getPort();
      CollectionCheckpointStore checkpoints = mock(CollectionCheckpointStore.class);
      Instant collectedAt = Instant.parse("2026-11-17T17:00:00Z");
      Observation observation =
          new Observation(
              "obs-1",
              8,
              collectedAt,
              "aws.config.resources",
              "{\"resource\":\"fixture\"}");
      LeasedSource source = sourceWithPending(List.of(observation), "next-page", collectedAt);
      when(checkpoints.claimDue(1, Duration.ofMillis(2000))).thenReturn(List.of(source));
      AtomicInteger collectionCalls = new AtomicInteger();
      ProviderCollector collector =
          new ProviderCollector() {
            @Override
            public ConnectionsRequests.Provider provider() {
              return ConnectionsRequests.Provider.AWS;
            }

            @Override
            public Batch collect(Request request) {
              collectionCalls.incrementAndGet();
              return Batch.notImplemented("Must reuse the persisted batch");
            }
          };
      CollectionRunner runner = runner(checkpoints, collector, baseUrl, 1, 2000, 10);

      assertThat(runner.runDue()).isEqualTo(1);

      assertThat(collectionCalls).hasValue(0);
      assertThat(calls)
          .anyMatch(
              call ->
                  call.startsWith("observation:Bearer delegation-token")
                      && call.contains("\"sequence\":8")
                      && call.contains("aws.config.resources"));
      verify(checkpoints, never()).storePending(any(), any(), any(), any());
      verify(checkpoints)
          .complete(
              eq(source),
              eq("COLLECTED"),
              eq("Batch submitted to observation intake."),
              eq("next-page"),
              eq(collectedAt),
              any(),
              eq(true));
    } finally {
      server.stop(0);
    }
  }

  private static CollectionRunner runner(
      CollectionCheckpointStore checkpoints,
      ProviderCollector collector,
      String baseUrl,
      int batchSize,
      long leaseMs,
      int maxObservations) {
    return new CollectionRunner(
        checkpoints,
        List.of(collector),
        baseUrl + "/token",
        "collector-client",
        "collector-secret",
        baseUrl + "/delegations",
        baseUrl,
        60000,
        leaseMs,
        batchSize,
        maxObservations);
  }

  private static LeasedSource source() {
    return new LeasedSource(
        "tenant-1",
        "environment-1",
        "connection-1",
        "source-1",
        "AWS",
        "identity-ref",
        "uacrf-external-id",
        "previous-page",
        Instant.parse("2026-11-17T16:00:00Z"),
        7,
        UUID.randomUUID(),
        null,
        null,
        null);
  }

  private static LeasedSource sourceWithPending(
      List<Observation> observations, String nextCheckpoint, Instant collectedAt) {
    LeasedSource source = source();
    return new LeasedSource(
        source.tenantId(),
        source.environmentId(),
        source.connectionId(),
        source.sourceRef(),
        source.provider(),
        source.readOnlyIdentityRef(),
        source.awsExternalId(),
        source.checkpoint(),
        source.lastCollectedAt(),
        8,
        source.leaseToken(),
        observations,
        nextCheckpoint,
        collectedAt);
  }

  private static HttpServer startServer(List<String> calls) throws IOException {
    return startServer(
        calls,
        202,
        "{\"id\":\"obs-1\",\"status\":\"ACCEPTED\",\"reason\":\"Accepted.\",\"sequence\":8}");
  }

  private static HttpServer startServer(
      List<String> calls, int observationStatus, String observationBody) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/token",
        exchange ->
            respond(
                exchange,
                calls,
                "token:",
                "{\"access_token\":\"identity-token\"}",
                200));
    server.createContext(
        "/delegations",
        exchange ->
            respond(
                exchange,
                calls,
                "delegation:",
                "{\"access_token\":\"delegation-token\"}",
                200));
    server.createContext(
        "/v1/connections/connection-1/observations",
        exchange ->
            respond(
                exchange,
                calls,
                "observation:",
                observationBody,
                observationStatus));
    server.start();
    return server;
  }

  private static void respond(
      HttpExchange exchange, List<String> calls, String label, String body, int status)
      throws IOException {
    String authorization = exchange.getRequestHeaders().getFirst("Authorization");
    String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    calls.add(label + (authorization == null ? "" : authorization) + "|" + request);
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
