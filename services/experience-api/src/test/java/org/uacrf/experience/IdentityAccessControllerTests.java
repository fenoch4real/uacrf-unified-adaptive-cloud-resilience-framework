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
