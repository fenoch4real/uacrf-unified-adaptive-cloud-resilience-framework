package org.uacrf.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiEdgeApplicationTests {
  @Autowired private IdentityAccessRouteController identityAccessRoute;
  @LocalServerPort private int port;

  @Test
  void startsWithTheCurrentAccessRouteConfigured() {
    assertThat(identityAccessRoute).isNotNull();
  }

  @Test
  void doesNotRouteInternalObjectiveReviewAuthorityCheck() throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    for (String path : new String[] {
        "/internal/v1/objective-review-authority:check",
        "/internal/identity/internal/v1/objective-review-authority:check"}) {
      HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
      assertEquals(404, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
    }
  }
}
