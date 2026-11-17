package org.uacrf.decisiongovernance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.uacrf.security.DelegationJwtConfiguration;

@SpringBootApplication
@Import(DelegationJwtConfiguration.class)
public class DecisionGovernanceApplication {
  public static void main(String[] args) {
    SpringApplication.run(DecisionGovernanceApplication.class, args);
  }
}
