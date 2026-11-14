package org.uacrf.connections;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.uacrf.security.DelegationJwtConfiguration;

@SpringBootApplication
@EnableScheduling
@Import(DelegationJwtConfiguration.class)
public class ConnectionsApplication {
  public static void main(String[] args) {
    SpringApplication.run(ConnectionsApplication.class, args);
  }
}
