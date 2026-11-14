package org.uacrf.topology;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.concurrent.TimeUnit;

@Configuration
class Neo4jConfiguration {
  @Bean(destroyMethod = "close")
  Driver topologyNeo4jDriver(
      @Value("${uacrf.topology.neo4j.uri}") String uri,
      @Value("${uacrf.topology.neo4j.user}") String user,
      @Value("${uacrf.topology.neo4j.password}") String password,
      @Value("${uacrf.topology.neo4j.connection-timeout-ms:5000}") long connectionTimeoutMillis,
      @Value("${uacrf.topology.neo4j.max-transaction-retry-ms:5000}") long retryMillis) {
    Config config = Config.builder()
        .withConnectionTimeout(connectionTimeoutMillis, TimeUnit.MILLISECONDS)
        .withMaxTransactionRetryTime(retryMillis, TimeUnit.MILLISECONDS)
        .build();
    return GraphDatabase.driver(uri, AuthTokens.basic(user, password), config);
  }
}
