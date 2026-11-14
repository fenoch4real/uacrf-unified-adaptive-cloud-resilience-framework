package org.uacrf.assessment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.uacrf.security.DelegationJwtConfiguration;

@SpringBootApplication
@Import(DelegationJwtConfiguration.class)
public class AssessmentApplication {
  public static void main(String[] args) {
    SpringApplication.run(AssessmentApplication.class, args);
  }
}
