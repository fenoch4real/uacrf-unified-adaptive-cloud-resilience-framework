package org.uacrf.runtime;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Offline accepted-source import. Never reachable through an HTTP route or API database role. */
final class GraphAdmin {
  static String actor(Connection c, Config cfg) throws Exception {
    var claims =
        Identity.verify(
            Admin.secret("Verified graph acceptance token"),
            cfg.issuer(),
            cfg.audience(),
            cfg.keys(),
            900);
    var identity = new Identity.Directory(cfg).verified("identity", claims.path("sub").asText());
    if (!identity.path("kind").asText().equals("HUMAN")
        || !identity.path("subject").asText().equals(claims.path("sub").asText()))
      throw new IllegalArgumentException("Verified human acceptance required");
    String actor =
        Admin.scalar(
            c,
            "SELECT id FROM uacrf.principal WHERE tenant_id=? AND environment_id=? AND issuer=? AND"
                + " subject=? AND enabled AND kind='HUMAN' AND"
                + " ?::bigint>=coalesce((payload->>'token_valid_after')::bigint,0)",
            cfg.tenant(),
            cfg.environment(),
            cfg.issuer(),
            claims.path("sub").asText(),
            claims.path("iat").asLong());
    if (actor == null)
      throw new IllegalArgumentException("Current accountable principal unavailable");
    return actor;
  }

  static String load(
      Connection c, Config cfg, JsonNode manifest, String sourceDigest, String actor, String reason)
      throws Exception {
    var contracts = new Contracts(cfg.releaseRoot());
    var schema =
        Json.read(Files.readString(cfg.releaseRoot().resolve("graph_design/manifest.schema.json")));
    if (!contracts.valid(schema, manifest, "graph_design/manifest.schema.json")
        || !sourceDigest.matches("[a-f0-9]{64}"))
      throw new IllegalArgumentException("Graph source manifest contract rejected");
    String rid = manifest.path("revision_id").asText();
    c.setAutoCommit(false);
    try {
      Admin.execute(
          c,
          "SELECT"
              + " set_config('app.tenant_id',?,true),set_config('app.environment_id',?,true),set_config('app.actor_id',?,true)",
          cfg.tenant(),
          cfg.environment(),
          actor);
      Admin.execute(
          c,
          "INSERT INTO"
              + " uacrf.graph_revision(tenant_id,environment_id,id,service_id,digest,object_ref,source_watermark,status,payload)"
              + " VALUES(?,?,?,?,?,?,?,'BUILDING',?::jsonb)",
          cfg.tenant(),
          cfg.environment(),
          rid,
          manifest.path("service_id").asText(),
          Json.hash("BUILDING:" + UUID.randomUUID()),
          manifest.path("object_ref").asText(),
          manifest.path("source_watermark").asLong(),
          Json.text(Map.of("source_manifest_digest", sourceDigest)));
      Map<String, String> ids = new HashMap<>();
      int i = 0;
      for (var n : manifest.path("nodes")) {
        String canonical = n.path("canonical_id").asText(), id = rid + ":n" + (i++);
        if (ids.putIfAbsent(canonical, id) != null)
          throw new IllegalArgumentException("Duplicate canonical node");
        ObjectNode payload = Json.M.createObjectNode();
        for (String field : List.of("name", "provider", "region", "workload_kind", "workload_id"))
          if (n.has(field)) payload.set(field, n.path(field));
        Admin.execute(
            c,
            "INSERT INTO"
                + " uacrf.graph_node(tenant_id,environment_id,id,service_id,graph_revision_id,canonical_id,label,qualification_id,expires_at,payload)"
                + " VALUES(?,?,?,?,?,?,?,?,?::timestamptz,?::jsonb)",
            cfg.tenant(),
            cfg.environment(),
            id,
            manifest.path("service_id").asText(),
            rid,
            canonical,
            n.path("label").asText(),
            n.path("qualification_id").asText(),
            n.path("expires_at").asText(),
            Json.text(payload));
      }
      Set<String> edges = new HashSet<>();
      for (var edge : manifest.path("edges")) {
        String id = edge.path("relationship_id").asText();
        if (!edges.add(id)
            || !ids.containsKey(edge.path("source_id").asText())
            || !ids.containsKey(edge.path("target_id").asText()))
          throw new IllegalArgumentException("Duplicate edge or dangling endpoint");
        Admin.execute(
            c,
            "INSERT INTO"
                + " uacrf.graph_edge(tenant_id,environment_id,id,service_id,graph_revision_id,source_node_id,target_node_id,relationship_type,qualification_id,confidence,valid_from,valid_to,expires_at)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?::numeric,?::timestamptz,?::timestamptz,?::timestamptz)",
            cfg.tenant(),
            cfg.environment(),
            id,
            manifest.path("service_id").asText(),
            rid,
            ids.get(edge.path("source_id").asText()),
            ids.get(edge.path("target_id").asText()),
            edge.path("type").asText(),
            edge.path("qualification_id").asText(),
            edge.path("confidence").asText(),
            edge.path("valid_from").asText(),
            edge.hasNonNull("valid_to") ? edge.path("valid_to").asText() : null,
            edge.path("expires_at").asText());
      }
      String result =
          Admin.scalar(
              c,
              "SELECT uacrf_app.graph_seal(?,?,?,1,?,?)",
              cfg.tenant(),
              cfg.environment(),
              rid,
              reason,
              actor);
      c.commit();
      return result;
    } catch (Exception e) {
      c.rollback();
      throw e;
    } finally {
      c.setAutoCommit(true);
    }
  }

  static void main(String[] args) throws Exception {
    if (args.length != 3)
      throw new IllegalArgumentException("admin graph-load FILE EXPECTED_SHA256 REASON");
    Path file = Path.of(args[0]);
    if (Files.size(file) > 8388608)
      throw new IllegalArgumentException("Bounded source artifact required");
    String raw = Files.readString(file);
    if (!Json.hash(raw).equals(args[1]))
      throw new IllegalArgumentException("Source file hash mismatch");
    Config cfg = Config.load();
    try (var c = Admin.connect()) {
      System.out.println(load(c, cfg, Json.read(raw), args[1], actor(c, cfg), args[2]));
    }
  }
}
