package org.uacrf.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.util.*;

final class PackageCheck {
  static void verify(Path root) throws Exception {
    int checks = 0;
    var map = Json.read(Files.readString(root.resolve("runtime/IMPLEMENTATION_MAP.json")));
    var contracts = new Contracts(root);
    if (Routes.ALL.size() != 30 || map.path("routes").size() != 30)
      throw new IllegalStateException("Route inventory mismatch");
    checks++;
    for (var r : Routes.ALL) {
      boolean present = false;
      for (var row : map.path("routes"))
        if (row.path("endpoint").asText().equals(r.method() + " " + r.path())
            && row.path("operation").asText().equals(r.operation())) present = true;
      if (!present || !contracts.operation(r).isObject())
        throw new IllegalStateException("Missing route or contract");
      checks++;
    }
    var report = Json.read(Files.readString(root.resolve("runtime/TEST_RESULTS.json")));
    if (!report.path("status").asText().equals("PASS")
        || !report.path("language").asText().equals("Java")
        || report.path("test_methods").asInt() != 32
        || !report.path("cleanup").asText().equals("REMOVED_BOTH_OWNED_CONTAINERS"))
      throw new IllegalStateException("Java integration evidence missing");
    checks++;
    try (var files = Files.list(root.resolve("runtime"))) {
      if (files.anyMatch(p -> p.toString().endsWith(".py")))
        throw new IllegalStateException("Python runtime remains");
    }
    checks++;
    Set<String> ids = new HashSet<>();
    for (var issue :
        Json.read(Files.readString(root.resolve("implementation_review/ISSUES.json")))
            .path("issues"))
      if (!ids.add(issue.path("id").asText()))
        throw new IllegalStateException("Duplicate audit ID");
    checks++;
    var result =
        Map.of(
            "status",
            "PASS_JAVA_RUNTIME_PACKAGE_STRUCTURE",
            "checks",
            checks,
            "integrated_evidence",
            "TEST_RESULTS.json",
            "scope",
            "Route inventory and executed Java evidence presence; not a repeated engine test");
    Files.writeString(root.resolve("runtime/PACKAGE_CHECKS.json"), Json.text(result) + "\n");
    System.out.println(Json.text(result));
  }

  static void fingerprint() throws Exception {
    byte[] raw = System.in.readNBytes(1048577);
    if (raw.length > 1048576)
      throw new IllegalArgumentException("Bounded canonical material required");
    JsonNode n = Json.read(new String(raw, java.nio.charset.StandardCharsets.UTF_8));
    if (!n.path("algorithm").asText().equals("SHA-256")
        || !n.path("encoding").asText().equals("UTF-8")
        || !n.path("canonical_text").isTextual()
        || !Json.hash(n.path("canonical_text").asText()).equals(n.path("digest").asText()))
      throw new IllegalArgumentException("Fingerprint mismatch");
    System.out.println("Verified canonical fingerprint");
  }
}
