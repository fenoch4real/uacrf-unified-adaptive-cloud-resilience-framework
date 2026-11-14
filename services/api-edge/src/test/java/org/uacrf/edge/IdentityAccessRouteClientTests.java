package org.uacrf.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class IdentityAccessRouteClientTests {
  @Test
  void routesCurrentAccessToFixedIdentityTargetAndPreservesTheOwnerResponse() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://identity.test");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(
            requestTo(
                "http://identity.test/v1/me/access"
                    + "?service_id=service-a&tenant_id=tenant-a&environment_id=environment-a"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer user-token"))
        .andRespond(
            withStatus(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"ACCESS_DENIED\"}"));

    IdentityAccessRouteClient client = new IdentityAccessRouteClient(builder.build());

    var response =
        client.currentAccess("Bearer user-token", "service-a", "tenant-a", "environment-a");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(response.getBody()).isEqualTo("{\"code\":\"ACCESS_DENIED\"}");
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    server.verify();
  }

  @Test
  void leavesAuthenticationDecisionsToIdentityWhenNoBearerIsPresent() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://identity.test");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(requestTo("http://identity.test/v1/me/access"))
        .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
        .andRespond(
            withStatus(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"UNAUTHENTICATED\"}"));

    IdentityAccessRouteClient client = new IdentityAccessRouteClient(builder.build());

    var response = client.currentAccess(null, null, null, null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    server.verify();
  }

  @Test
  void forwardsPrincipalPageCursorAndScopeToTheIdentityOwner() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://identity.test");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(
            requestTo(
                "http://identity.test/v1/principals"
                    + "?limit=25&cursor=next-page&tenant_id=tenant-a"
                    + "&environment_id=environment-a"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer user-token"))
        .andRespond(
            withStatus(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"items\":[],\"next_cursor\":null}"));

    IdentityAccessRouteClient client = new IdentityAccessRouteClient(builder.build());

    var response =
        client.principals("Bearer user-token", 25, "next-page", "tenant-a", "environment-a");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isEqualTo("{\"items\":[],\"next_cursor\":null}");
    server.verify();
  }

  @Test
  void forwardsMembershipRevocationWithIdempotencyAndContextToTheOwner() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://identity.test");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(
            requestTo(
                "http://identity.test/v1/memberships/membership-a:revoke"
                    + "?tenant_id=tenant-a&environment_id=environment-a"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer user-token"))
        .andExpect(header("Idempotency-Key", "request-a"))
        .andExpect(content().json("{\"expected_revision\":3,\"reason\":\"retire access\"}"))
        .andRespond(
            withStatus(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"id\":\"membership-a\",\"revision\":4,\"result\":\"REVOKED\"}"));

    IdentityAccessRouteClient client = new IdentityAccessRouteClient(builder.build());

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
  void forwardsMembershipCreationAndGrantDecisionsToTheIdentityOwner() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://identity.test");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(
            requestTo(
                "http://identity.test/v1/memberships"
                    + "?tenant_id=tenant-a&environment_id=environment-a"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer user-token"))
        .andExpect(header("Idempotency-Key", "request-a"))
        .andExpect(content().json("{\"authorization_ref\":\"decision-a\"}"))
        .andRespond(
            withStatus(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"result\":\"CREATED\"}"));
    server
        .expect(requestTo("http://identity.test/v1/grant-decisions"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer approver-token"))
        .andRespond(
            withStatus(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"result\":\"CREATED\"}"));

    IdentityAccessRouteClient client = new IdentityAccessRouteClient(builder.build());

    var membership =
        client.createMembership(
            "Bearer user-token",
            "tenant-a",
            "environment-a",
            "request-a",
            "{\"authorization_ref\":\"decision-a\"}");
    var decision =
        client.createGrantDecision("Bearer approver-token", null, null, "{\"id\":\"decision-a\"}");

    assertThat(membership.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(decision.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    server.verify();
  }

  @Test
  void forwardsPrincipalAdmissionToTheIdentityOwner() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://identity.test");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(
            requestTo(
                "http://identity.test/v1/principals"
                    + "?tenant_id=tenant-a&environment_id=environment-a"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer admin-token"))
        .andExpect(header("Idempotency-Key", "admit-a"))
        .andExpect(content().json("{\"directory_subject_ref\":\"dir-1\"}"))
        .andRespond(
            withStatus(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"result\":\"CREATED\"}"));

    var response =
        new IdentityAccessRouteClient(builder.build())
            .admitPrincipal(
                "Bearer admin-token",
                "tenant-a",
                "environment-a",
                "admit-a",
                "{\"directory_subject_ref\":\"dir-1\"}");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    server.verify();
  }

  @Test
  void forwardsPrincipalStatusChangeToTheIdentityOwner() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://identity.test");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(requestTo("http://identity.test/v1/principals/principal-a:set-status"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer user-token"))
        .andExpect(header("Idempotency-Key", "request-a"))
        .andExpect(content().json("{\"enabled\":false,\"expected_revision\":3}"))
        .andRespond(
            withStatus(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    "{\"id\":\"principal-a\",\"revision\":4,"
                        + "\"result\":\"UPDATED\",\"runtime_effect\":\"NONE\"}"));

    IdentityAccessRouteClient client = new IdentityAccessRouteClient(builder.build());

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
}
