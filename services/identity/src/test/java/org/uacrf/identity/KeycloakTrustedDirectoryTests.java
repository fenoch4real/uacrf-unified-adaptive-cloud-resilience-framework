package org.uacrf.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class KeycloakTrustedDirectoryTests {
  private static final String ISSUER = "https://kc.test/realms/uacrf";
  private static final String TOKEN = ISSUER + "/protocol/openid-connect/token";
  private static final String USERS = "https://kc.test/admin/realms/uacrf/users";

  private final RestClient.Builder builder = RestClient.builder();
  private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
  private final KeycloakTrustedDirectory directory =
      new KeycloakTrustedDirectory(builder, JsonMapper.builder().build(), ISSUER, TOKEN, USERS, "dir-client", "secret");

  @Test
  void readsVerifiedUserWithServiceAccountToken() {
    expectToken();
    server
        .expect(requestTo(USERS + "/u-1"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("Authorization", "Bearer svc-token"))
        .andRespond(
            withSuccess(
                "{\"id\":\"u-1\",\"username\":\"ada\",\"firstName\":\"Ada\",\"lastName\":\"L\",\"enabled\":true}",
                MediaType.APPLICATION_JSON));

    var found = directory.find(ISSUER, "u-1").orElseThrow();

    assertThat(found).isEqualTo(new TrustedDirectory.DirectoryPrincipal("u-1", "Ada L", "HUMAN", true));
    server.verify();
  }

  @Test
  void treatsUnknownUsersAsAbsent() {
    expectToken();
    server.expect(requestTo(USERS + "/missing")).andRespond(withStatus(HttpStatus.NOT_FOUND));
    assertThat(directory.find(ISSUER, "missing")).isEmpty();
  }

  @Test
  void treatsServerFailuresAsUnavailable() {
    expectToken();
    server.expect(requestTo(USERS + "/boom")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
    assertThatThrownBy(() -> directory.find(ISSUER, "boom"))
        .isInstanceOf(TrustedDirectory.UnavailableException.class);
  }

  @Test
  void refusesIssuersWithoutAServerHeldConnection() {
    assertThatThrownBy(() -> directory.find("https://other.test/realms/x", "u-1"))
        .isInstanceOf(TrustedDirectory.UnavailableException.class);
  }

  private void expectToken() {
    server
        .expect(requestTo(TOKEN))
        .andRespond(withSuccess("{\"access_token\":\"svc-token\"}", MediaType.APPLICATION_JSON));
  }
}
