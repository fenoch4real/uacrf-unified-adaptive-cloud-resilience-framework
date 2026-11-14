package org.uacrf.identity;

import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Reads users from Keycloak's admin API using a server-held service-account credential. */
@Component
@ConditionalOnProperty(prefix = "uacrf.identity.directory.keycloak", name = "enabled", havingValue = "true")
class KeycloakTrustedDirectory implements TrustedDirectory {
  private final RestClient http;
  private final ObjectMapper objectMapper;
  private final String issuerUri;
  private final String tokenUri;
  private final String usersUri;
  private final String clientId;
  private final String clientSecret;

  @Autowired
  KeycloakTrustedDirectory(
      ObjectProvider<RestClient.Builder> builders,
      ObjectMapper objectMapper,
      @Value("${uacrf.identity.directory.keycloak.issuer}") String issuerUri,
      @Value("${uacrf.identity.directory.keycloak.token-uri}") String tokenUri,
      @Value("${uacrf.identity.directory.keycloak.users-uri}") String usersUri,
      @Value("${uacrf.identity.directory.keycloak.client-id}") String clientId,
      @Value("${uacrf.identity.directory.keycloak.client-secret}") String clientSecret) {
    this(builders.getIfAvailable(RestClient::builder), objectMapper, issuerUri, tokenUri, usersUri, clientId, clientSecret);
  }

  KeycloakTrustedDirectory(
      RestClient.Builder builder,
      ObjectMapper objectMapper,
      String issuerUri,
      String tokenUri,
      String usersUri,
      String clientId,
      String clientSecret) {
    this.http = builder.build();
    this.objectMapper = objectMapper;
    this.issuerUri = issuerUri;
    this.tokenUri = tokenUri;
    this.usersUri = usersUri;
    this.clientId = clientId;
    this.clientSecret = clientSecret;
  }

  @Override
  public Optional<DirectoryPrincipal> find(String issuer, String subjectRef) {
    if (!issuerUri.equals(issuer)) {
      throw new UnavailableException("No directory connection exists for the provider issuer.", null);
    }
    try {
      String accessToken = serviceAccountToken();
      JsonNode user =
          http.get()
              .uri(usersUri + "/{id}", subjectRef)
              .headers(headers -> headers.setBearerAuth(accessToken))
              .exchange(
                  (request, response) -> {
                    if (response.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)
                        || response.getStatusCode().isSameCodeAs(HttpStatus.BAD_REQUEST)) {
                      return null;
                    }
                    if (!response.getStatusCode().is2xxSuccessful()) {
                      throw new UnavailableException(
                          "The directory returned " + response.getStatusCode().value() + ".", null);
                    }
                    return objectMapper.readTree(response.getBody());
                  });
      if (user == null || user.path("id").asString("").isBlank()) {
        return Optional.empty();
      }
      String username = user.path("username").asString("");
      String fullName =
          (user.path("firstName").asString("") + " " + user.path("lastName").asString("")).trim();
      String kind = username.startsWith("service-account-") ? "SYSTEM" : "HUMAN";
      return Optional.of(
          new DirectoryPrincipal(
              user.path("id").asString(),
              fullName.isBlank() ? username : fullName,
              kind,
              user.path("enabled").asBoolean(false)));
    } catch (RestClientException | tools.jackson.core.JacksonException error) {
      throw new UnavailableException("The directory could not be reached.", error);
    }
  }

  private String serviceAccountToken() {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "client_credentials");
    form.add("client_id", clientId);
    form.add("client_secret", clientSecret);
    JsonNode token =
        objectMapper.readTree(
            http.post()
                .uri(tokenUri)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String.class));
    String accessToken = token.path("access_token").asString("");
    if (accessToken.isBlank()) {
      throw new UnavailableException("The directory token response was invalid.", null);
    }
    return accessToken;
  }
}
