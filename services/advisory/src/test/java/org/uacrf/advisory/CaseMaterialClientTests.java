package org.uacrf.advisory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CaseMaterialClientTests {
  private static final String MATERIAL =
      "{\"workload_id\":\"workload-1\",\"purpose\":\"assessment\",\"required_source_kinds\":[\"metrics\"]}";
  private static final String DIGEST = Sha256.hex(MATERIAL.getBytes(StandardCharsets.UTF_8));
  private static final SourceReceiptConsistency.Expected EXPECTED = new SourceReceiptConsistency.Expected(
      "case-1", "retrieval-1", "b".repeat(64), "assessment", SourceReceiptConsistency.Classification.INTERNAL);
  private static final SourceReceiptConsistency.CaseReceipt RECEIPT = new SourceReceiptConsistency.CaseReceipt(
      "case-1", "assessment", DIGEST, "workload-1", List.of("metrics"));
  private final AtomicReference<Map<String, String>> reply = new AtomicReference<>(
      Map.of("case_id", "case-1", "case_digest", DIGEST, "canonical_text", MATERIAL));
  private final AtomicInteger status = new AtomicInteger(200);
  private final AtomicInteger calls = new AtomicInteger();
  private HttpServer server;
  private CaseMaterialClient client;
  private URI base;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/internal/v1/cases/case-1:material", exchange -> {
      calls.incrementAndGet();
      boolean authorized = "POST".equals(exchange.getRequestMethod())
          && "Bearer case-token".equals(exchange.getRequestHeaders().getFirst("Authorization"));
      byte[] bytes = JsonMapper.builder().build().writeValueAsBytes(authorized ? reply.get() : Map.of());
      exchange.sendResponseHeaders(authorized ? status.get() : 403, bytes.length);
      try (var output = exchange.getResponseBody()) {
        output.write(bytes);
      }
    });
    server.start();
    base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    client = new CaseMaterialClient(true, base, true, (tenant, environment, audience, capabilities) -> {
      assertEquals("tenant-1", tenant);
      assertEquals("env-1", environment);
      assertEquals("S07", audience);
      assertEquals(List.of("assessment.case.read"), capabilities);
      return "case-token";
    });
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void returnsDigestBoundCaseMaterial() throws Exception {
    assertEquals(MATERIAL, client.fetch("tenant-1", "env-1", EXPECTED, RECEIPT));
  }

  @Test
  void rejectsSubstitutionAndReceiptMetadataMismatch() {
    for (Map<String, String> invalid : List.of(
        Map.of("case_id", "case-2", "case_digest", DIGEST, "canonical_text", MATERIAL),
        Map.of("case_id", "case-1", "case_digest", "0".repeat(64), "canonical_text", MATERIAL),
        Map.of("case_id", "case-1", "case_digest", DIGEST, "canonical_text", MATERIAL + " "),
        Map.of("case_id", "case-1", "case_digest", DIGEST, "canonical_text", ""),
        Map.of("case_id", "case-1", "case_digest", DIGEST),
        Map.of("case_id", "case-1", "case_digest", DIGEST, "canonical_text", "\ud800"))) {
      reply.set(invalid);
      assertThrows(IOException.class, () -> client.fetch("tenant-1", "env-1", EXPECTED, RECEIPT));
    }
    reply.set(Map.of("case_id", "case-1", "case_digest", DIGEST, "canonical_text", MATERIAL));
    var changed = new SourceReceiptConsistency.CaseReceipt(
        "case-1", "assessment", DIGEST, "other-workload", List.of("metrics"));
    assertThrows(IOException.class, () -> client.fetch("tenant-1", "env-1", EXPECTED, changed));
    var changedKinds = new SourceReceiptConsistency.CaseReceipt(
        "case-1", "assessment", DIGEST, "workload-1", List.of("other"));
    assertThrows(IOException.class, () -> client.fetch("tenant-1", "env-1", EXPECTED, changedKinds));
  }

  @Test
  void rejectsDigestBoundMalformedAndAmbiguousCaseJson() {
    for (String material : List.of("not-json", "[]",
        "{\"workload_id\":\"workload-1\",\"workload_id\":\"other\",\"purpose\":\"assessment\","
            + "\"required_source_kinds\":[\"metrics\"]}",
        MATERIAL + "{}")) {
      String digest = Sha256.hex(material.getBytes(StandardCharsets.UTF_8));
      var receipt = new SourceReceiptConsistency.CaseReceipt(
          "case-1", "assessment", digest, "workload-1", List.of("metrics"));
      reply.set(Map.of("case_id", "case-1", "case_digest", digest, "canonical_text", material));
      assertThrows(IOException.class, () -> client.fetch("tenant-1", "env-1", EXPECTED, receipt));
    }
  }

  @Test
  void failsClosedWhenDisabledOrOwnerUnavailable() {
    var disabled = new CaseMaterialClient(false, base, true, (tenant, environment, audience, caps) -> "case-token");
    assertThrows(IllegalStateException.class, () -> disabled.fetch("tenant-1", "env-1", EXPECTED, RECEIPT));
    assertEquals(0, calls.get());
    status.set(503);
    assertThrows(IOException.class, () -> client.fetch("tenant-1", "env-1", EXPECTED, RECEIPT));
    assertEquals(1, calls.get());
  }
}
