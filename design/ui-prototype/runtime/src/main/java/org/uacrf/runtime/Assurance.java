package org.uacrf.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.sql.*;
import java.util.*;

/** Persistent diagnostic baselines. These records do not certify or execute recovery. */
final class Assurance {
  final GraphService graphs;

  Assurance(GraphService graphs) {
    this.graphs = graphs;
  }

  ObjectNode context(Connection c, ObjectNode claims, String id, String journey, String graph)
      throws SQLException {
    return (ObjectNode)
        Json.read(
            Admin.scalar(
                c,
                "SELECT uacrf_app.assurance_context(?,?,?,?,?,?,?,?)",
                graphs.cfg.tenant(),
                graphs.cfg.environment(),
                claims.path("iss").asText(),
                claims.path("sub").asText(),
                claims.path("iat").asLong(),
                id,
                journey,
                graph));
  }

  ObjectNode handle(ObjectNode claims, String operation, String id, ObjectNode body, String key) {
    try (var c = graphs.db.connect()) {
      c.setAutoCommit(false);
      try {
        Admin.execute(c, "SET LOCAL statement_timeout='20s'");
        Admin.execute(c, "SET LOCAL lock_timeout='5s'");
        boolean create = operation.equals("assurance-create"),
            read = operation.equals("assurance-get");
        ObjectNode ctx =
            context(
                c,
                claims,
                create ? null : id,
                body.path("journey_id").asText(),
                body.path("graph_revision_id").asText());
        ObjectNode result;
        if (read) {
          result = ((ObjectNode) ctx.path("result")).deepCopy();
          var active = graphs.context(c, claims, result.path("graph_revision_id").asText());
          boolean usable =
              active.path("eligible").asBoolean() && active.path("projected").asBoolean();
          if (usable)
            graphs.store.verified(
                Json.read(active.path("canonical_text").asText()), active.path("digest").asText());
          else result.put("state", "INSUFFICIENT_EVIDENCE");
        } else {
          String rid = body.path("graph_revision_id").asText();
          ObjectNode current = graphs.context(c, claims, rid);
          if (!current.path("eligible").asBoolean() || !current.path("projected").asBoolean())
            throw new Problem(409, "Accepted current graph evidence required");
          var now =
              graphs.store.verified(
                  Json.read(current.path("canonical_text").asText()),
                  current.path("digest").asText());
          ObjectNode base = create ? null : (ObjectNode) ctx.path("result");
          String primary =
              create ? body.path("primary_id").asText() : base.path("primary_id").asText();
          String recovery =
              create ? body.path("recovery_id").asText() : base.path("recovery_id").asText();
          GraphService.requireNode(now, primary);
          GraphService.requireNode(now, recovery);
          if (primary.equals(recovery))
            throw new Problem(422, "Distinct recovery and primary nodes required");
          // The baseline graph remains immutable; all future comparisons refer to this original
          // context.
          var before = Json.read(ctx.path("context").path("canonical_text").asText());
          result = compare(before, now, primary, recovery);
          result
              .put("id", create ? UUID.randomUUID().toString() : id)
              .put(
                  "journey_id",
                  create ? body.path("journey_id").asText() : base.path("journey_id").asText())
              .put(
                  "baseline_graph_revision_id",
                  create ? rid : base.path("baseline_graph_revision_id").asText())
              .put("graph_revision_id", rid)
              .put("primary_id", primary)
              .put("recovery_id", recovery)
              .put(
                  "scenario",
                  create ? body.path("scenario").asText() : base.path("scenario").asText())
              .put("reason", body.path("reason").asText());
          if (!ctx.path("context").path("eligible").asBoolean()) {
            result.put("state", "INSUFFICIENT_EVIDENCE");
            finding(
                result,
                "BASELINE_EVIDENCE_UNAVAILABLE",
                "baseline",
                "The historical baseline is retained but its evidence is no longer eligible."
                    + " Establish a new reviewed baseline; this comparison cannot renew it.");
          }
          // Recheck after the remote Neo4j read. SQL owns scope, expected revision, audit and
          // idempotency.
          String dg =
              Json.hash(Json.text(Api.sorted(body)) + ":" + operation + ":" + (create ? "" : id));
          result =
              (ObjectNode)
                  Json.read(
                      Admin.scalar(
                          c,
                          "SELECT uacrf_app.assurance_save(?,?,?,?,?,?,?,?,?,?,?,?::jsonb)",
                          graphs.cfg.tenant(),
                          graphs.cfg.environment(),
                          claims.path("iss").asText(),
                          claims.path("sub").asText(),
                          claims.path("iat").asLong(),
                          result.path("id").asText(),
                          result.path("journey_id").asText(),
                          rid,
                          create ? 0 : body.path("expected_revision").asLong(),
                          key,
                          dg,
                          Json.text(result)));
          ctx = context(c, claims, result.path("id").asText(), null, null);
        }
        ArrayNode history = result.putArray("history");
        int n = 0;
        for (var row : ctx.path("reviews")) {
          if (n++ >= 100) break;
          graphs.context(c, claims, row.path("graph_revision_id").asText());
          history
              .addObject()
              .setAll(
                  (ObjectNode)
                      Json.M.createObjectNode()
                          .put("revision", row.path("revision").asLong())
                          .put("graph_revision_id", row.path("graph_revision_id").asText())
                          .put("state", row.path("state").asText())
                          .put("observed_at", row.path("observed_at").asText()));
        }
        result.put("history_truncated", ctx.path("reviews").size() > 100);
        ArrayNode pending = result.putArray("pending_graph_revisions");
        n = 0;
        for (var candidate : ctx.path("pending")) {
          if (n++ >= 100) break;
          // Conceal candidates that the caller cannot currently inspect, without leaking IDs.
          try {
            graphs.context(c, claims, candidate.asText());
            pending.add(candidate.asText());
          } catch (Problem ex) {
            throw ex;
          }
        }
        result.put("pending_truncated", ctx.path("pending").size() > 100);
        if (pending.size() > 0
            && result.path("state").asText().equals("NO_RELEVANT_CHANGE_OBSERVED"))
          result.put("state", "REVIEW_REQUIRED");
        result.put("coverage", "SELECTED_ACCEPTED_GRAPH_ONLY").put("automatic_recovery", false);
        var after = graphs.context(c, claims, result.path("graph_revision_id").asText());
        if (!ctx.path("context").path("eligible").asBoolean()
            || !after.path("eligible").asBoolean()
            || !after.path("projected").asBoolean()) result.put("state", "INSUFFICIENT_EVIDENCE");
        c.commit();
        return result;
      } catch (Exception ex) {
        c.rollback();
        throw ex;
      }
    } catch (Problem p) {
      throw p;
    } catch (SQLException ex) {
      String state = ex.getSQLState();
      if (state != null && state.matches("U0[0-9]{3}"))
        throw new Problem(
            Integer.parseInt(state.substring(2)),
            "Assurance scope, authority or revision rejected");
      if (Set.of("23505", "40001", "40P01").contains(state == null ? "" : state))
        throw new Problem(409, "Concurrent assurance conflict");
      throw new Problem(503, "Assurance persistence boundary unavailable");
    } catch (Exception ex) {
      throw new Problem(503, "Verified graph unavailable for assurance");
    }
  }

  static void finding(ObjectNode r, String kind, String subject, String explanation) {
    ((ArrayNode) r.path("findings"))
        .addObject()
        .put("kind", kind)
        .put("subject", subject)
        .put("explanation", explanation);
  }

  static JsonNode ordered(JsonNode value) {
    value = GraphStore.normalized(value);
    if (value.isObject()) {
      var out = Json.M.createObjectNode();
      var keys = new TreeSet<String>();
      value.fieldNames().forEachRemaining(keys::add);
      for (String k : keys) out.set(k, ordered(value.path(k)));
      return out;
    }
    if (value.isArray()) {
      var out = Json.M.createArrayNode();
      for (var x : value) out.add(ordered(x));
      return out;
    }
    return value;
  }

  static String canonical(JsonNode value) {
    return Json.text(ordered(value));
  }

  static SortedMap<String, String> relevant(JsonNode graph, String primary, String recovery) {
    Set<String> ids = new TreeSet<>();
    ids.add(primary);
    ids.add(recovery);
    ids.addAll(GraphService.walk(graph, primary, false, 8).ids());
    ids.addAll(GraphService.walk(graph, recovery, false, 8).ids());
    SortedMap<String, String> out = new TreeMap<>();
    for (var node : graph.path("nodes"))
      if (ids.contains(node.path("canonical_id").asText()))
        out.put("node:" + node.path("canonical_id").asText(), canonical(node));
    // SQL row IDs are revision-local. Group by semantic identity so a copied revision is not a new
    // edge.
    Map<String, List<String>> edges = new TreeMap<>();
    for (var edge : graph.path("edges"))
      if (GraphService.DEPENDENCIES.contains(edge.path("type").asText())
          && ids.contains(edge.path("source_id").asText())) {
        String k =
            "edge:"
                + Json.text(
                    List.of(
                        edge.path("source_id").asText(),
                        edge.path("type").asText(),
                        edge.path("target_id").asText()));
        edges.computeIfAbsent(k, x -> new ArrayList<>()).add(canonical(edge.path("properties")));
      }
    edges.forEach(
        (k, v) -> {
          Collections.sort(v);
          out.put(k, Json.text(v));
        });
    return out;
  }

  static ObjectNode compare(JsonNode old, JsonNode now, String primary, String recovery) {
    var r =
        Json.M.createObjectNode()
            .put("state", "NO_RELEVANT_CHANGE_OBSERVED")
            .put("independence", "NOT_CERTIFIED")
            .put("runtime_verification", "NOT_ESTABLISHED");
    r.putArray("findings");
    var a = GraphService.walk(now, primary, false, 8);
    var b = GraphService.walk(now, recovery, false, 8);
    Set<String> shared = new TreeSet<>(a.ids());
    shared.add(primary);
    Set<String> right = new TreeSet<>(b.ids());
    right.add(recovery);
    shared.retainAll(right);
    r.set("shared_nodes", Json.M.valueToTree(shared));
    if (!shared.isEmpty()) {
      r.put("state", "REVIEW_REQUIRED");
      for (String id : shared)
        finding(
            r,
            "SHARED_DEPENDENCY",
            id,
            "Both selected paths reach this dependency. Review its relevance to the declared"
                + " failure scenario.");
    }
    var left = relevant(old, primary, recovery);
    var next = relevant(now, primary, recovery);
    Set<String> keys = new TreeSet<>(left.keySet());
    keys.addAll(next.keySet());
    for (String k : keys)
      if (!Objects.equals(left.get(k), next.get(k))) {
        r.put("state", "REVIEW_REQUIRED");
        finding(
            r,
            left.containsKey(k) ? next.containsKey(k) ? "CHANGED" : "REMOVED" : "ADDED",
            k,
            "The selected paths differ from the immutable baseline. Review the dependency, evidence"
                + " or configuration change and determine the required retest.");
      }
    if (a.truncated()
        || b.truncated()
        || GraphService.walk(old, primary, false, 8).truncated()
        || GraphService.walk(old, recovery, false, 8).truncated()) {
      r.put("state", "INSUFFICIENT_EVIDENCE");
      finding(
          r,
          "TRAVERSAL_LIMIT",
          "selected paths",
          "Depth eight or the 1,000-node bound prevents a complete comparison.");
    }
    r.putArray("required_next_steps")
        .add("An accountable human must review the findings against the declared scenario.")
        .add(
            "Refresh affected evidence and perform the agreed controlled retest before asserting"
                + " readiness.")
        .add(
            "No shared dependency observed is not proof of independent recovery. Agent retries and"
                + " external effects require separate reconciliation.");
    return r;
  }
}
