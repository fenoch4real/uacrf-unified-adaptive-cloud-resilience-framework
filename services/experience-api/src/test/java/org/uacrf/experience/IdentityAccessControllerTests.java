package org.uacrf.experience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;

class IdentityAccessControllerTests {
  private IdentityProxyClient identity;
  private IdentityAccessController controller;

  @BeforeEach
  void setUp() {
    identity = mock(IdentityProxyClient.class);
    controller = new IdentityAccessController(identity);
  }

  @Test
  void forwardsBearerAndContextAndPreservesTheIdentityResponse() {
    ResponseEntity<String> upstream =
        ResponseEntity.ok("{\"principal_id\":\"user-a\"}");
    when(identity.currentAccess("Bearer user-token", "tenant-a", "environment-a"))
        .thenReturn(upstream);

    ResponseEntity<String> response =
        controller.currentAccess("Bearer user-token", "tenant-a", "environment-a");

    assertThat(response).isSameAs(upstream);
    verify(identity).currentAccess("Bearer user-token", "tenant-a", "environment-a");
  }

  @Test
  void forwardsTheCurrentContextPageRequest() {
    ResponseEntity<String> upstream =
        ResponseEntity.ok("{\"items\":[],\"next_cursor\":null}");
    when(identity.currentContexts("Bearer user-token", 25, "next-page")).thenReturn(upstream);

    ResponseEntity<String> response =
        controller.currentContexts("Bearer user-token", 25, "next-page");

    assertThat(response).isSameAs(upstream);
    verify(identity).currentContexts("Bearer user-token", 25, "next-page");
  }

  @Test
  void forwardsGrantDecisionWithSelectedContext() {
    String requestBody = "{\"id\":\"decision-a\",\"principal_id\":\"principal-a\"}";
    ResponseEntity<String> upstream = ResponseEntity.status(HttpStatus.CREATED).body(
        "{\"id\":\"decision-a\",\"revision\":1,\"result\":\"CREATED\",\"runtime_effect\":\"NONE\"}");
    when(
            identity.createGrantDecision(
                "Bearer approver-token", "tenant-a", "environment-a", requestBody))
        .thenReturn(upstream);

    ResponseEntity<String> response =
        controller.createGrantDecision(
            "Bearer approver-token", "tenant-a", "environment-a", requestBody);

    assertThat(response).isSameAs(upstream);
    verify(identity)
        .createGrantDecision(
            "Bearer approver-token", "tenant-a", "environment-a", requestBody);
  }

  @Test
  void forwardsMembershipCreationWithContextAndIdempotencyKey() {
    String requestBody = "{\"id\":\"membership-a\",\"authorization_ref\":\"decision-a\"}";
    ResponseEntity<String> upstream =
        ResponseEntity.status(HttpStatus.CREATED).body("{\"id\":\"membership-a\"}");
    when(
            identity.createMembership(
                "Bearer manager-token",
                "tenant-a",
                "environment-a",
                "request-a",
                requestBody))
        .thenReturn(upstream);

    ResponseEntity<String> response =
        controller.createMembership(
            "Bearer manager-token",
            "tenant-a",
            "environment-a",
            "request-a",
            requestBody);

    assertThat(response).isSameAs(upstream);
    verify(identity)
        .createMembership(
            "Bearer manager-token",
            "tenant-a",
            "environment-a",
            "request-a",
            requestBody);
  }

  @Test
  void forwardsMembershipRevocationWithItsIdempotencyKey() {
    String requestBody = "{\"expected_revision\":3,\"reason\":\"retire access\"}";
    ResponseEntity<String> upstream = ResponseEntity.ok("{\"result\":\"REVOKED\"}");
    when(
            identity.revokeMembership(
                "Bearer user-token",
                "membership-a",
                "tenant-a",
                "environment-a",
                "request-a",
                requestBody))
        .thenReturn(upstream);

    ResponseEntity<String> response =
        controller.revokeMembership(
            "Bearer user-token",
            "membership-a",
            "tenant-a",
            "environment-a",
            "request-a",
            requestBody);

    assertThat(response).isSameAs(upstream);
    verify(identity)
        .revokeMembership(
            "Bearer user-token",
            "membership-a",
            "tenant-a",
            "environment-a",
            "request-a",
            requestBody);
  }

  @Test
  void forwardsPrincipalStatusChangeWithItsIdempotencyKey() {
    String requestBody = "{\"enabled\":false,\"expected_revision\":3}";
    ResponseEntity<String> upstream = ResponseEntity.ok("{\"result\":\"UPDATED\"}");
    when(
            identity.setPrincipalStatus(
                "Bearer user-token", "principal-a", "request-a", requestBody))
        .thenReturn(upstream);

    ResponseEntity<String> response =
        controller.setPrincipalStatus(
            "Bearer user-token", "principal-a", "request-a", requestBody);

    assertThat(response).isSameAs(upstream);
    verify(identity)
        .setPrincipalStatus("Bearer user-token", "principal-a", "request-a", requestBody);
  }

  @Test
  void forwardsPrincipalAdmissionWithContextAndIdempotencyKey() {
    String requestBody =
        "{\"id\":\"principal-a\",\"provider_id\":\"keycloak-local\","
            + "\"directory_subject_ref\":\"directory-subject-a\","
            + "\"reason\":\"Onboard approved directory user\",\"expected_revision\":0}";
    ResponseEntity<String> upstream = ResponseEntity.status(HttpStatus.CREATED).body(
        "{\"id\":\"principal-a\",\"revision\":1,\"result\":\"CREATED\",\"runtime_effect\":\"NONE\"}");
    when(
            identity.admitPrincipal(
                "Bearer user-token",
                "tenant-a",
                "environment-a",
                "request-a",
                requestBody))
        .thenReturn(upstream);

    ResponseEntity<String> response =
        controller.admitPrincipal(
            "Bearer user-token",
            "tenant-a",
            "environment-a",
            "request-a",
            requestBody);

    assertThat(response).isSameAs(upstream);
    verify(identity)
        .admitPrincipal(
            "Bearer user-token",
            "tenant-a",
            "environment-a",
            "request-a",
            requestBody);
  }

  @Test
  void forwardsDelegationRequestToIdentity() {
    String requestBody = "{\"audience\":\"S02\"}";
    ResponseEntity<String> upstream = ResponseEntity.ok("{\"expires_in\":30}");
    when(identity.createDelegation("Bearer user-token", requestBody)).thenReturn(upstream);

    ResponseEntity<String> response =
        controller.createDelegation("Bearer user-token", requestBody);

    assertThat(response).isSameAs(upstream);
    verify(identity).createDelegation("Bearer user-token", requestBody);
  }

  @Test
  void requiresABearerHeaderBeforeProxying() {
    ResponseEntity<String> response = controller.currentAccess(null, null, null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getHeaders().getFirst("WWW-Authenticate")).isEqualTo("Bearer");
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    verifyNoInteractions(identity);
  }

  @Test
  void reportsAnUnavailableIdentityServiceAsBadGateway() {
    doThrow(new ResourceAccessException("connection failed"))
        .when(identity)
        .currentAccess("Bearer user-token", null, null);

    ResponseEntity<String> response =
        controller.currentAccess("Bearer user-token", null, null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    assertThat(response.getBody()).contains("IDENTITY_UNAVAILABLE");
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
  }
}
