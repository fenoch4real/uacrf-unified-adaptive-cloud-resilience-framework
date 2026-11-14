package org.uacrf.runtime;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.neo4j.driver.*;

record GraphConfig(String uri, String database, String user, String password) {
  static GraphConfig load(boolean worker) {
    Map<String, String> e = System.getenv();
    String p = worker ? "UACRF_GRAPH_WORKER_NEO4J_" : "UACRF_NEO4J_";
    GraphConfig c =
        new GraphConfig(
            Config.required(e, p + "URI"),
            Config.required(e, p + "DATABASE"),
            Config.required(e, p + "USER"),
            Config.required(e, p + "PASSWORD"));
    URI u = URI.create(c.uri());
    if (u.getUserInfo() != null
        || !java.util.Set.of("bolt+s", "neo4j+s", "bolt", "neo4j").contains(u.getScheme())
        || (!(u.getScheme().endsWith("+s"))
            && !java.util.Set.of("localhost", "127.0.0.1", "::1").contains(u.getHost())))
      throw new IllegalArgumentException(
          "Verified TLS Neo4j connection required except on loopback");
    return c;
  }

  Driver connect() {
    return GraphDatabase.driver(
        uri,
        AuthTokens.basic(user, password),
        org.neo4j.driver.Config.builder()
            .withConnectionTimeout(5, TimeUnit.SECONDS)
            .withConnectionAcquisitionTimeout(5, TimeUnit.SECONDS)
            .withMaxTransactionRetryTime(5, TimeUnit.SECONDS)
            .withMaxConnectionPoolSize(8)
            .build());
  }
}
