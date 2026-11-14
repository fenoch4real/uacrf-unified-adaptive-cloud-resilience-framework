package org.uacrf.edge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ApiEdgeApplicationTests {
  @Autowired private IdentityAccessRouteController identityAccessRoute;

  @Test
  void startsWithTheCurrentAccessRouteConfigured() {
    assertThat(identityAccessRoute).isNotNull();
  }
}
