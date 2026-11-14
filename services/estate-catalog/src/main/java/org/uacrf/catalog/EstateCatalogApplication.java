package org.uacrf.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.uacrf.security.DelegationJwtConfiguration;

@SpringBootApplication
@Import(DelegationJwtConfiguration.class)
public class EstateCatalogApplication {
  public static void main(String[] args) {
    SpringApplication.run(EstateCatalogApplication.class, args);
  }
}
