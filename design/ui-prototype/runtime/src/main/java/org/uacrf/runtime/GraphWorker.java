package org.uacrf.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/**
 * Durable outbox consumer. SQL commits its lease before Neo4j work; acknowledgement follows Neo4j
 * commit.
 */
final class GraphWorker implements AutoCloseable {
  final String url, user, password;
  final GraphStore store;

  GraphWorker(String url, String user, String password, GraphStore store) {
    this.url = url;
    this.user = user;
    this.password = password;
    this.store = store;
  }

  Connection connect() throws SQLException {
    return DriverManager.getConnection(url, user, password);
  }

  boolean once() throws Exception {
    ObjectNode claim;
    try (var c = connect()) {
      String raw = Admin.scalar(c, "SELECT uacrf_app.graph_claim()");
      if (raw == null) return false;
      claim = (ObjectNode) Json.read(raw);
    }
    String rid = claim.path("snapshot").path("graph_revision").asText();
    boolean success = false;
    try {
      store.project(claim);
      success = true;
    } finally {
      try (var c = connect()) {
        Admin.execute(
            c,
            "SELECT uacrf_app.graph_complete(?,?::uuid,?,?)",
            rid,
            claim.path("lease_id").asText(),
            claim.path("digest").asText(),
            success);
      }
    }
    return true;
  }

  void requeue(String revision) throws Exception {
    try (var c = connect()) {
      Admin.execute(c, "SELECT uacrf_app.graph_requeue(?)", revision);
    }
  }

  @Override
  public void close() {
    store.close();
  }

  static void main(String[] args) throws Exception {
    Map<String, String> e = System.getenv();
    Path root = Path.of(Config.required(e, "UACRF_RELEASE_ROOT"));
    try (var worker =
        new GraphWorker(
            Config.required(e, "UACRF_GRAPH_WORKER_JDBC_URL"),
            Config.required(e, "UACRF_GRAPH_WORKER_DB_USER"),
            Config.required(e, "UACRF_GRAPH_WORKER_DB_PASSWORD"),
            new GraphStore(GraphConfig.load(true), root))) {
      if (args.length == 2 && args[0].equals("requeue")) {
        worker.requeue(args[1]);
        System.out.println("Graph revision queued for reconciliation");
        return;
      }
      if (args.length != 1 || !Set.of("once", "run").contains(args[0]))
        throw new IllegalArgumentException("graph-worker once | run | requeue REVISION");
      do {
        try {
          boolean worked = worker.once();
          if (args[0].equals("once")) {
            System.out.println(
                worked
                    ? "Graph projection committed and acknowledged"
                    : "No eligible projection work");
            return;
          }
          if (!worked) Thread.sleep(1000);
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          return;
        } catch (Exception ex) {
          if (args[0].equals("once"))
            throw new IllegalStateException("Graph projection failed; retained for retry");
          System.err.println("Graph projection unavailable; retry scheduled");
          Thread.sleep(5000);
        }
      } while (!Thread.currentThread().isInterrupted());
    }
  }
}
