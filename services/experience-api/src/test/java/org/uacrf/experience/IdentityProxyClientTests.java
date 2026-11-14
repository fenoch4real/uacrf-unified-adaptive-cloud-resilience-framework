package org.uacrf.experience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class IdentityProxyClientTests {
  @Test
  void forwardsBearerAndContextToFixedIdentityEndpointAndPreservesResponse() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://edge.test");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(
            requestTo(
                "http://edge.test/v1/me/access"
                    + "?tenant_id=tenant-a&environment_id=environment-a"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer user-token"))
        .andRespond(
            withStatus(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"ACCESS_DENIED\"}"));

    IdentityProxyClient client = client(builder.build());

    var response =
        client.currentAccess("Bearer user-token", "tenant-a", "environment-a");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(response.getBody()).isEqualTo("{\"code\":\"ACCESS_DENIED\"}");
    server.verify();
  }

  @Test
  void forwardsContextCursorAndDisablesCachingForTheCurrentContextPage() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://edge.test");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(requestTo("http://edge.test/v1/me/contexts?limit=25&cursor=next-page"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer user-token"))
        .andRespond(
            withSuccess("{\"items\":[],\"next_cursor\":null}", MediaType.APPLICATION_JSON));

    IdentityProxyClient client = client(builder.build());

    var response = client.currentContexts("Bearer user-token", 25, "next-page");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    server.verify();
  }

  @Test
  void forwardsMembershipRevocationBodyAndIdempotencyKey() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://edge.test");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(
            requestTo(
                "http://edge.test/v1/memberships/membership-a:revoke"
                    + "?tenant_id=tenant-a&environment_id=environment-a"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer user-token"))
        .andExpect(header("Idempotency-Key", "request-a"))
        .andExpect(content().json("{\"expected_revision\":3,\"reason\":\"retire access\"}"))
        .andRespond(
            withSuccess(
                "{\"id\":\"membership-a\",\"revision\":4,\"result\":\"REVOKED\"}",
                MediaType.APPLICATION_JSON));

    IdentityProxyClient client = client(builder.build());

    var response =
        client.revokeMembership(
            "Bearer user-token",
            "membership-a",
            "tenant-a",
            "environment-a",
            "request-a",
            "{\"expected_revision\":3,\"reason\":\"retire access\"}");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    server.verify();
  }

  @Test
  void forwardsPrincipalStatusChangeBodyAndIdempotencyKey() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://edge.test");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(requestTo("http://edge.test/v1/principals/principal-a:set-status"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer user-token"))
        .andExpect(header("Idempotency-Key", "request-a"))
        .andExpect(content().json("{\"enabled\":false,\"expected_revision\":3}"))
        .andRespond(
            withSuccess(
                "{\"id\":\"principal-a\",\"revision\":4,"
                    + "\"result\":\"UPDATED\",\"runtime_effect\":\"NONE\"}",
                MediaType.APPLICATION_JSON));

    IdentityProxyClient client = client(builder.build());

    var response =
        client.setPrincipalStatus(
            "Bearer user-token",
            "principal-a",
            "request-a",
            "{\"enabled\":false,\"expected_revision\":3}");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    server.verify();
  }

  @Test
  void forwardsDelegationRequestOnlyToTheFixedIdentityEndpoint() {
    RestClient.Builder edgeBuilder = RestClient.builder().baseUrl("http://edge.test");
    RestClient.Builder identityBuilder = RestClient.builder().baseUrl("http://identity.test");
    MockRestServiceServer server = MockRestServiceServer.bindTo(identityBuilder).build();
    server
        .expect(requestTo("http://identity.test/internal/v1/delegations"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer user-token"))
        .andExpect(content().json("{\"audience\":\"S02\",\"tenant_id\":\"tenant-a\"}"))
        .andRespond(
            withSuccess(
                "{\"access_token\":\"delegated\",\"token_type\":\"Bearer\",\"expires_in\":30}",
                MediaType.APPLICATION_JSON));

    IdentityProxyClient client =
        new IdentityProxyClient(edgeBuilder.build(), identityBuilder.build());

    var response =
        client.createDelegation(
            "Bearer user-token", "{\"audience\":\"S02\",\"tenant_id\":\"tenant-a\"}");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains("\"access_token\":\"delegated\"");
    server.verify();
  }

  private static IdentityProxyClient client(RestClient restClient) {
    return new IdentityProxyClient(restClient, restClient);
  }
}
