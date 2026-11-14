package org.uacrf.evidence;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.uacrf.security.DelegationJwtConfiguration;

@SpringBootApplication
@Import(DelegationJwtConfiguration.class)
public class EvidenceApplication {
  public static void main(String[] args) {
    SpringApplication.run(EvidenceApplication.class, args);
  }
}
