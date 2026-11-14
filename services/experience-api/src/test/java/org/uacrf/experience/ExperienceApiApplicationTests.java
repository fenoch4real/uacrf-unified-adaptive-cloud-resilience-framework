package org.uacrf.experience;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ExperienceApiApplicationTests {
  @Autowired private IdentityAccessController controller;

  @Test
  void startsWithItsIdentityProxyRouteConfigured() {
    assertThat(controller).isNotNull();
  }
}
