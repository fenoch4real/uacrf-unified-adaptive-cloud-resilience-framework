package org.uacrf.knowledge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.uacrf.security.DelegationJwtConfiguration;

@SpringBootApplication
@Import(DelegationJwtConfiguration.class)
public class KnowledgeApplication {
  public static void main(String[] args) {
    SpringApplication.run(KnowledgeApplication.class, args);
  }
}
