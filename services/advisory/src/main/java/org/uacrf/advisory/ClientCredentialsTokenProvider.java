package org.uacrf.advisory;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

final class ClientCredentialsTokenProvider implements S01DelegationClient.ActorTokenProvider {
  private final boolean enabled;
  private final URI tokenEndpoint;
  private final String clientId;
  private final String clientSecret;
  private final InternalJsonClient http = new InternalJsonClient();

  ClientCredentialsTokenProvider(boolean enabled, URI tokenEndpoint, boolean allowLoopbackHttp,
                                 String clientId, String clientSecret) {
    this.enabled = enabled;
    this.tokenEndpoint = InternalJsonClient.validateEndpoint(tokenEndpoint, allowLoopbackHttp);
    if (tokenEndpoint.getPath() == null || tokenEndpoint.getPath().isEmpty()
        || clientId == null || clientId.isBlank() || clientId.length() > 256
        || clientSecret == null || clientSecret.isBlank() || clientSecret.length() > 4096) {
      throw new IllegalArgumentException("Invalid client credentials configuration.");
    }
    this.clientId = clientId;
    this.clientSecret = clientSecret;
  }

  @Override
  public String token() throws IOException, InterruptedException {
    if (!enabled) {
      throw new IllegalStateException("Client credentials token fetching is disabled.");
    }
    Map<?, ?> response = http.postForm(tokenEndpoint, Map.of(
        "grant_type", "client_credentials", "client_id", clientId, "client_secret", clientSecret));
    if (!(response.get("access_token") instanceof String token)
        || !(response.get("token_type") instanceof String type)
        || !"Bearer".equalsIgnoreCase(type)
        || !(response.get("expires_in") instanceof Integer lifetime) || lifetime <= 0) {
      throw new IOException("Identity provider returned an invalid token response.");
    }
    return InternalJsonClient.requireToken(token);
  }
}
