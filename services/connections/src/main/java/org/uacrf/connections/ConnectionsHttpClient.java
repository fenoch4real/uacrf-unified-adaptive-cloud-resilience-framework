package org.uacrf.connections;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

final class ConnectionsHttpClient {
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
  private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(3);

  private ConnectionsHttpClient() {}

  static RestClient create() {
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(RESPONSE_TIMEOUT);
    return RestClient.builder().requestFactory(requestFactory).build();
  }

  static void validateEndpoint(String endpoint, String name) {
    if (endpoint == null || endpoint.isBlank()) {
      throw new IllegalArgumentException(name + " must be configured when enabled.");
    }
    URI uri;
    try {
      uri = URI.create(endpoint);
    } catch (IllegalArgumentException error) {
      throw invalidEndpoint(name, error);
    }
    if (!uri.isAbsolute()
        || uri.getHost() == null
        || !("http".equalsIgnoreCase(uri.getScheme())
            || "https".equalsIgnoreCase(uri.getScheme()))) {
      throw invalidEndpoint(name, null);
    }
  }

  private static IllegalArgumentException invalidEndpoint(String name, Throwable cause) {
    return new IllegalArgumentException(
        name + " must be an absolute HTTP(S) URL.", cause);
  }
}
