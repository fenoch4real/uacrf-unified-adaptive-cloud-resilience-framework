package org.uacrf.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Client;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;

@SpringBootTest
@AutoConfigureMockMvc
class WebApplicationTests {
  @Autowired private MockMvc mockMvc;
  @MockitoBean private IdentityAccessClient identity;
  @MockitoBean private CatalogClient catalog;
  @MockitoBean private ConnectionsClient connections;
  @MockitoBean private EvidenceClient evidence;
  @MockitoBean private ObjectivesClient objectives;

  @Test
  void offersAnOidcSignInWithoutRequiringAuthentication() throws Exception {
    mockMvc
        .perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("/oauth2/authorization/keycloak")))
        .andExpect(content().string(not(containsString("access_token"))));
  }

  @Test
  void startsAuthorizationCodeFlowWithPkce() throws Exception {
    MvcResult result =
        mockMvc
            .perform(get("/oauth2/authorization/keycloak"))
            .andExpect(status().is3xxRedirection())
            .andReturn();

    assertThat(result.getResponse().getRedirectedUrl())
        .contains(
            "client_id=uacrf-ux01",
            "response_type=code",
            "code_challenge=",
            "code_challenge_method=S256");
  }

  @Test
  void keepsPublicIssuerSeparateFromContainerBackchannelEndpoints() {
    String issuer = "http://localhost:8180/realms/uacrf";
    var registrations =
        new BrowserSecurityConfiguration()
            .clientRegistrationRepository(
                issuer,
                "uacrf-ux01",
                "http://localhost:8180/realms/uacrf/protocol/openid-connect/auth",
                "http://keycloak:8080/realms/uacrf/protocol/openid-connect/token",
                "http://keycloak:8080/realms/uacrf/protocol/openid-connect/certs",
                "http://keycloak:8080/realms/uacrf/protocol/openid-connect/userinfo");
    var registration = registrations.findByRegistrationId("keycloak");

    assertThat(registration.getProviderDetails().getIssuerUri()).isEqualTo(issuer);
    assertThat(registration.getScopes()).containsExactlyInAnyOrder("openid", "profile");
    assertThat(registration.getProviderDetails().getAuthorizationUri())
        .isEqualTo("http://localhost:8180/realms/uacrf/protocol/openid-connect/auth");
    assertThat(registration.getProviderDetails().getTokenUri())
        .isEqualTo("http://keycloak:8080/realms/uacrf/protocol/openid-connect/token");
    assertThat(registration.getProviderDetails().getJwkSetUri())
        .isEqualTo("http://keycloak:8080/realms/uacrf/protocol/openid-connect/certs");
    assertThat(registration.getProviderDetails().getUserInfoEndpoint().getUri())
        .isEqualTo("http://keycloak:8080/realms/uacrf/protocol/openid-connect/userinfo");
  }

  @Test
  void derivesBackchannelEndpointsFromIssuerWhenOverridesAreBlank() {
    String issuer = "http://localhost:8180/realms/uacrf";
    var registrations =
        new BrowserSecurityConfiguration()
            .clientRegistrationRepository(issuer, "uacrf-ux01", "", "", "", "");
    var provider = registrations.findByRegistrationId("keycloak").getProviderDetails();

    assertThat(provider.getIssuerUri()).isEqualTo(issuer);
    assertThat(provider.getAuthorizationUri())
        .isEqualTo("http://localhost:8180/realms/uacrf/protocol/openid-connect/auth");
    assertThat(provider.getTokenUri())
        .isEqualTo("http://localhost:8180/realms/uacrf/protocol/openid-connect/token");
    assertThat(provider.getJwkSetUri())
        .isEqualTo("http://localhost:8180/realms/uacrf/protocol/openid-connect/certs");
    assertThat(provider.getUserInfoEndpoint().getUri())
        .isEqualTo("http://localhost:8180/realms/uacrf/protocol/openid-connect/userinfo");
  }

  @Test
  void rendersAuthenticatedIdentityWithoutTreatingLoginAsAuthorization() throws Exception {
    mockMvc
        .perform(
            get("/")
                .with(
                    oidcLogin()
                        .idToken(
                            token ->
                                token
                                    .subject("local-admin-subject")
                                    .claim("name", "Local Administrator"))))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Local Administrator")))
        .andExpect(content().string(containsString("Access must be authorized separately by S01.")))
        .andExpect(content().string(containsString("name=\"_csrf\"")))
        .andExpect(content().string(not(containsString("access_token"))));
  }

  @Test
  void requiresCsrfForLocalSignOut() throws Exception {
    mockMvc.perform(post("/logout").with(oidcLogin())).andExpect(status().isForbidden());
    mockMvc
        .perform(post("/logout").with(oidcLogin()).with(csrf()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/"));
  }

  @Test
  void checksCurrentAccessThroughUX01WithTheSessionAuthorizedClient() throws Exception {
    when(identity.currentAccess(
            "test-access-token", "tenant-test", "environment-test"))
        .thenReturn(
            new IdentityAccessClient.MeAccess(
                "local-admin",
                "Local Administrator",
                "HUMAN",
                null,
                "GLOBAL_GRANTS_ONLY",
                List.of("identity.membership.read")));

    mockMvc
        .perform(
            get("/access")
                .with(browserLogin())
                .session(sessionWithSelectedContext()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("identity.membership.read")))
        .andExpect(content().string(containsString("Switch tenant or environment")))
        .andExpect(content().string(not(containsString("access_token"))));
    verify(identity)
        .currentAccess("test-access-token", "tenant-test", "environment-test");
  }

  @Test
  void reportsUX01UnavailabilityWithoutShowingStalePermissions() throws Exception {
    when(identity.currentAccess(
            "test-access-token", "tenant-test", "environment-test"))
        .thenThrow(new ResourceAccessException("S01 is not running"));

    mockMvc
        .perform(
            get("/access")
                .with(browserLogin())
                .session(sessionWithSelectedContext()))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    containsString("UX01 is unavailable; current permissions were not loaded.")))
        .andExpect(content().string(not(containsString("identity.membership.read"))));
  }

  @Test
  void listsPrincipalsForTheSelectedContextWithoutRenderingTokens() throws Exception {
    when(identity.listPrincipals("test-access-token", sessionContext(), null))
        .thenReturn(
            new IdentityAccessClient.PrincipalPage(
                List.of(
                    new IdentityAccessClient.Principal(
                        "principal-test",
                        "Test Principal",
                        "HUMAN",
                        true,
                        true,
                        2)),
                "principal-cursor"));
    when(identity.listIdentityProviders("test-access-token", sessionContext()))
        .thenReturn(
            new IdentityAccessClient.IdentityProviderList(
                List.of(
                    new IdentityAccessClient.IdentityProvider(
                        "keycloak-local", "Local Keycloak", "TRUSTED"),
                    new IdentityAccessClient.IdentityProvider(
                        "disabled-source", "Disabled source", "REVOKED"))));
    when(identity.currentAccess("test-access-token", "tenant-test", "environment-test"))
        .thenReturn(
            new IdentityAccessClient.MeAccess(
                "local-admin",
                "Local Administrator",
                "HUMAN",
                null,
                "GLOBAL_GRANTS_ONLY",
                List.of("identity.directory.read", "identity.principal.manage")));

    mockMvc
        .perform(
            get("/principals")
                .with(browserLogin())
                .session(sessionWithSelectedContext()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Test Principal")))
        .andExpect(content().string(containsString("Owner eligible")))
        .andExpect(content().string(containsString("Next page of principals")))
        .andExpect(content().string(containsString("Admit trusted directory principal")))
        .andExpect(content().string(containsString("Disable principal")))
        .andExpect(content().string(containsString("Reason for disabling this principal")))
        .andExpect(content().string(containsString("keycloak-local")))
        .andExpect(content().string(not(containsString("disabled-source"))))
        .andExpect(content().string(containsString("it does not grant memberships or capabilities")))
        .andExpect(content().string(containsString("role=\"region\"")))
        .andExpect(content().string(not(containsString("test-access-token"))));
    verify(identity).listPrincipals("test-access-token", sessionContext(), null);
    verify(identity).listIdentityProviders("test-access-token", sessionContext());
  }

  @Test
  void hidesPrincipalAdmissionFromDirectoryReadersWithoutManageCapability() throws Exception {
    when(identity.listPrincipals("test-access-token", sessionContext(), null))
        .thenReturn(new IdentityAccessClient.PrincipalPage(List.of(), null));
    when(identity.currentAccess("test-access-token", "tenant-test", "environment-test"))
        .thenReturn(
            new IdentityAccessClient.MeAccess(
                "local-approver",
                "Local Approver",
                "HUMAN",
                null,
                "GLOBAL_GRANTS_ONLY",
                List.of("identity.directory.read")));

    mockMvc
        .perform(
            get("/principals")
                .with(browserLogin())
                .session(sessionWithSelectedContext()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Principal directory")))
        .andExpect(content().string(not(containsString("Admit trusted directory principal"))))
        .andExpect(content().string(not(containsString("Disable principal"))))
        .andExpect(content().string(not(containsString("No trusted identity providers"))));

    verify(identity, never()).listIdentityProviders("test-access-token", sessionContext());
  }

  @Test
  void disablesAnEnabledPrincipalWithTheCurrentRevisionAndAuditReason() throws Exception {
    String principalId = "principal-test";
    String reason = "Disable compromised test identity.";
    String idempotencyKey = "00000000-0000-4000-8000-000000000203";
    when(
            identity.disablePrincipal(
                "test-access-token", principalId, 2, reason, idempotencyKey))
        .thenReturn(
            new IdentityAccessClient.PrincipalStatusReceipt(
                principalId, 3, "UPDATED", "NONE"));

    MvcResult result =
        mockMvc
            .perform(
                post("/principals/{principalId}:disable", principalId)
                    .with(browserLogin())
                    .with(csrf())
                    .session(sessionWithSelectedContext())
                    .param("expectedRevision", "2")
                    .param("reason", reason)
                    .param("idempotencyKey", idempotencyKey))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/principals"))
            .andReturn();

    assertThat(result.getFlashMap().get("principalMessage"))
        .asString()
        .contains(principalId)
        .contains("No memberships were removed");
    verify(identity).disablePrincipal("test-access-token", principalId, 2, reason, idempotencyKey);
  }

  @Test
  void validatesPrincipalDisableReasonBeforeCallingS01() throws Exception {
    when(identity.listPrincipals("test-access-token", sessionContext(), null))
        .thenReturn(
            new IdentityAccessClient.PrincipalPage(
                List.of(
                    new IdentityAccessClient.Principal(
                        "principal-test", "Test Principal", "HUMAN", true, false, 2)),
                null));
    when(identity.listIdentityProviders("test-access-token", sessionContext()))
        .thenReturn(new IdentityAccessClient.IdentityProviderList(List.of()));
    when(identity.currentAccess("test-access-token", "tenant-test", "environment-test"))
        .thenReturn(
            new IdentityAccessClient.MeAccess(
                "local-admin",
                "Local Administrator",
                "HUMAN",
                null,
                "GLOBAL_GRANTS_ONLY",
                List.of("identity.directory.read", "identity.principal.manage")));

    mockMvc
        .perform(
            post("/principals/{principalId}:disable", "principal-test")
                .with(browserLogin())
                .with(csrf())
                .session(sessionWithSelectedContext())
                .param("expectedRevision", "2")
                .param("reason", "")
                .param("idempotencyKey", "00000000-0000-4000-8000-000000000204"))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(containsString("Correct the disable reason and try again.")))
        .andExpect(content().string(containsString("principal-disable-reason-error")))
        .andExpect(content().string(containsString("aria-invalid=\"true\"")))
        .andExpect(content().string(containsString("autofocus=\"autofocus\"")));

    verify(identity, never())
        .disablePrincipal(
            "test-access-token",
            "principal-test",
            2,
            "",
            "00000000-0000-4000-8000-000000000204");
  }

  @Test
  void preservesPrincipalDisableRetryKeyWhenTheOutcomeIsUnconfirmed() throws Exception {
    String principalId = "principal-test";
    String reason = "Disable compromised test identity.";
    String idempotencyKey = "00000000-0000-4000-8000-000000000206";
    when(
            identity.disablePrincipal(
                "test-access-token", principalId, 2, reason, idempotencyKey))
        .thenThrow(new ResourceAccessException("connection timed out"));
    when(identity.listPrincipals("test-access-token", sessionContext(), null))
        .thenReturn(
            new IdentityAccessClient.PrincipalPage(
                List.of(
                    new IdentityAccessClient.Principal(
                        principalId, "Test Principal", "HUMAN", true, false, 2)),
                null));
    when(identity.listIdentityProviders("test-access-token", sessionContext()))
        .thenReturn(new IdentityAccessClient.IdentityProviderList(List.of()));
    when(identity.currentAccess("test-access-token", "tenant-test", "environment-test"))
        .thenReturn(
            new IdentityAccessClient.MeAccess(
                "local-admin",
                "Local Administrator",
                "HUMAN",
                null,
                "GLOBAL_GRANTS_ONLY",
                List.of("identity.directory.read", "identity.principal.manage")));

    mockMvc
        .perform(
            post("/principals/{principalId}:disable", principalId)
                .with(browserLogin())
                .with(csrf())
                .session(sessionWithSelectedContext())
                .param("expectedRevision", "2")
                .param("reason", reason)
                .param("idempotencyKey", idempotencyKey))
        .andExpect(status().isServiceUnavailable())
        .andExpect(
            content()
                .string(
                    containsString(
                        "Retry with the same reason and request key.")))
        .andExpect(content().string(containsString("name=\"idempotencyKey\"")))
        .andExpect(content().string(containsString(idempotencyKey)))
        .andExpect(content().string(containsString(reason)));

    verify(identity).disablePrincipal("test-access-token", principalId, 2, reason, idempotencyKey);
  }

  @Test
  void admitsTrustedDirectoryPrincipalWithoutGrantingMemberships() throws Exception {
    String principalId = "principal-new";
    when(
            identity.admitPrincipal(
                "test-access-token",
                sessionContext(),
                principalId,
                "keycloak-local",
                "directory-subject-new",
                "Approved directory onboarding",
                "00000000-0000-4000-8000-000000000202"))
        .thenReturn(
            new IdentityAccessClient.PrincipalAdmissionReceipt(
                principalId, 1, "CREATED", "NONE"));

    MvcResult result =
        mockMvc
            .perform(
                post("/principals")
                    .with(browserLogin())
                    .with(csrf())
                    .session(sessionWithSelectedContext())
                    .param("id", principalId)
                    .param("providerId", "keycloak-local")
                    .param("directorySubjectRef", "directory-subject-new")
                    .param("reason", "Approved directory onboarding")
                    .param("idempotencyKey", "00000000-0000-4000-8000-000000000202"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/principals"))
            .andReturn();

    assertThat(result.getFlashMap().get("principalMessage"))
        .asString()
        .contains("principal-new")
        .contains("This operation added no memberships or capabilities");
    verify(identity)
        .admitPrincipal(
            "test-access-token",
            sessionContext(),
            principalId,
            "keycloak-local",
            "directory-subject-new",
            "Approved directory onboarding",
            "00000000-0000-4000-8000-000000000202");
  }

  @Test
  void rendersAccessibleFieldErrorWhenDirectorySubjectIsBlank() throws Exception {
    when(identity.listPrincipals("test-access-token", sessionContext(), null))
        .thenReturn(new IdentityAccessClient.PrincipalPage(List.of(), null));
    when(identity.listIdentityProviders("test-access-token", sessionContext()))
        .thenReturn(
            new IdentityAccessClient.IdentityProviderList(
                List.of(new IdentityAccessClient.IdentityProvider(
                    "keycloak-local", "Local Keycloak", "TRUSTED"))));
    when(identity.currentAccess("test-access-token", "tenant-test", "environment-test"))
        .thenReturn(
            new IdentityAccessClient.MeAccess(
                "local-admin",
                "Local Administrator",
                "HUMAN",
                null,
                "GLOBAL_GRANTS_ONLY",
                List.of("identity.directory.read", "identity.principal.manage")));

    mockMvc
        .perform(
            post("/principals")
                .with(browserLogin())
                .with(csrf())
                .session(sessionWithSelectedContext())
                .param("id", "principal-new")
                .param("providerId", "keycloak-local")
                .param("directorySubjectRef", "")
                .param("reason", "Approved directory onboarding")
                .param("idempotencyKey", "00000000-0000-4000-8000-000000000202"))
        .andExpect(status().isBadRequest())
        .andExpect(
            content()
                .string(
                    containsString("Review the field errors and try again.")))
        .andExpect(content().string(containsString("aria-invalid=\"true\"")))
        .andExpect(
            content()
                .string(
                    containsString(
                        "aria-describedby=\"directory-subject-help directory-subject-error\"")))
        .andExpect(content().string(containsString("id=\"directory-subject-error\"")))
        .andExpect(content().string(containsString("must not be blank")));

    verify(identity, never())
        .admitPrincipal(
            "test-access-token",
            sessionContext(),
            "principal-new",
            "keycloak-local",
            "",
            "Approved directory onboarding",
            "00000000-0000-4000-8000-000000000202");
  }

  @Test
  void requiresCsrfForDirectoryPrincipalAdmission() throws Exception {
    mockMvc
        .perform(
            post("/principals")
                .with(browserLogin())
                .session(sessionWithSelectedContext())
                .param("id", "principal-new")
                .param("providerId", "keycloak-local")
                .param("directorySubjectRef", "directory-subject-new")
                .param("reason", "Approved directory onboarding")
                .param("idempotencyKey", "00000000-0000-4000-8000-000000000202"))
        .andExpect(status().isForbidden());

    verify(identity, never())
        .admitPrincipal(
            "test-access-token",
            sessionContext(),
            "principal-new",
            "keycloak-local",
            "directory-subject-new",
            "Approved directory onboarding",
            "00000000-0000-4000-8000-000000000202");
  }

  @Test
  void redirectsToContextSelectionBeforeReadingPrincipals() throws Exception {
    mockMvc
        .perform(get("/principals").with(browserLogin()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/contexts"));

    verify(identity, never())
        .listPrincipals("test-access-token", sessionContext(), null);
  }

  @Test
  void listsMembershipsForTheSelectedContextWithoutRenderingTokens() throws Exception {
    when(identity.listMemberships("test-access-token", sessionContext(), null))
        .thenReturn(
            new IdentityAccessClient.MembershipPage(
                List.of(
                    new IdentityAccessClient.Membership(
                        "00000000-0000-4000-8000-000000000101",
                        "principal-test",
                        "catalog.service.write",
                        "svc-test",
                        Instant.parse("2030-01-01T00:00:00Z"),
                        3)),
                "membership-cursor"));

    mockMvc
        .perform(
            get("/memberships")
                .with(browserLogin())
                .session(sessionWithSelectedContext()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("catalog.service.write")))
        .andExpect(content().string(containsString("principal-test")))
        .andExpect(content().string(containsString("Next page of memberships")))
        .andExpect(
            content()
                .string(
                    containsString(
                        "action=\"/memberships/00000000-0000-4000-8000-000000000101:revoke\"")))
        .andExpect(content().string(containsString("name=\"_csrf\"")))
        .andExpect(content().string(not(containsString("test-access-token"))));
    verify(identity).listMemberships("test-access-token", sessionContext(), null);
  }

  @Test
  void showsGrantDecisionAndMembershipControlsOnlyForTheirCurrentS01Capabilities()
      throws Exception {
    when(identity.currentAccess("test-access-token", "tenant-test", "environment-test"))
        .thenReturn(
            new IdentityAccessClient.MeAccess(
                "local-approver",
                "Approver",
                "HUMAN",
                null,
                null,
                List.of("identity.grant.approve")),
            new IdentityAccessClient.MeAccess(
                "local-admin",
                "Administrator",
                "HUMAN",
                null,
                null,
                List.of("identity.membership.manage")));
    when(identity.listMemberships("test-access-token", sessionContext(), null))
        .thenReturn(new IdentityAccessClient.MembershipPage(List.of(), null));

    mockMvc
        .perform(
            get("/memberships")
                .with(browserLogin())
                .session(sessionWithSelectedContext()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Record an independent grant decision")))
        .andExpect(content().string(not(containsString("Apply an approved membership"))));

    mockMvc
        .perform(
            get("/memberships")
                .with(browserLogin())
                .session(sessionWithSelectedContext()))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("Record an independent grant decision"))))
        .andExpect(content().string(containsString("Apply an approved membership")));
  }

  @Test
  void recordsGrantDecisionWithTheApproverSuppliedValues() throws Exception {
    String decisionId = "decision-approval-201";
    Instant expiresAt = Instant.parse("2030-01-01T00:00:00Z");
    when(identity.createGrantDecision(
            "test-access-token",
            sessionContext(),
            decisionId,
            "local-member",
            "catalog.journey.read",
            "*",
            expiresAt,
            "Approved read-only access"))
        .thenReturn(
            new IdentityAccessClient.GrantDecisionReceipt(
                decisionId, 1, "CREATED", "NONE"));

    mockMvc
        .perform(
            post("/memberships/grant-decisions")
                .with(browserLogin())
                .with(csrf())
                .session(sessionWithSelectedContext())
                .param("id", decisionId)
                .param("principalId", "local-member")
                .param("capability", "catalog.journey.read")
                .param("serviceScope", "*")
                .param("expiresAt", "2030-01-01T00:00:00Z")
                .param("reason", "Approved read-only access"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/memberships"));

    verify(identity)
        .createGrantDecision(
            "test-access-token",
            sessionContext(),
            decisionId,
            "local-member",
            "catalog.journey.read",
            "*",
            expiresAt,
            "Approved read-only access");
  }

  @Test
  void appliesMembershipWithTheApprovalReferenceAndIdempotencyKey() throws Exception {
    String membershipId = "00000000-0000-4000-8000-000000000221";
    String idempotencyKey = "00000000-0000-4000-8000-000000000222";
    Instant expiresAt = Instant.parse("2030-01-01T00:00:00Z");
    when(identity.createMembership(
            "test-access-token",
            sessionContext(),
            membershipId,
            "local-member",
            "catalog.journey.read",
            "*",
            expiresAt,
            "Approved read-only access",
            "decision-approval-201",
            idempotencyKey))
        .thenReturn(
            new IdentityAccessClient.MembershipMutationReceipt(
                membershipId, 1, "CREATED", "NONE"));

    mockMvc
        .perform(
            post("/memberships")
                .with(browserLogin())
                .with(csrf())
                .session(sessionWithSelectedContext())
                .param("id", membershipId)
                .param("idempotencyKey", idempotencyKey)
                .param("principalId", "local-member")
                .param("capability", "catalog.journey.read")
                .param("serviceScope", "*")
                .param("expiresAt", "2030-01-01T00:00:00Z")
                .param("reason", "Approved read-only access")
                .param("authorizationRef", "decision-approval-201"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/memberships"));

    verify(identity)
        .createMembership(
            "test-access-token",
            sessionContext(),
            membershipId,
            "local-member",
            "catalog.journey.read",
            "*",
            expiresAt,
            "Approved read-only access",
            "decision-approval-201",
            idempotencyKey);
  }

  @Test
  void rejectsMalformedMembershipExpiryAndAssociatesTheErrorWithItsField() throws Exception {
    when(identity.currentAccess("test-access-token", "tenant-test", "environment-test"))
        .thenReturn(
            new IdentityAccessClient.MeAccess(
                "local-approver",
                "Approver",
                "HUMAN",
                null,
                null,
                List.of("identity.grant.approve")));
    when(identity.listMemberships("test-access-token", sessionContext(), null))
        .thenReturn(new IdentityAccessClient.MembershipPage(List.of(), null));

    mockMvc
        .perform(
            post("/memberships/grant-decisions")
                .with(browserLogin())
                .with(csrf())
                .session(sessionWithSelectedContext())
                .param("id", "decision-approval-201")
                .param("principalId", "local-member")
                .param("capability", "catalog.journey.read")
                .param("serviceScope", "*")
                .param("expiresAt", "not-an-instant")
                .param("reason", "Approved read-only access"))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(containsString("aria-invalid=\"true\"")))
        .andExpect(content().string(containsString("Enter an ISO-8601 UTC timestamp ending in Z.")));

    verify(identity, never())
        .createGrantDecision(
            "test-access-token",
            sessionContext(),
            "decision-approval-201",
            "local-member",
            "catalog.journey.read",
            "*",
            null,
            "Approved read-only access");
  }

  @Test
  void requiresCsrfForMembershipRevocation() throws Exception {
    mockMvc
        .perform(
            post("/memberships/00000000-0000-4000-8000-000000000101:revoke")
                .with(browserLogin())
                .session(sessionWithSelectedContext())
                .param("expectedRevision", "3")
                .param("reason", "Remove temporary access")
                .param("idempotencyKey", "00000000-0000-4000-8000-000000000201"))
        .andExpect(status().isForbidden());

    verify(identity, never())
        .revokeMembership(
            "test-access-token",
            sessionContext(),
            "00000000-0000-4000-8000-000000000101",
            3,
            "Remove temporary access",
            "00000000-0000-4000-8000-000000000201");
  }

  @Test
  void revokesMembershipWithTheSubmittedRevisionReasonAndIdempotencyKey() throws Exception {
    String membershipId = "00000000-0000-4000-8000-000000000101";
    String idempotencyKey = "00000000-0000-4000-8000-000000000201";
    when(identity.revokeMembership(
            "test-access-token",
            sessionContext(),
            membershipId,
            3,
            "Remove temporary access",
            idempotencyKey))
        .thenReturn(
            new IdentityAccessClient.MembershipMutationReceipt(
                membershipId, 4, "REVOKED", "NONE"));

    mockMvc
        .perform(
            post("/memberships/" + membershipId + ":revoke")
                .with(browserLogin())
                .with(csrf())
                .session(sessionWithSelectedContext())
                .param("expectedRevision", "3")
                .param("reason", "Remove temporary access")
                .param("idempotencyKey", idempotencyKey))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/memberships"));

    verify(identity)
        .revokeMembership(
            "test-access-token",
            sessionContext(),
            membershipId,
            3,
            "Remove temporary access",
            idempotencyKey);
  }

  @Test
  void rejectsInvalidMembershipRevocationBeforeCallingS01() throws Exception {
    String membershipId = "00000000-0000-4000-8000-000000000101";
    when(identity.listMemberships("test-access-token", sessionContext(), null))
        .thenReturn(
            new IdentityAccessClient.MembershipPage(
                List.of(
                    new IdentityAccessClient.Membership(
                        membershipId,
                        "principal-test",
                        "catalog.service.write",
                        "svc-test",
                        Instant.parse("2030-01-01T00:00:00Z"),
                        3)),
                null));

    mockMvc
        .perform(
            post("/memberships/" + membershipId + ":revoke")
                .with(browserLogin())
                .with(csrf())
                .session(sessionWithSelectedContext())
                .param("expectedRevision", "3")
                .param("reason", " ")
                .param("idempotencyKey", "00000000-0000-4000-8000-000000000201"))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(containsString("Provide a valid revision, reason, and request key.")))
        .andExpect(content().string(containsString("aria-invalid=\"true\"")))
        .andExpect(
            content()
                .string(
                    containsString("Enter a non-whitespace reason of up to 2000 characters.")));

    verify(identity, never())
        .revokeMembership(
            "test-access-token",
            sessionContext(),
            membershipId,
            3,
            " ",
            "00000000-0000-4000-8000-000000000201");
  }

  @Test
  void redirectsToContextSelectionBeforeRequestingCatalogAccess() throws Exception {
    mockMvc
        .perform(get("/journeys").with(browserLogin()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/contexts"));

    verify(identity, never())
        .createDelegation(
            "test-access-token",
            "S02",
            null,
            List.of("catalog.journey.read"),
            "tenant-test",
            "environment-test");
  }

  @Test
  void listsConnectionsUsingContextBoundReadDelegation() throws Exception {
    when(identity.createDelegation(
            "test-access-token", "S03", null, List.of("connections.connection.read"),
            "tenant-test", "environment-test"))
        .thenReturn(new IdentityAccessClient.Delegation("s03-delegation", "Bearer", 30));
    when(connections.list("s03-delegation", null))
        .thenReturn(new ConnectionsClient.ConnectionPage(
            List.of(new ConnectionsClient.Connection("sample-estate", "Sample estate", "SAMPLE_LOCAL", "ACTIVE", null)),
            "next-page"));

    mockMvc.perform(get("/connections").with(browserLogin()).session(sessionWithSelectedContext()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Sample estate")))
        .andExpect(content().string(containsString("SAMPLE_LOCAL")))
        .andExpect(content().string(containsString("/connections/sample-estate/inventory")))
        .andExpect(content().string(containsString("/connections?cursor=next-page")))
        .andExpect(content().string(not(containsString("s03-delegation"))));
    verify(identity).createDelegation(
        "test-access-token", "S03", null, List.of("connections.connection.read"),
        "tenant-test", "environment-test");
  }

  @Test
  void listsEvidenceWithContextBoundReadDelegationAndEscapedProvenance() throws Exception {
    when(identity.createDelegation(
            "test-access-token", "S04", null, List.of("evidence.event.read"),
            "tenant-test", "environment-test"))
        .thenReturn(new IdentityAccessClient.Delegation("s04-delegation", "Bearer", 30));
    when(evidence.list("s04-delegation", "current", null))
        .thenReturn(new EvidenceClient.EvidencePage(List.of(new EvidenceClient.Evidence(
            "sample", "SAMPLE_LOCAL", "sample:<source>", 1L, "2026-11-12T09:05:00Z",
            "sample.metric", null, null, "a".repeat(64), 2L, "submitter",
            "SAMPLE_ONLY", 1L, null, null)), "next-page"));

    mockMvc.perform(get("/evidence").param("cursor", "current")
            .with(browserLogin()).session(sessionWithSelectedContext()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Synthetic sample; never qualified evidence.")))
        .andExpect(content().string(containsString("sample:&lt;source&gt;")))
        .andExpect(content().string(containsString("SAMPLE_ONLY")))
        .andExpect(content().string(containsString("/evidence?cursor=next-page")))
        .andExpect(content().string(not(containsString("s04-delegation"))));
    verify(identity).createDelegation(
        "test-access-token", "S04", null, List.of("evidence.event.read"),
        "tenant-test", "environment-test");
  }

  @Test
  void evidenceRequiresContextAndReportsDeniedOrMalformedReads() throws Exception {
    mockMvc.perform(get("/evidence").with(browserLogin()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/contexts"));
    verifyNoInteractions(evidence);

    when(identity.createDelegation(
            "test-access-token", "S04", null, List.of("evidence.event.read"),
            "tenant-test", "environment-test"))
        .thenThrow(new org.springframework.web.client.HttpClientErrorException(
            org.springframework.http.HttpStatus.FORBIDDEN))
        .thenReturn(new IdentityAccessClient.Delegation("s04-delegation", "Bearer", 30));
    mockMvc.perform(get("/evidence").with(browserLogin()).session(sessionWithSelectedContext()))
        .andExpect(status().isForbidden())
        .andExpect(content().string(containsString("Evidence access failed (HTTP 403)")))
        .andExpect(content().string(not(containsString("No evidence records are available"))));
    verifyNoInteractions(evidence);
    when(evidence.list("s04-delegation", null, null)).thenThrow(new IllegalStateException("invalid"));
    mockMvc.perform(get("/evidence").with(browserLogin()).session(sessionWithSelectedContext()))
        .andExpect(status().isBadGateway())
        .andExpect(content().string(containsString("returned an invalid response")))
        .andExpect(content().string(not(containsString("No evidence records are available"))));
  }

  @Test
  void preservesServiceScopeInEvidenceDelegationAndPagination() throws Exception {
    when(identity.createDelegation(
            "test-access-token", "S04", "service-a", List.of("evidence.event.read"),
            "tenant-test", "environment-test"))
        .thenReturn(new IdentityAccessClient.Delegation("scoped-delegation", "Bearer", 30));
    when(evidence.list("scoped-delegation", "current", "service-a"))
        .thenReturn(new EvidenceClient.EvidencePage(List.of(), "next-page"));
    mockMvc.perform(get("/evidence").param("serviceId", "service-a").param("cursor", "current")
            .with(browserLogin()).session(sessionWithSelectedContext()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("value=\"service-a\"")))
        .andExpect(content().string(containsString("/evidence?cursor=next-page&amp;serviceId=service-a")))
        .andExpect(content().string(not(containsString("scoped-delegation"))));
    verify(identity).createDelegation(
        "test-access-token", "S04", "service-a", List.of("evidence.event.read"),
        "tenant-test", "environment-test");
  }

  @Test
  void displaysObjectivesWithGlobalDelegationAndEscapedTitle() throws Exception {
    when(identity.createDelegation(
            "test-access-token", "S05", null, List.of("objectives.objective.read"),
            "tenant-test", "environment-test"))
        .thenReturn(new IdentityAccessClient.Delegation("s05-delegation", "Bearer", 30));
    when(objectives.list("s05-delegation", "current", "workload-a"))
        .thenReturn(new ObjectivesClient.ObjectivePage(List.of(new ObjectivesClient.Objective(
            "objective", "workload-a", "Latency <target>", "latency", "LTE",
            new java.math.BigDecimal("99.75"), "ms", 60, "LOCAL", null, null, null, null,
            "a".repeat(64), "DRAFT", 1L, "proposer", null, null, null)), "next"));
    mockMvc.perform(get("/objectives").param("workloadId", "workload-a").param("cursor", "current")
            .with(browserLogin()).session(sessionWithSelectedContext()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Latency &lt;target&gt;")))
        .andExpect(content().string(containsString("DRAFT")))
        .andExpect(content().string(containsString("not measured performance or proof of compliance")))
        .andExpect(content().string(containsString("/objectives?cursor=next&amp;workloadId=workload-a")))
        .andExpect(content().string(not(containsString("s05-delegation"))));
    verify(identity).createDelegation(
        "test-access-token", "S05", null, List.of("objectives.objective.read"),
        "tenant-test", "environment-test");
  }

  @Test
  void objectiveReadDenialAndMalformedPageAreVisible() throws Exception {
    mockMvc.perform(get("/objectives").with(browserLogin()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/contexts"));
    verifyNoInteractions(objectives);
    when(identity.createDelegation(
            "test-access-token", "S05", null, List.of("objectives.objective.read"),
            "tenant-test", "environment-test"))
        .thenThrow(new org.springframework.web.client.HttpClientErrorException(
            org.springframework.http.HttpStatus.FORBIDDEN))
        .thenReturn(new IdentityAccessClient.Delegation("s05-delegation", "Bearer", 30));
    mockMvc.perform(get("/objectives").with(browserLogin()).session(sessionWithSelectedContext()))
        .andExpect(status().isForbidden())
        .andExpect(content().string(containsString("Objective access failed (HTTP 403)")))
        .andExpect(content().string(not(containsString("No objectives are available"))));
    verifyNoInteractions(objectives);
    when(objectives.list("s05-delegation", null, null))
        .thenThrow(new IllegalStateException("invalid"));
    mockMvc.perform(get("/objectives").with(browserLogin()).session(sessionWithSelectedContext()))
        .andExpect(status().isBadGateway())
        .andExpect(content().string(containsString("returned an invalid response")))
        .andExpect(content().string(not(containsString("No objectives are available"))));
  }

  @Test
  void emptyObjectivePageIsNotReportedAsAnError() throws Exception {
    when(identity.createDelegation(
            "test-access-token", "S05", null, List.of("objectives.objective.read"),
            "tenant-test", "environment-test"))
        .thenReturn(new IdentityAccessClient.Delegation("s05-delegation", "Bearer", 30));
    when(objectives.list("s05-delegation", null, null))
        .thenReturn(new ObjectivesClient.ObjectivePage(List.of(), null));
    mockMvc.perform(get("/objectives").with(browserLogin()).session(sessionWithSelectedContext()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("No objectives are available on this page.")))
        .andExpect(content().string(not(containsString("Objective access failed"))));
  }

  @Test
  void showsSampleInventoryProvenanceAndUnknownLocation() throws Exception {
    when(identity.createDelegation(
            "test-access-token", "S03", null, List.of("connections.connection.read"),
            "tenant-test", "environment-test"))
        .thenReturn(new IdentityAccessClient.Delegation("s03-delegation", "Bearer", 30));
    when(connections.get("s03-delegation", "sample-estate"))
        .thenReturn(new ConnectionsClient.Connection("sample-estate", "Sample estate", "SAMPLE_LOCAL", "ACTIVE", null));
    when(connections.listCollectionStatuses("s03-delegation", "sample-estate", null))
        .thenReturn(new ConnectionsClient.CollectionStatusPage(List.of(), null));
    when(connections.listInventory("s03-delegation", "sample-estate", "current", "SAMPLE_LOCAL"))
        .thenReturn(new ConnectionsClient.InventoryPage(
            List.of(new ConnectionsClient.InventoryObservation(
                "inventory-observation.v1", "SAMPLE_LOCAL", "GCP", "asset-<script>",
                "compute.googleapis.com/Network", null, "obs-1", "sample:gcp/source",
                "gcp.assetinventory.assets", "abc", "2026-11-12T09:05:40Z")),
            "next-page"));

    mockMvc.perform(get("/connections/sample-estate/inventory").param("cursor", "current")
            .with(browserLogin()).session(sessionWithSelectedContext()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Synthetic sample")))
        .andExpect(content().string(containsString("GCP")))
        .andExpect(content().string(containsString("Unknown")))
        .andExpect(content().string(containsString("asset-&lt;script&gt;")))
        .andExpect(content().string(containsString("obs-1")))
        .andExpect(content().string(containsString("cursor=next-page")))
        .andExpect(content().string(not(containsString("s03-delegation"))));
  }

  @Test
  void showsRetainedMetricObservationPayloadsAsEscapedSampleData() throws Exception {
    when(identity.createDelegation(
            "test-access-token", "S03", null, List.of("connections.connection.read"),
            "tenant-test", "environment-test"))
        .thenReturn(new IdentityAccessClient.Delegation("s03-delegation", "Bearer", 30));
    when(connections.get("s03-delegation", "sample-estate"))
        .thenReturn(new ConnectionsClient.Connection(
            "sample-estate", "Sample estate", "SAMPLE_LOCAL", "ACTIVE", null));
    when(connections.listObservations("s03-delegation", "sample-estate", "current"))
        .thenReturn(new ConnectionsClient.ObservationPage(
            List.of(new ConnectionsClient.Observation(
                "obs-metric", "sample:aws/metric", 1, "2026-11-12T09:05:00Z",
                "aws.cloudwatch.metrics", "{\"metric\":\"<script>\"}", "a".repeat(64))),
            "next-page"));

    mockMvc.perform(get("/connections/sample-estate/observations").param("cursor", "current")
            .with(browserLogin()).session(sessionWithSelectedContext()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Retained observations")))
        .andExpect(content().string(containsString("aws.cloudwatch.metrics")))
        .andExpect(content().string(containsString("&lt;script&gt;")))
        .andExpect(content().string(containsString("cursor=next-page")))
        .andExpect(content().string(containsString("qualified evidence")))
        .andExpect(content().string(not(containsString("s03-delegation"))));
  }

  @Test
  void doesNotExposeRawObservationsForUnacceptedProviderConnections() throws Exception {
    when(identity.createDelegation(
            "test-access-token", "S03", null, List.of("connections.connection.read"),
            "tenant-test", "environment-test"))
        .thenReturn(new IdentityAccessClient.Delegation("s03-delegation", "Bearer", 30));
    when(connections.get("s03-delegation", "provider-connection"))
        .thenReturn(new ConnectionsClient.Connection(
            "provider-connection", "Provider connection", "AWS", "UNACCEPTED", "Not accepted"));

    mockMvc.perform(get("/connections/provider-connection/observations")
            .with(browserLogin()).session(sessionWithSelectedContext()))
        .andExpect(status().isNotFound())
        .andExpect(content().string(containsString("only shown for SAMPLE_LOCAL")));
    verify(connections, never()).listObservations(anyString(), anyString(), any());
  }

  @Test
  void showsCollectionStatusesWithIndependentPaginationAndNoFreshnessClaim() throws Exception {
    when(identity.createDelegation(
            "test-access-token", "S03", null, List.of("connections.connection.read"),
            "tenant-test", "environment-test"))
        .thenReturn(new IdentityAccessClient.Delegation("s03-delegation", "Bearer", 30));
    when(connections.get("s03-delegation", "sample-estate"))
        .thenReturn(new ConnectionsClient.Connection("sample-estate", "Sample estate", "AWS", "ACTIVE", null));
    when(connections.listCollectionStatuses("s03-delegation", "sample-estate", "status-current"))
        .thenReturn(new ConnectionsClient.CollectionStatusPage(
            List.of(
                new ConnectionsClient.CollectionStatus(
                    "binding-1", "sample-estate", "aws/us-east-1/config/inventory",
                    null, null, false, null, null),
                new ConnectionsClient.CollectionStatus(
                    "binding-2", "sample-estate", "aws/us-east-1/cloudwatch/sqs/orders",
                    "COLLECTED", "<script>detail</script>", false,
                    "2026-11-18T10:20:30Z", "2026-11-18T10:25:00Z")),
            "status-next"));
    when(connections.listInventory("s03-delegation", "sample-estate", "inventory-current", "AWS"))
        .thenReturn(new ConnectionsClient.InventoryPage(List.of(), "inventory-next"));

    mockMvc.perform(get("/connections/sample-estate/inventory")
            .param("cursor", "inventory-current").param("statusCursor", "status-current")
            .with(browserLogin()).session(sessionWithSelectedContext()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Source collection status")))
        .andExpect(content().string(containsString("aws/us-east-1/config/inventory")))
        .andExpect(content().string(containsString("Not yet run")))
        .andExpect(content().string(containsString("No details reported")))
        .andExpect(content().string(containsString("Not recorded")))
        .andExpect(content().string(containsString("Not scheduled")))
        .andExpect(content().string(containsString("COLLECTED")))
        .andExpect(content().string(containsString("&lt;script&gt;detail&lt;/script&gt;")))
        .andExpect(content().string(containsString("datetime=\"2026-11-18T10:20:30Z\"")))
        .andExpect(content().string(containsString("do not indicate freshness or staleness")))
        .andExpect(content().string(containsString("statusCursor=status-next")))
        .andExpect(content().string(containsString("cursor=inventory-current")))
        .andExpect(content().string(containsString("cursor=inventory-next")))
        .andExpect(content().string(containsString("statusCursor=status-current")))
        .andExpect(content().string(not(containsString("s03-delegation"))));

    verify(connections).listCollectionStatuses("s03-delegation", "sample-estate", "status-current");
    verify(connections).listInventory("s03-delegation", "sample-estate", "inventory-current", "AWS");
  }

  @Test
  void showsAnEmptyCollectionStatusState() throws Exception {
    when(identity.createDelegation(
            "test-access-token", "S03", null, List.of("connections.connection.read"),
            "tenant-test", "environment-test"))
        .thenReturn(new IdentityAccessClient.Delegation("s03-delegation", "Bearer", 30));
    when(connections.get("s03-delegation", "sample-estate"))
        .thenReturn(new ConnectionsClient.Connection("sample-estate", "Sample estate", "AWS", "ACTIVE", null));
    when(connections.listCollectionStatuses("s03-delegation", "sample-estate", null))
        .thenReturn(new ConnectionsClient.CollectionStatusPage(List.of(), null));
    when(connections.listInventory("s03-delegation", "sample-estate", null, "AWS"))
        .thenReturn(new ConnectionsClient.InventoryPage(List.of(), null));

    mockMvc.perform(get("/connections/sample-estate/inventory")
            .with(browserLogin()).session(sessionWithSelectedContext()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(
            "No source collection status rows are available for this connection.")));
  }

  @Test
  void reportsInvalidConnectionListWithoutShowingAnEmptyState() throws Exception {
    when(identity.createDelegation(
            "test-access-token", "S03", null, List.of("connections.connection.read"),
            "tenant-test", "environment-test"))
        .thenReturn(new IdentityAccessClient.Delegation("s03-delegation", "Bearer", 30));
    when(connections.list("s03-delegation", null))
        .thenThrow(new IllegalStateException("invalid page"));

    mockMvc.perform(get("/connections").with(browserLogin()).session(sessionWithSelectedContext()))
        .andExpect(status().isBadGateway())
        .andExpect(content().string(containsString("invalid response")))
        .andExpect(content().string(not(containsString("No connections are available."))));
  }

  @Test
  void reportsInvalidConnectionBeforeReadingItsInventory() throws Exception {
    when(identity.createDelegation(
            "test-access-token", "S03", null, List.of("connections.connection.read"),
            "tenant-test", "environment-test"))
        .thenReturn(new IdentityAccessClient.Delegation("s03-delegation", "Bearer", 30));
    when(connections.get("s03-delegation", "sample-estate"))
        .thenThrow(new IllegalStateException("invalid connection"));

    mockMvc.perform(get("/connections/sample-estate/inventory")
            .with(browserLogin()).session(sessionWithSelectedContext()))
        .andExpect(status().isBadGateway())
        .andExpect(content().string(containsString("invalid connection")));
    verify(connections, never()).listInventory(anyString(), anyString(), any(), anyString());
    verify(connections, never()).listCollectionStatuses(anyString(), anyString(), any());
  }

  @Test
  void reportsInvalidCollectionStatusAndStillDisplaysAvailableInventory() throws Exception {
    when(identity.createDelegation(
            "test-access-token", "S03", null, List.of("connections.connection.read"),
            "tenant-test", "environment-test"))
        .thenReturn(new IdentityAccessClient.Delegation("s03-delegation", "Bearer", 30));
    when(connections.get("s03-delegation", "sample-estate"))
        .thenReturn(new ConnectionsClient.Connection("sample-estate", "Sample estate", "AWS", "ACTIVE", null));
    when(connections.listCollectionStatuses("s03-delegation", "sample-estate", null))
        .thenThrow(new org.springframework.web.client.RestClientException("invalid status page"));
    when(connections.listInventory("s03-delegation", "sample-estate", null, "AWS"))
        .thenReturn(new ConnectionsClient.InventoryPage(List.of(), null));

    mockMvc.perform(get("/connections/sample-estate/inventory")
            .with(browserLogin()).session(sessionWithSelectedContext()))
        .andExpect(status().isBadGateway())
        .andExpect(content().string(containsString("invalid collection-status page")))
        .andExpect(content().string(not(containsString("No source collection status rows are available"))))
        .andExpect(content().string(containsString("No inventory observations are available")));
  }

  @Test
  void reportsInvalidInventoryWithoutShowingAnEmptyState() throws Exception {
    when(identity.createDelegation(
            "test-access-token", "S03", null, List.of("connections.connection.read"),
            "tenant-test", "environment-test"))
        .thenReturn(new IdentityAccessClient.Delegation("s03-delegation", "Bearer", 30));
    when(connections.get("s03-delegation", "sample-estate"))
        .thenReturn(new ConnectionsClient.Connection("sample-estate", "Sample estate", "AWS", "ACTIVE", null));
    when(connections.listCollectionStatuses("s03-delegation", "sample-estate", null))
        .thenReturn(new ConnectionsClient.CollectionStatusPage(List.of(), null));
    when(connections.listInventory("s03-delegation", "sample-estate", null, "AWS"))
        .thenThrow(new IllegalStateException("invalid inventory page"));

    mockMvc.perform(get("/connections/sample-estate/inventory")
            .with(browserLogin()).session(sessionWithSelectedContext()))
        .andExpect(status().isBadGateway())
        .andExpect(content().string(containsString("invalid inventory page")))
        .andExpect(content().string(not(containsString("No inventory observations are available"))))
        .andExpect(content().string(containsString("No source collection status rows are available")));
  }

  @Test
  void inventoryRequiresSelectedContextAndDoesNotReadWhenDelegationFails() throws Exception {
    mockMvc.perform(get("/connections/sample-estate/inventory").with(browserLogin()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/contexts"));
    when(identity.createDelegation(
            "test-access-token", "S03", null, List.of("connections.connection.read"),
            "tenant-test", "environment-test"))
        .thenThrow(new ResourceAccessException("identity unavailable"));
    mockMvc.perform(get("/connections/sample-estate/inventory")
            .with(browserLogin()).session(sessionWithSelectedContext()))
        .andExpect(status().isServiceUnavailable())
        .andExpect(content().string(containsString("inventory access was not authorized")));
    verifyNoInteractions(connections);
  }

  @Test
  void inventoryDenialDoesNotReachConnections() throws Exception {
    when(identity.createDelegation(
            "test-access-token", "S03", null, List.of("connections.connection.read"),
            "tenant-test", "environment-test"))
        .thenThrow(new org.springframework.web.client.HttpClientErrorException(org.springframework.http.HttpStatus.FORBIDDEN));
    mockMvc.perform(get("/connections/sample-estate/inventory")
            .with(browserLogin()).session(sessionWithSelectedContext()))
        .andExpect(status().isForbidden())
        .andExpect(content().string(containsString("Inventory access was not authorized (HTTP 403)")))
        .andExpect(content().string(not(containsString("Synthetic sample"))));
    verifyNoInteractions(connections);
  }

  @Test
  void sampleImportRequiresContextCsrfAndAnEligibleOwner() throws Exception {
    mockMvc.perform(get("/connections/sample-import").with(browserLogin()))
        .andExpect(redirectedUrl("/contexts"));
    mockMvc.perform(post("/connections/sample-import").with(browserLogin())
            .session(sessionWithSelectedContext()).param("ownerId", "owner-test"))
        .andExpect(status().isForbidden());

    when(identity.ownerCandidates("test-access-token", sessionContext(), "CONNECTION", null))
        .thenReturn(new IdentityAccessClient.OwnerPage(List.of(
            new IdentityAccessClient.OwnerCandidate("owner-test", "Owner <test>", "HUMAN", true, true, 1)), null));
    mockMvc.perform(get("/connections/sample-import").with(browserLogin()).session(sessionWithSelectedContext()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Owner &lt;test&gt;")))
        .andExpect(content().string(containsString("synthetic AWS")))
        .andExpect(content().string(containsString("name=\"_csrf\"")));
    mockMvc.perform(post("/connections/sample-import").with(browserLogin()).with(csrf())
            .session(sessionWithSelectedContext()).param("ownerId", "forged-owner"))
        .andExpect(status().isForbidden())
        .andExpect(content().string(containsString("Select an eligible human owner")));
    verifyNoInteractions(connections);
  }

  @Test
  void sampleImportStopsOnUncertaintyAndSafelyReplaysConfirmedObservations() throws Exception {
    when(identity.ownerCandidates("test-access-token", sessionContext(), "CONNECTION", null))
        .thenReturn(new IdentityAccessClient.OwnerPage(List.of(
            new IdentityAccessClient.OwnerCandidate("owner-test", "Owner", "HUMAN", true, true, 1)), null));
    when(identity.createDelegation("test-access-token", "S03", null,
        List.of("connections.connection.write", "connections.observation.write"),
        "tenant-test", "environment-test"))
        .thenReturn(new IdentityAccessClient.Delegation("sample-delegation", "Bearer", 30));
    var accepted = new java.util.HashSet<String>();
    var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
    when(connections.submitSample(anyString(), anyString(), any())).thenAnswer(invocation -> {
      SampleInventory.Observation observation = invocation.getArgument(2);
      if (accepted.size() == 2 && !interrupted.getAndSet(true)) {
        throw new ResourceAccessException("simulated interruption");
      }
      String result = accepted.add(observation.id()) ? "ACCEPTED" : "DUPLICATE";
      return new ConnectionsClient.IngestResult(observation.id(), result, result, observation.sequence());
    });
    MockHttpSession session = sessionWithSelectedContext();
    mockMvc.perform(post("/connections/sample-import").with(browserLogin()).with(csrf())
            .session(session).param("ownerId", "owner-test"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(content().string(containsString("the last operation may have completed")))
        .andExpect(content().string(containsString("Confirmed observations in this attempt:")));
    mockMvc.perform(post("/connections/sample-import").with(browserLogin()).with(csrf())
            .session(session).param("ownerId", "owner-test"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Sample import completed:")))
        .andExpect(content().string(containsString("122")))
        .andExpect(content().string(containsString("already accepted on a prior attempt")));
    assertThat(accepted).hasSize(122);
    String id = SampleInventory.connectionId(sessionContext(), "local-admin-subject", "owner-test");
    verify(connections, times(2)).registerSample("sample-delegation", "sample-register-" + id,
        new SampleInventory.Connection(id, "Sample multi-cloud estate (AWS, Azure, Google Cloud shapes)",
            "SAMPLE_LOCAL", "sample/readonly-collector", "owner-test", 0));
    verify(connections, times(20)).bindSample(anyString(), anyString(), anyString(), any());
    verify(connections, times(125)).submitSample(anyString(), anyString(), any());
  }

  @Test
  void sampleImportStopsImmediatelyWhenAnObservationIsQuarantined() throws Exception {
    when(identity.ownerCandidates("test-access-token", sessionContext(), "CONNECTION", null))
        .thenReturn(new IdentityAccessClient.OwnerPage(List.of(
            new IdentityAccessClient.OwnerCandidate("owner-test", "Owner", "HUMAN", true, true, 1)), null));
    when(identity.createDelegation("test-access-token", "S03", null,
        List.of("connections.connection.write", "connections.observation.write"),
        "tenant-test", "environment-test"))
        .thenReturn(new IdentityAccessClient.Delegation("sample-delegation", "Bearer", 30));
    when(connections.submitSample(anyString(), anyString(), any())).thenAnswer(invocation -> {
      SampleInventory.Observation observation = invocation.getArgument(2);
      return new ConnectionsClient.IngestResult(observation.id(), "QUARANTINED", "REPLAY_CONFLICT", observation.sequence());
    });
    mockMvc.perform(post("/connections/sample-import").with(browserLogin()).with(csrf())
            .session(sessionWithSelectedContext()).param("ownerId", "owner-test"))
        .andExpect(status().isConflict())
        .andExpect(content().string(containsString("S03 quarantined")))
        .andExpect(content().string(not(containsString("Sample import completed"))));
    verify(connections, times(1)).submitSample(anyString(), anyString(), any());
  }

  @Test
  void loadsCustomerJourneysWithAServerHeldContextBoundDelegation() throws Exception {
    when(identity.createDelegation(
            "test-access-token",
            "S02",
            null,
            List.of("catalog.journey.read"),
            "tenant-test",
            "environment-test"))
        .thenReturn(new IdentityAccessClient.Delegation("s02-delegation", "Bearer", 30));
    when(catalog.listJourneys("s02-delegation", "journey-cursor"))
        .thenReturn(
            new CatalogClient.JourneyPage(
                List.of(
                    new CatalogClient.Journey(
                        "journey-test",
                        "Resilience review",
                        "owner-test",
                        "Review resilience controls",
                        "DRAFT",
                        1)),
                "next-cursor"));

    mockMvc
        .perform(
            get("/journeys")
                .with(browserLogin())
                .param("cursor", "journey-cursor")
                .session(sessionWithSelectedContext()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Resilience review")))
        .andExpect(content().string(containsString("/journeys?cursor=next-cursor")))
        .andExpect(content().string(not(containsString("s02-delegation"))));

    verify(identity)
        .createDelegation(
            "test-access-token",
            "S02",
            null,
            List.of("catalog.journey.read"),
            "tenant-test",
            "environment-test");
    verify(catalog).listJourneys("s02-delegation", "journey-cursor");
  }

  @Test
  void reportsS01UnavailabilityBeforeAccessingTheCatalog() throws Exception {
    when(identity.createDelegation(
            "test-access-token",
            "S02",
            null,
            List.of("catalog.journey.read"),
            "tenant-test",
            "environment-test"))
        .thenThrow(new ResourceAccessException("S01 is not running"));

    mockMvc
        .perform(
            get("/journeys")
                .with(browserLogin())
                .session(sessionWithSelectedContext()))
        .andExpect(status().isServiceUnavailable())
        .andExpect(
            content()
                .string(
                    containsString("UX01 is unavailable; journey access was not authorized.")));

    verify(catalog, never()).listJourneys("s02-delegation", null);
  }

  @Test
  void loadsJourneyStepsAndCapabilitiesThroughTheScopedDelegation() throws Exception {
    when(identity.createDelegation(
            "test-access-token",
            "S02",
            null,
            List.of("catalog.journey.read"),
            "tenant-test",
            "environment-test"))
        .thenReturn(new IdentityAccessClient.Delegation("s02-delegation", "Bearer", 30));
    when(catalog.getJourney("s02-delegation", "journey-test"))
        .thenReturn(
            new CatalogClient.JourneyDetails(
                "journey-test",
                "Resilience review",
                "owner-test",
                "Review resilience controls",
                "DRAFT",
                1,
                List.of(
                    new CatalogClient.Step(
                        "step-test", 1, "Map dependencies", "Identify critical paths", 1)),
                List.of(
                    new CatalogClient.Capability(
                        "link-test", "SERVICE", "service-test", "step-test", "HIGH", 1))));

    mockMvc
        .perform(
            get("/journeys/journey-test")
                .with(browserLogin())
                .session(sessionWithSelectedContext()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Identify critical paths")))
        .andExpect(content().string(containsString("service-test")))
        .andExpect(content().string(containsString("Link an agent workload")))
        .andExpect(content().string(not(containsString("s02-delegation"))));

    verify(catalog).getJourney("s02-delegation", "journey-test");
  }

  @Test
  void rendersDraftJourneyFormWithOwnersReturnedByS01() throws Exception {
    when(identity.ownerCandidates(
            "test-access-token", sessionContext(), "JOURNEY", null))
        .thenReturn(
            new IdentityAccessClient.OwnerPage(
                List.of(
                    new IdentityAccessClient.OwnerCandidate(
                        "owner-test", "Test Owner", "HUMAN", true, true, 1)),
                "owner-cursor"));

    mockMvc
        .perform(
            get("/journeys/new")
                .with(browserLogin())
                .session(sessionWithSelectedContext()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Create a draft customer journey")))
        .andExpect(content().string(containsString("Test Owner (owner-test)")))
        .andExpect(content().string(containsString("name=\"_csrf\"")))
        .andExpect(content().string(containsString("More eligible owners")))
        .andExpect(content().string(not(containsString("test-access-token"))));

    verify(identity)
        .ownerCandidates("test-access-token", sessionContext(), "JOURNEY", null);
  }

  @Test
  void createsDraftJourneyOnlyAfterRevalidatingOwnerAndDelegatingWrite() throws Exception {
    String idempotencyKey = "123e4567-e89b-42d3-a456-426614174000";
    String journeyId = "cmd_owner-test_" + idempotencyKey;
    BrowserContext context = sessionContext();
    CatalogClient.JourneyRegistration registration =
        new CatalogClient.JourneyRegistration(
            journeyId,
            "Resilience review",
            "owner-test",
            "Review resilience controls",
            "DRAFT");
    when(identity.ownerCandidates("test-access-token", context, "JOURNEY", null))
        .thenReturn(
            new IdentityAccessClient.OwnerPage(
                List.of(
                    new IdentityAccessClient.OwnerCandidate(
                        "owner-test", "Test Owner", "HUMAN", true, true, 1)),
                null));
    when(identity.createDelegation(
            "test-access-token",
            "S02",
            null,
            List.of("catalog.journey.write"),
            "tenant-test",
            "environment-test"))
        .thenReturn(new IdentityAccessClient.Delegation("s02-write-delegation", "Bearer", 30));
    when(catalog.registerJourney("s02-write-delegation", idempotencyKey, registration))
        .thenReturn(
            new CatalogClient.MutationReceipt(
                journeyId, 1, "CUSTOMER_JOURNEY", "CREATED", "NONE"));

    mockMvc
        .perform(
            post("/journeys")
                .with(browserLogin())
                .with(csrf())
                .session(sessionWithSelectedContext())
                .param("name", "Resilience review")
                .param("purpose", "Review resilience controls")
                .param("ownerId", "owner-test")
                .param("idempotencyKey", idempotencyKey)
                .param("ownerCursor", "")
                .param("ownerCursorHistory", ""))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/journeys/" + journeyId));

    verify(identity).ownerCandidates("test-access-token", context, "JOURNEY", null);
    verify(identity)
        .createDelegation(
            "test-access-token",
            "S02",
            null,
            List.of("catalog.journey.write"),
            "tenant-test",
            "environment-test");
    verify(catalog).registerJourney("s02-write-delegation", idempotencyKey, registration);
  }

  @Test
  void rejectsJourneyOwnerNotReturnedAsEligibleByS01() throws Exception {
    when(identity.ownerCandidates(
            "test-access-token", sessionContext(), "JOURNEY", null))
        .thenReturn(
            new IdentityAccessClient.OwnerPage(
                List.of(
                    new IdentityAccessClient.OwnerCandidate(
                        "owner-test", "Test Owner", "HUMAN", true, true, 1)),
                null));

    mockMvc
        .perform(
            post("/journeys")
                .with(browserLogin())
                .with(csrf())
                .session(sessionWithSelectedContext())
                .param("name", "Resilience review")
                .param("purpose", "Review resilience controls")
                .param("ownerId", "owner-other")
                .param("idempotencyKey", "123e4567-e89b-42d3-a456-426614174000")
                .param("ownerCursor", "")
                .param("ownerCursorHistory", ""))
        .andExpect(status().isForbidden())
        .andExpect(
            content()
                .string(
                    containsString(
                        "Select an active, eligible human owner from the current S01 owner list.")));

    verify(identity, never())
        .createDelegation(
            "test-access-token",
            "S02",
            null,
            List.of("catalog.journey.write"),
            "tenant-test",
            "environment-test");
    verify(catalog, never())
        .registerJourney(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  void validatesJourneyFieldsOnTheServerBeforeRequestingWriteAuthority() throws Exception {
    when(identity.ownerCandidates(
            "test-access-token", sessionContext(), "JOURNEY", null))
        .thenReturn(
            new IdentityAccessClient.OwnerPage(
                List.of(
                    new IdentityAccessClient.OwnerCandidate(
                        "owner-test", "Test Owner", "HUMAN", true, true, 1)),
                null));

    mockMvc
        .perform(
            post("/journeys")
                .with(browserLogin())
                .with(csrf())
                .session(sessionWithSelectedContext())
                .param("name", "")
                .param("purpose", "Review resilience controls")
                .param("ownerId", "owner-test")
                .param("idempotencyKey", "123e4567-e89b-42d3-a456-426614174000")
                .param("ownerCursor", "")
                .param("ownerCursorHistory", ""))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(containsString("name: must not be blank")))
        .andExpect(content().string(containsString("aria-invalid=\"true\"")));

    verify(identity, never())
        .createDelegation(
            "test-access-token",
            "S02",
            null,
            List.of("catalog.journey.write"),
            "tenant-test",
            "environment-test");
    verify(catalog, never())
        .registerJourney(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  void pagesJourneyOwnerChoicesWithoutLosingDraftFields() throws Exception {
    BrowserContext context = sessionContext();
    when(identity.ownerCandidates("test-access-token", context, "JOURNEY", null))
        .thenReturn(
            new IdentityAccessClient.OwnerPage(
                List.of(
                    new IdentityAccessClient.OwnerCandidate(
                        "owner-first", "First Owner", "HUMAN", true, true, 1)),
                "owner-cursor"));
    when(identity.ownerCandidates("test-access-token", context, "JOURNEY", "owner-cursor"))
        .thenReturn(
            new IdentityAccessClient.OwnerPage(
                List.of(
                    new IdentityAccessClient.OwnerCandidate(
                        "owner-next", "Next Owner", "HUMAN", true, true, 1)),
                null));

    mockMvc
        .perform(
            post("/journeys/new/owners")
                .with(browserLogin())
                .with(csrf())
                .session(sessionWithSelectedContext())
                .param("name", "Keep this name")
                .param("purpose", "Keep this purpose")
                .param("ownerId", "")
                .param("idempotencyKey", "123e4567-e89b-42d3-a456-426614174000")
                .param("ownerCursor", "")
                .param("ownerCursorHistory", "")
                .param("ownerPageAction", "next"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Next Owner (owner-next)")))
        .andExpect(content().string(containsString("Keep this name")))
        .andExpect(content().string(containsString("Keep this purpose")))
        .andExpect(content().string(containsString("value=\"owner-cursor\"")));
  }

  @Test
  void rendersServiceRegistrationFormWithS01OwnersAndCurrentJourney() throws Exception {
    when(identity.createDelegation(
            "test-access-token",
            "S02",
            null,
            List.of("catalog.journey.read"),
            "tenant-test",
            "environment-test"))
        .thenReturn(new IdentityAccessClient.Delegation("s02-read", "Bearer", 30));
    when(catalog.getJourney("s02-read", "journey-test"))
        .thenReturn(
            new CatalogClient.JourneyDetails(
                "journey-test",
                "Resilience review",
                "owner-test",
                "Review resilience controls",
                "DRAFT",
                1,
                List.of(),
                List.of()));
    when(identity.ownerCandidates(
            "test-access-token", sessionContext(), "SERVICE", null))
        .thenReturn(
            new IdentityAccessClient.OwnerPage(
                List.of(
                    new IdentityAccessClient.OwnerCandidate(
                        "owner-test", "Test Owner", "HUMAN", true, true, 1)),
                "owner-cursor"));

    mockMvc
        .perform(
            get("/journeys/journey-test/services/new")
                .with(browserLogin())
                .session(sessionWithSelectedContext()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Resilience review")))
        .andExpect(content().string(containsString("Test Owner (owner-test)")))
        .andExpect(content().string(containsString("More eligible owners")))
        .andExpect(content().string(containsString("name=\"_csrf\"")))
        .andExpect(content().string(not(containsString("s02-read"))));

    verify(identity)
        .ownerCandidates("test-access-token", sessionContext(), "SERVICE", null);
  }

  @Test
  void registersServiceWithValidatedOwnerAndSeparateWriteDelegation() throws Exception {
    String idempotencyKey = "123e4567-e89b-42d3-a456-426614174000";
    String serviceId = "cmd_owner-test_" + idempotencyKey;
    BrowserContext context = sessionContext();
    CatalogClient.ServiceRegistration registration =
        new CatalogClient.ServiceRegistration(
            serviceId, "Recovery API", "owner-test", "Supports recovery checks", 0, 4, "DRAFT");
    when(identity.ownerCandidates("test-access-token", context, "SERVICE", null))
        .thenReturn(
            new IdentityAccessClient.OwnerPage(
                List.of(
                    new IdentityAccessClient.OwnerCandidate(
                        "owner-test", "Test Owner", "HUMAN", true, true, 1)),
                null));
    when(identity.createDelegation(
            "test-access-token",
            "S02",
            null,
            List.of("catalog.journey.read"),
            "tenant-test",
            "environment-test"))
        .thenReturn(new IdentityAccessClient.Delegation("s02-read", "Bearer", 30));
    when(catalog.getJourney("s02-read", "journey-test"))
        .thenReturn(
            new CatalogClient.JourneyDetails(
                "journey-test",
                "Resilience review",
                "owner-test",
                "Review resilience controls",
                "DRAFT",
                1,
                List.of(),
                List.of()));
    when(identity.createDelegation(
            "test-access-token",
            "S02",
            null,
            List.of("catalog.service.write"),
            "tenant-test",
            "environment-test"))
        .thenReturn(new IdentityAccessClient.Delegation("s02-service-write", "Bearer", 30));
    when(catalog.registerService("s02-service-write", idempotencyKey, registration))
        .thenReturn(new CatalogClient.MutationReceipt(serviceId, 1, "SERVICE", "CREATED", "NONE"));

    mockMvc
        .perform(
            post("/journeys/journey-test/services")
                .with(browserLogin())
                .with(csrf())
                .session(sessionWithSelectedContext())
                .param("name", "Recovery API")
                .param("reason", "Supports recovery checks")
                .param("criticality", "4")
                .param("ownerId", "owner-test")
                .param("idempotencyKey", idempotencyKey)
                .param("ownerCursor", "")
                .param("ownerCursorHistory", ""))
        .andExpect(status().is3xxRedirection())
        .andExpect(
            redirectedUrl(
                "/journeys/journey-test/services/" + serviceId + "/link"));

    verify(identity).ownerCandidates("test-access-token", context, "SERVICE", null);
    verify(identity)
        .createDelegation(
            "test-access-token",
            "S02",
            null,
            List.of("catalog.service.write"),
            "tenant-test",
            "environment-test");
    verify(catalog).registerService("s02-service-write", idempotencyKey, registration);
  }

  @Test
  void rendersServiceLinkFormWithOnlyStepsFromTheSelectedJourney() throws Exception {
    String serviceId = "cmd_owner-test_123e4567-e89b-42d3-a456-426614174000";
    when(identity.createDelegation(
            "test-access-token",
            "S02",
            null,
            List.of("catalog.journey.read"),
            "tenant-test",
            "environment-test"))
        .thenReturn(new IdentityAccessClient.Delegation("s02-read", "Bearer", 30));
    when(catalog.getJourney("s02-read", "journey-test"))
        .thenReturn(
            new CatalogClient.JourneyDetails(
                "journey-test",
                "Resilience review",
                "owner-test",
                "Review resilience controls",
                "DRAFT",
                1,
                List.of(
                    new CatalogClient.Step(
                        "step-test", 1, "Validate recovery", "A recovery check passes.", 1)),
                List.of()));

    mockMvc
        .perform(
            get("/journeys/journey-test/services/" + serviceId + "/link")
                .with(browserLogin())
                .session(sessionWithSelectedContext()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Resilience review")))
        .andExpect(content().string(containsString("1. Validate recovery")))
        .andExpect(content().string(containsString(serviceId)))
        .andExpect(content().string(not(containsString("s02-read"))));
  }

  @Test
  void rendersStandaloneAgentRegistrationWithScopedOwnerSelection() throws Exception {
    when(identity.ownerCandidates(
            "test-access-token", sessionContext(), "AGENT", null, null))
        .thenReturn(
            new IdentityAccessClient.OwnerPage(
                List.of(
                    new IdentityAccessClient.OwnerCandidate(
                        "owner-test", "Test Owner", "HUMAN", true, true, 1)),
                null));

    mockMvc
        .perform(
            get("/agents/new")
                .with(browserLogin())
                .session(sessionWithSelectedContext()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("This agent will be standalone")))
        .andExpect(content().string(containsString("Test Owner (owner-test)")))
        .andExpect(content().string(containsString("Runtime principal ID")))
        .andExpect(content().string(containsString("name=\"_csrf\"")))
        .andExpect(content().string(not(containsString("access_token"))));

    verify(identity).ownerCandidates("test-access-token", sessionContext(), "AGENT", null, null);
  }

  @Test
  void rendersServiceBoundAgentRegistrationWithTheSelectedServiceOwnerScope() throws Exception {
    when(identity.ownerCandidates(
            "test-access-token", sessionContext(), "AGENT", "svc-agent", null))
        .thenReturn(
            new IdentityAccessClient.OwnerPage(
                List.of(
                    new IdentityAccessClient.OwnerCandidate(
                        "owner-test", "Test Owner", "HUMAN", true, true, 1)),
                null));

    mockMvc
        .perform(
            get("/services/svc-agent/agents/new")
                .with(browserLogin())
                .session(sessionWithSelectedContext()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Service-bound agent")))
        .andExpect(content().string(containsString("svc-agent")))
        .andExpect(content().string(containsString("Test Owner (owner-test)")))
        .andExpect(content().string(not(containsString("access_token"))));

    verify(identity)
        .ownerCandidates("test-access-token", sessionContext(), "AGENT", "svc-agent", null);
  }

  @Test
  void listsAgentWorkloadsUsingOnlyTheScopedReadDelegation() throws Exception {
    BrowserContext context = sessionContext();
    List<CatalogClient.AgentWorkload> agents =
        List.of(
            new CatalogClient.AgentWorkload(
                "agent-standalone",
                null,
                "Standalone helper",
                "SINGLE_AGENT",
                "owner-test",
                "runtime-standalone",
                "Review evidence",
                1),
            new CatalogClient.AgentWorkload(
                "agent-service",
                "svc-agent",
                "Recovery helper",
                "MULTI_AGENT",
                "owner-test",
                "runtime-service",
                "Coordinate recovery checks",
                2));
    when(identity.createDelegation(
            "test-access-token",
            "S02",
            null,
            List.of("catalog.agent.read"),
            context.tenantId(),
            context.environmentId()))
        .thenReturn(new IdentityAccessClient.Delegation("s02-agent-read", "Bearer", 30));
    when(catalog.listAgents("s02-agent-read", "cursor-first"))
        .thenReturn(new CatalogClient.AgentPage(agents, "cursor-next"));

    mockMvc
        .perform(
            get("/agents")
                .with(browserLogin())
                .param("cursor", "cursor-first")
                .session(sessionWithSelectedContext()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Agent workloads")))
        .andExpect(content().string(containsString("Standalone helper")))
        .andExpect(content().string(containsString("runtime-standalone")))
        .andExpect(content().string(containsString("Recovery helper")))
        .andExpect(content().string(containsString("svc-agent")))
        .andExpect(content().string(containsString("Next agent workloads")))
        .andExpect(content().string(containsString("cursor=cursor-next")))
        .andExpect(content().string(not(containsString("s02-agent-read"))));

    verify(identity)
        .createDelegation(
            "test-access-token",
            "S02",
            null,
            List.of("catalog.agent.read"),
            context.tenantId(),
            context.environmentId());
    verify(catalog).listAgents("s02-agent-read", "cursor-first");
  }

  @Test
  void reportsAgentWorkloadReadFailuresWithoutReturningAFalseEmptySuccess() throws Exception {
    BrowserContext context = sessionContext();
    when(identity.createDelegation(
            "test-access-token",
            "S02",
            null,
            List.of("catalog.agent.read"),
            context.tenantId(),
            context.environmentId()))
        .thenReturn(new IdentityAccessClient.Delegation("s02-agent-read", "Bearer", 30));
    when(catalog.listAgents("s02-agent-read", null))
        .thenThrow(new ResourceAccessException("S02 is unavailable"));

    mockMvc
        .perform(
            get("/agents")
                .with(browserLogin())
                .session(sessionWithSelectedContext()))
        .andExpect(status().isServiceUnavailable())
        .andExpect(content().string(containsString("S02 is unavailable")))
        .andExpect(content().string(not(containsString("No agent workloads are available."))));
  }

  @Test
  void registersStandaloneAgentWithoutBroadeningItsDelegation() throws Exception {
    String idempotencyKey = "123e4567-e89b-42d3-a456-426614174000";
    BrowserContext context = sessionContext();
    String agentId = "cmd_owner-test_" + idempotencyKey;
    CatalogClient.AgentRegistration registration =
        new CatalogClient.AgentRegistration(
            agentId, null, "Audit helper", "SINGLE_AGENT", "owner-test", "runtime-test", "Review evidence");
    when(identity.ownerCandidates("test-access-token", context, "AGENT", null, null))
        .thenReturn(
            new IdentityAccessClient.OwnerPage(
                List.of(
                    new IdentityAccessClient.OwnerCandidate(
                        "owner-test", "Test Owner", "HUMAN", true, true, 1)),
                null));
    when(identity.createDelegation(
            "test-access-token",
            "S02",
            null,
            List.of("catalog.agent.write"),
            "tenant-test",
            "environment-test"))
        .thenReturn(new IdentityAccessClient.Delegation("s02-agent-write", "Bearer", 30));
    when(catalog.registerAgent("s02-agent-write", idempotencyKey, registration))
        .thenReturn(
            new CatalogClient.MutationReceipt(
                agentId, 1, "AGENT_WORKLOAD", "CREATED", "NONE"));

    mockMvc
        .perform(
            post("/agents")
                .with(browserLogin())
                .with(csrf())
                .session(sessionWithSelectedContext())
                .param("name", "Audit helper")
                .param("kind", "SINGLE_AGENT")
                .param("ownerId", "owner-test")
                .param("runtimePrincipalId", "runtime-test")
                .param("purpose", "Review evidence")
                .param("idempotencyKey", idempotencyKey)
                .param("ownerCursor", "")
                .param("ownerCursorHistory", ""))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/agents/new"));

    verify(identity)
        .createDelegation(
            "test-access-token",
            "S02",
            null,
            List.of("catalog.agent.write"),
            "tenant-test",
            "environment-test");
    verify(catalog).registerAgent("s02-agent-write", idempotencyKey, registration);
  }

  @Test
  void registersServiceBoundAgentWithMatchingServiceScope() throws Exception {
    String idempotencyKey = "123e4567-e89b-42d3-a456-426614174001";
    BrowserContext context = sessionContext();
    String agentId = "cmd_owner-test_" + idempotencyKey;
    CatalogClient.AgentRegistration registration =
        new CatalogClient.AgentRegistration(
            agentId,
            "svc-agent",
            "Recovery helper",
            "MULTI_AGENT",
            "owner-test",
            "runtime-test",
            "Coordinate recovery checks");
    when(identity.ownerCandidates(
            "test-access-token", context, "AGENT", "svc-agent", null))
        .thenReturn(
            new IdentityAccessClient.OwnerPage(
                List.of(
                    new IdentityAccessClient.OwnerCandidate(
                        "owner-test", "Test Owner", "HUMAN", true, true, 1)),
                null));
    when(identity.createDelegation(
            "test-access-token",
            "S02",
            "svc-agent",
            List.of("catalog.agent.write"),
            "tenant-test",
            "environment-test"))
        .thenReturn(new IdentityAccessClient.Delegation("s02-agent-write", "Bearer", 30));
    when(catalog.registerAgent("s02-agent-write", idempotencyKey, registration))
        .thenReturn(
            new CatalogClient.MutationReceipt(
                agentId, 1, "AGENT_WORKLOAD", "CREATED", "NONE"));

    mockMvc
        .perform(
            post("/services/svc-agent/agents")
                .with(browserLogin())
                .with(csrf())
                .session(sessionWithSelectedContext())
                .param("name", "Recovery helper")
                .param("kind", "MULTI_AGENT")
                .param("ownerId", "owner-test")
                .param("runtimePrincipalId", "runtime-test")
                .param("purpose", "Coordinate recovery checks")
                .param("idempotencyKey", idempotencyKey)
                .param("ownerCursor", "")
                .param("ownerCursorHistory", ""))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/services/svc-agent/agents/new"));

    verify(identity)
        .ownerCandidates("test-access-token", context, "AGENT", "svc-agent", null);
    verify(identity)
        .createDelegation(
            "test-access-token",
            "S02",
            "svc-agent",
            List.of("catalog.agent.write"),
            "tenant-test",
            "environment-test");
    verify(catalog).registerAgent("s02-agent-write", idempotencyKey, registration);
  }

  @Test
  void rejectsAgentRegistrationWhenOwnerIsNotEligibleInTheCurrentS01Page() throws Exception {
    when(identity.ownerCandidates(
            "test-access-token", sessionContext(), "AGENT", "svc-agent", null))
        .thenReturn(
            new IdentityAccessClient.OwnerPage(
                List.of(
                    new IdentityAccessClient.OwnerCandidate(
                        "owner-test", "Test Owner", "HUMAN", false, true, 1)),
                null));

    mockMvc
        .perform(
            post("/services/svc-agent/agents")
                .with(browserLogin())
                .with(csrf())
                .session(sessionWithSelectedContext())
                .param("name", "Recovery helper")
                .param("kind", "MULTI_AGENT")
                .param("ownerId", "owner-test")
                .param("runtimePrincipalId", "runtime-test")
                .param("purpose", "Coordinate recovery checks")
                .param("idempotencyKey", "123e4567-e89b-42d3-a456-426614174002")
                .param("ownerCursor", "")
                .param("ownerCursorHistory", ""))
        .andExpect(status().isForbidden())
        .andExpect(
            content()
                .string(
                    containsString(
                        "Select an active, eligible human owner from the current S01 owner list.")));

    verify(identity, never())
        .createDelegation(
            "test-access-token",
            "S02",
            "svc-agent",
            List.of("catalog.agent.write"),
            "tenant-test",
            "environment-test");
    verify(catalog, never())
        .registerAgent(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  void rejectsIneligibleServiceOwnerBeforeRequestingWriteAuthority() throws Exception {
    when(identity.ownerCandidates(
            "test-access-token", sessionContext(), "SERVICE", null))
        .thenReturn(
            new IdentityAccessClient.OwnerPage(
                List.of(
                    new IdentityAccessClient.OwnerCandidate(
                        "owner-test", "Test Owner", "HUMAN", false, true, 1)),
                null));
    when(identity.createDelegation(
            "test-access-token",
            "S02",
            null,
            List.of("catalog.journey.read"),
            "tenant-test",
            "environment-test"))
        .thenReturn(new IdentityAccessClient.Delegation("s02-read", "Bearer", 30));
    when(catalog.getJourney("s02-read", "journey-test"))
        .thenReturn(
            new CatalogClient.JourneyDetails(
                "journey-test",
                "Resilience review",
                "owner-test",
                "Review resilience controls",
                "DRAFT",
                1,
                List.of(),
                List.of()));

    mockMvc
        .perform(
            post("/journeys/journey-test/services")
                .with(browserLogin())
                .with(csrf())
                .session(sessionWithSelectedContext())
                .param("name", "Recovery API")
                .param("reason", "Supports recovery checks")
                .param("criticality", "4")
                .param("ownerId", "owner-test")
                .param("idempotencyKey", "123e4567-e89b-42d3-a456-426614174000")
                .param("ownerCursor", "")
                .param("ownerCursorHistory", ""))
        .andExpect(status().isForbidden())
        .andExpect(
            content()
                .string(
                    containsString(
                        "Select an active, eligible human owner from the current S01 owner list.")));

    verify(identity, never())
        .createDelegation(
            "test-access-token",
            "S02",
            null,
            List.of("catalog.service.write"),
            "tenant-test",
            "environment-test");
    verify(catalog, never())
        .registerService(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  void validatesSelectedStepAndLinksServiceWithSeparateJourneyWriteDelegation() throws Exception {
    String serviceId = "cmd_owner-test_123e4567-e89b-42d3-a456-426614174000";
    String idempotencyKey = "123e4567-e89b-42d3-a456-426614174001";
    BrowserContext context = sessionContext();
    CatalogClient.CapabilityLink link =
        new CatalogClient.CapabilityLink(
            "link_" + idempotencyKey, "SERVICE", serviceId, "step-test", "REQUIRED");
    when(identity.createDelegation(
            "test-access-token",
            "S02",
            null,
            List.of("catalog.journey.read"),
            "tenant-test",
            "environment-test"))
        .thenReturn(new IdentityAccessClient.Delegation("s02-read", "Bearer", 30));
    when(catalog.getJourney("s02-read", "journey-test"))
        .thenReturn(
            new CatalogClient.JourneyDetails(
                "journey-test",
                "Resilience review",
                "owner-test",
                "Review resilience controls",
                "DRAFT",
                1,
                List.of(
                    new CatalogClient.Step(
                        "step-test", 1, "Validate recovery", "A recovery check passes.", 1)),
                List.of()));
    when(identity.createDelegation(
            "test-access-token",
            "S02",
            null,
            List.of("catalog.journey.write"),
            "tenant-test",
            "environment-test"))
        .thenReturn(new IdentityAccessClient.Delegation("s02-journey-write", "Bearer", 30));
    when(catalog.linkCapability("s02-journey-write", "journey-test", idempotencyKey, link))
        .thenReturn(
            new CatalogClient.MutationReceipt(
                link.id(), 1, "JOURNEY_CAPABILITY", "CREATED", "NONE"));

    mockMvc
        .perform(
            post("/journeys/journey-test/services/" + serviceId + "/link")
                .with(browserLogin())
                .with(csrf())
                .session(sessionWithSelectedContext())
                .param("stepId", "step-test")
                .param("criticality", "REQUIRED")
                .param("idempotencyKey", idempotencyKey))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/journeys/journey-test"));

    verify(identity)
        .createDelegation(
            "test-access-token",
            "S02",
            null,
            List.of("catalog.journey.write"),
            "tenant-test",
            "environment-test");
    verify(catalog).linkCapability("s02-journey-write", "journey-test", idempotencyKey, link);
  }

  @Test
  void rendersAgentLinkFormWithOnlyStepsFromTheSelectedJourney() throws Exception {
    when(identity.createDelegation(
            "test-access-token",
            "S02",
            null,
            List.of("catalog.journey.read"),
            "tenant-test",
            "environment-test"))
        .thenReturn(new IdentityAccessClient.Delegation("s02-read", "Bearer", 30));
    when(catalog.getJourney("s02-read", "journey-test"))
        .thenReturn(
            new CatalogClient.JourneyDetails(
                "journey-test",
                "Resilience review",
                "owner-test",
                "Review resilience controls",
                "DRAFT",
                1,
                List.of(
                    new CatalogClient.Step(
                        "step-test", 1, "Validate recovery", "A recovery check passes.", 1)),
                List.of()));

    mockMvc
        .perform(
            get("/journeys/journey-test/agents/link")
                .with(browserLogin())
                .session(sessionWithSelectedContext()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Link agent to ")))
        .andExpect(content().string(containsString("Resilience review")))
        .andExpect(content().string(containsString("Agent workload ID (required)")))
        .andExpect(content().string(containsString("1. Validate recovery")))
        .andExpect(content().string(containsString("name=\"_csrf\"")))
        .andExpect(content().string(not(containsString("s02-read"))));
  }

  @Test
  void linksAgentWithSeparateJourneyDelegationAndIdempotencyKey() throws Exception {
    String idempotencyKey = "123e4567-e89b-42d3-a456-426614174002";
    CatalogClient.CapabilityLink link =
        new CatalogClient.CapabilityLink(
            "link_" + idempotencyKey, "AGENT", "agent-test", "step-test", "REQUIRED");
    when(identity.createDelegation(
            "test-access-token",
            "S02",
            null,
            List.of("catalog.journey.read"),
            "tenant-test",
            "environment-test"))
        .thenReturn(new IdentityAccessClient.Delegation("s02-read", "Bearer", 30));
    when(catalog.getJourney("s02-read", "journey-test"))
        .thenReturn(
            new CatalogClient.JourneyDetails(
                "journey-test",
                "Resilience review",
                "owner-test",
                "Review resilience controls",
                "DRAFT",
                1,
                List.of(
                    new CatalogClient.Step(
                        "step-test", 1, "Validate recovery", "A recovery check passes.", 1)),
                List.of()));
    when(identity.createDelegation(
            "test-access-token",
            "S02",
            null,
            List.of("catalog.journey.write"),
            "tenant-test",
            "environment-test"))
        .thenReturn(new IdentityAccessClient.Delegation("s02-journey-write", "Bearer", 30));
    when(catalog.linkCapability("s02-journey-write", "journey-test", idempotencyKey, link))
        .thenReturn(
            new CatalogClient.MutationReceipt(
                link.id(), 1, "JOURNEY_CAPABILITY", "CREATED", "NONE"));

    mockMvc
        .perform(
            post("/journeys/journey-test/agents/link")
                .with(browserLogin())
                .with(csrf())
                .session(sessionWithSelectedContext())
                .param("agentId", "agent-test")
                .param("stepId", "step-test")
                .param("criticality", "REQUIRED")
                .param("idempotencyKey", idempotencyKey))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/journeys/journey-test"));

    verify(identity)
        .createDelegation(
            "test-access-token",
            "S02",
            null,
            List.of("catalog.journey.write"),
            "tenant-test",
            "environment-test");
    verify(catalog).linkCapability("s02-journey-write", "journey-test", idempotencyKey, link);
  }

  @Test
  void refusesToLinkAgentToStepOutsideTheCurrentJourney() throws Exception {
    when(identity.createDelegation(
            "test-access-token",
            "S02",
            null,
            List.of("catalog.journey.read"),
            "tenant-test",
            "environment-test"))
        .thenReturn(new IdentityAccessClient.Delegation("s02-read", "Bearer", 30));
    when(catalog.getJourney("s02-read", "journey-test"))
        .thenReturn(
            new CatalogClient.JourneyDetails(
                "journey-test",
                "Resilience review",
                "owner-test",
                "Review resilience controls",
                "DRAFT",
                1,
                List.of(
                    new CatalogClient.Step(
                        "step-test", 1, "Validate recovery", "A recovery check passes.", 1)),
                List.of()));

    mockMvc
        .perform(
            post("/journeys/journey-test/agents/link")
                .with(browserLogin())
                .with(csrf())
                .session(sessionWithSelectedContext())
                .param("agentId", "agent-test")
                .param("stepId", "step-other")
                .param("criticality", "REQUIRED")
                .param("idempotencyKey", "123e4567-e89b-42d3-a456-426614174002"))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(containsString("Choose a step from this journey.")));

    verify(identity, never())
        .createDelegation(
            "test-access-token",
            "S02",
            null,
            List.of("catalog.journey.write"),
            "tenant-test",
            "environment-test");
    verify(catalog, never())
        .linkCapability(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  void refusesToLinkServiceToStepOutsideTheCurrentJourney() throws Exception {
    when(identity.createDelegation(
            "test-access-token",
            "S02",
            null,
            List.of("catalog.journey.read"),
            "tenant-test",
            "environment-test"))
        .thenReturn(new IdentityAccessClient.Delegation("s02-read", "Bearer", 30));
    when(catalog.getJourney("s02-read", "journey-test"))
        .thenReturn(
            new CatalogClient.JourneyDetails(
                "journey-test",
                "Resilience review",
                "owner-test",
                "Review resilience controls",
                "DRAFT",
                1,
                List.of(
                    new CatalogClient.Step(
                        "step-test", 1, "Validate recovery", "A recovery check passes.", 1)),
                List.of()));

    mockMvc
        .perform(
            post("/journeys/journey-test/services/service-test/link")
                .with(browserLogin())
                .with(csrf())
                .session(sessionWithSelectedContext())
                .param("stepId", "step-other")
                .param("criticality", "REQUIRED")
                .param("idempotencyKey", "123e4567-e89b-42d3-a456-426614174001"))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(containsString("Choose a step from this journey.")));

    verify(identity, never())
        .createDelegation(
            "test-access-token",
            "S02",
            null,
            List.of("catalog.journey.write"),
            "tenant-test",
            "environment-test");
    verify(catalog, never())
        .linkCapability(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  void offersTheNextStepPositionFromTheCurrentJourney() throws Exception {
    when(identity.createDelegation(
            "test-access-token",
            "S02",
            null,
            List.of("catalog.journey.read"),
            "tenant-test",
            "environment-test"))
        .thenReturn(new IdentityAccessClient.Delegation("s02-read", "Bearer", 30));
    when(catalog.getJourney("s02-read", "journey-test"))
        .thenReturn(
            new CatalogClient.JourneyDetails(
                "journey-test",
                "Resilience review",
                "owner-test",
                "Review resilience controls",
                "DRAFT",
                1,
                List.of(
                    new CatalogClient.Step("step-a", 1, "First", "Outcome one", 1),
                    new CatalogClient.Step("step-c", 3, "Third", "Outcome three", 1)),
                List.of()));

    mockMvc
        .perform(
            get("/journeys/journey-test/steps/new")
                .with(browserLogin())
                .session(sessionWithSelectedContext()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Add a step to ")))
        .andExpect(content().string(containsString("Resilience review")))
        .andExpect(content().string(containsString("name=\"position\" value=\"4\"")))
        .andExpect(content().string(containsString("name=\"_csrf\"")))
        .andExpect(content().string(not(containsString("s02-read"))));

    verify(catalog).getJourney("s02-read", "journey-test");
  }

  @Test
  void createsStepThroughAWriteDelegationAndStableCommandId() throws Exception {
    String idempotencyKey = "123e4567-e89b-42d3-a456-426614174000";
    String stepId = "cmd_owner-test_" + idempotencyKey;
    when(identity.createDelegation(
            "test-access-token",
            "S02",
            null,
            List.of("catalog.journey.read"),
            "tenant-test",
            "environment-test"))
        .thenReturn(new IdentityAccessClient.Delegation("s02-read", "Bearer", 30));
    when(catalog.getJourney("s02-read", "journey-test"))
        .thenReturn(
            new CatalogClient.JourneyDetails(
                "journey-test",
                "Resilience review",
                "owner-test",
                "Review resilience controls",
                "DRAFT",
                1,
                List.of(new CatalogClient.Step("step-a", 3, "First", "Outcome one", 1)),
                List.of()));
    when(identity.createDelegation(
            "test-access-token",
            "S02",
            null,
            List.of("catalog.journey.write"),
            "tenant-test",
            "environment-test"))
        .thenReturn(new IdentityAccessClient.Delegation("s02-write", "Bearer", 30));
    when(catalog.registerStep(
            "s02-write",
            "journey-test",
            idempotencyKey,
            new CatalogClient.StepRegistration(
                stepId, 4, "Validate recovery", "A recovery check has passed.")))
        .thenReturn(
            new CatalogClient.MutationReceipt(stepId, 1, "JOURNEY_STEP", "CREATED", "NONE"));

    mockMvc
        .perform(
            post("/journeys/journey-test/steps")
                .with(browserLogin())
                .with(csrf())
                .session(sessionWithSelectedContext())
                .param("position", "4")
                .param("name", "Validate recovery")
                .param("intendedOutcome", "A recovery check has passed.")
                .param("idempotencyKey", idempotencyKey))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/journeys/journey-test"));

    verify(catalog)
        .registerStep(
            "s02-write",
            "journey-test",
            idempotencyKey,
            new CatalogClient.StepRegistration(
                stepId, 4, "Validate recovery", "A recovery check has passed."));
  }

  @Test
  void storesOnlyAContextReturnedByS01InTheServerSession() throws Exception {
    IdentityAccessClient.CurrentContext context =
        new IdentityAccessClient.CurrentContext(
            "tenant-test", "Test tenant", "environment-test", "Test environment");
    when(identity.currentContexts("test-access-token", null))
        .thenReturn(new IdentityAccessClient.CurrentContextList(List.of(context), null));
    MockHttpSession session = new MockHttpSession();

    mockMvc
        .perform(get("/contexts").with(browserLogin()).session(session))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Test environment")));

    mockMvc
        .perform(
            post("/contexts")
                .with(browserLogin())
                .with(csrf())
                .session(session)
                .param("tenantId", "tenant-test")
                .param("environmentId", "environment-test"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/access"));
    assertThat(session.getAttribute(BrowserContext.SESSION_KEY))
        .isEqualTo(
            new BrowserContext(
                "tenant-test", "Test tenant", "environment-test", "Test environment"));
  }

  @Test
  void rejectsBrowserSubmittedContextThatS01DidNotReturn() throws Exception {
    when(identity.currentContexts("test-access-token", null))
        .thenReturn(
            new IdentityAccessClient.CurrentContextList(
                List.of(
                    new IdentityAccessClient.CurrentContext(
                        "tenant-test", "Test tenant", "environment-test", "Test environment")),
                null));
    MockHttpSession session = new MockHttpSession();

    mockMvc
        .perform(
            post("/contexts")
                .with(browserLogin())
                .with(csrf())
                .session(session)
                .param("tenantId", "tenant-other")
                .param("environmentId", "environment-other"))
        .andExpect(status().isForbidden())
        .andExpect(
            content()
                .string(
                    containsString("That context is not currently available to this identity.")));
    assertThat(session.getAttribute(BrowserContext.SESSION_KEY)).isNull();
  }

  @Test
  void paginatesTheContextSelectorUsingTheS01Cursor() throws Exception {
    when(identity.currentContexts("test-access-token", "page-token"))
        .thenReturn(new IdentityAccessClient.CurrentContextList(List.of(), "next-token"));

    mockMvc
        .perform(
            get("/contexts")
                .with(browserLogin())
                .param("cursor", "page-token"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("/contexts?cursor=next-token")));
  }

  @Test
  void forwardsCurrentAccessToUX01AndParsesItsSnakeCaseResponse() {
    RestClient.Builder experienceBuilder =
        RestClient.builder().baseUrl("http://experience.test");
    MockRestServiceServer experienceServer =
        MockRestServiceServer.bindTo(experienceBuilder).build();
    experienceServer
        .expect(requestTo("http://experience.test/v1/me/access"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-access-token"))
        .andRespond(
            withSuccess(
                """
                {
                  "principal_id":"local-admin",
                  "display_name":"Local Administrator",
                  "kind":"HUMAN",
                  "service_id":null,
                  "scope":"GLOBAL_GRANTS_ONLY",
                  "capability_ids":["identity.membership.read"]
                }
                """,
                MediaType.APPLICATION_JSON));

    IdentityAccessClient client = new IdentityAccessClient(experienceBuilder.build());
    IdentityAccessClient.MeAccess response = client.currentAccess("test-access-token");

    assertThat(response.principalId()).isEqualTo("local-admin");
    assertThat(response.capabilityIds()).containsExactly("identity.membership.read");
    experienceServer.verify();
  }

  @Test
  void forwardsContextCursorToUX01AndParsesTheNextPageCursor() {
    RestClient.Builder experienceBuilder =
        RestClient.builder().baseUrl("http://experience.test");
    MockRestServiceServer experienceServer =
        MockRestServiceServer.bindTo(experienceBuilder).build();
    experienceServer
        .expect(
            requestTo(
                "http://experience.test/v1/me/contexts?limit=25&cursor=cursor-token"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-access-token"))
        .andRespond(
            withSuccess(
                """
                {
                  "items":[
                    {
                      "tenant_id":"tenant-test",
                      "tenant_name":"Test tenant",
                      "environment_id":"environment-test",
                      "environment_name":"Test environment"
                    }
                  ],
                  "next_cursor":"next-token"
                }
                """,
                MediaType.APPLICATION_JSON));

    IdentityAccessClient client = new IdentityAccessClient(experienceBuilder.build());
    IdentityAccessClient.CurrentContextList response =
        client.currentContexts("test-access-token", "cursor-token");

    assertThat(response.items()).hasSize(1);
    assertThat(response.nextCursor()).isEqualTo("next-token");
    experienceServer.verify();
  }

  @Test
  void forwardsContextAndPurposeToUX01OwnerCandidateLookup() {
    RestClient.Builder experienceBuilder =
        RestClient.builder().baseUrl("http://experience.test");
    MockRestServiceServer experienceServer =
        MockRestServiceServer.bindTo(experienceBuilder).build();
    experienceServer
        .expect(
            requestTo(
                "http://experience.test/v1/owner-candidates?object_kind=AGENT&limit=100"
                    + "&tenant_id=tenant-test&environment_id=environment-test"
                    + "&object_id=svc-agent&cursor=owner-cursor"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-access-token"))
        .andRespond(
            withSuccess(
                """
                {
                  "items":[
                    {
                      "id":"owner-test",
                      "display_name":"Test Owner",
                      "kind":"HUMAN",
                      "enabled":true,
                      "owner_eligible":true,
                      "revision":1
                    }
                  ],
                  "next_cursor":null
                }
                """,
                MediaType.APPLICATION_JSON));

    IdentityAccessClient client = new IdentityAccessClient(experienceBuilder.build());
    IdentityAccessClient.OwnerPage page =
        client.ownerCandidates(
            "test-access-token",
            sessionContext(),
            "AGENT",
            "svc-agent",
            "owner-cursor");

    assertThat(page.items())
        .singleElement()
        .satisfies(
            owner -> {
              assertThat(owner.displayName()).isEqualTo("Test Owner");
              assertThat(owner.ownerEligible()).isTrue();
            });
    experienceServer.verify();
  }

  @Test
  void readsAndRevokesMembershipsThroughUX01WithContextAndIdempotency() {
    String membershipId = "00000000-0000-4000-8000-000000000101";
    String idempotencyKey = "00000000-0000-4000-8000-000000000201";
    RestClient.Builder experienceBuilder =
        RestClient.builder().baseUrl("http://experience.test");
    MockRestServiceServer experienceServer = MockRestServiceServer.bindTo(experienceBuilder).build();
    experienceServer
        .expect(
            requestTo(
                "http://experience.test/v1/memberships?limit=25&tenant_id=tenant-test"
                    + "&environment_id=environment-test&cursor=membership-cursor"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-access-token"))
        .andRespond(
            withSuccess(
                """
                {
                  "items":[
                    {
                      "id":"00000000-0000-4000-8000-000000000101",
                      "principal_id":"principal-test",
                      "capability":"catalog.service.write",
                      "service_scope":"svc-test",
                      "expires_at":"2030-01-01T00:00:00Z",
                      "revision":3
                    }
                  ],
                  "next_cursor":null
                }
                """,
                MediaType.APPLICATION_JSON));
    experienceServer
        .expect(
            requestTo(
                "http://experience.test/v1/memberships/"
                    + membershipId
                    + ":revoke?tenant_id=tenant-test&environment_id=environment-test"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-access-token"))
        .andExpect(header("Idempotency-Key", idempotencyKey))
        .andExpect(jsonPath("$.expected_revision").value(3))
        .andExpect(jsonPath("$.reason").value("Remove temporary access"))
        .andRespond(
            withSuccess(
                """
                {
                  "id":"00000000-0000-4000-8000-000000000101",
                  "revision":4,
                  "result":"REVOKED",
                  "runtime_effect":"NONE"
                }
                """,
                MediaType.APPLICATION_JSON));

    IdentityAccessClient client = new IdentityAccessClient(experienceBuilder.build());
    IdentityAccessClient.MembershipPage page =
        client.listMemberships("test-access-token", sessionContext(), "membership-cursor");
    IdentityAccessClient.MembershipMutationReceipt receipt =
        client.revokeMembership(
            "test-access-token",
            sessionContext(),
            membershipId,
            3,
            "Remove temporary access",
            idempotencyKey);

    assertThat(page.items()).singleElement().extracting(IdentityAccessClient.Membership::revision)
        .isEqualTo(3L);
    assertThat(receipt.result()).isEqualTo("REVOKED");
    experienceServer.verify();
  }

  @Test
  void disablesPrincipalThroughUX01WithRevisionAuditReasonAndIdempotency() {
    String principalId = "principal-test";
    String idempotencyKey = "00000000-0000-4000-8000-000000000205";
    RestClient.Builder experienceBuilder =
        RestClient.builder().baseUrl("http://experience.test");
    MockRestServiceServer experienceServer = MockRestServiceServer.bindTo(experienceBuilder).build();
    experienceServer
        .expect(requestTo("http://experience.test/v1/principals/" + principalId + ":set-status"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-access-token"))
        .andExpect(header("Idempotency-Key", idempotencyKey))
        .andExpect(jsonPath("$.enabled").value(false))
        .andExpect(jsonPath("$.expected_revision").value(2))
        .andExpect(jsonPath("$.reason").value("Disable compromised test identity."))
        .andRespond(
            withSuccess(
                """
                {
                  "id":"principal-test",
                  "revision":3,
                  "result":"UPDATED",
                  "runtime_effect":"NONE"
                }
                """,
                MediaType.APPLICATION_JSON));

    IdentityAccessClient client = new IdentityAccessClient(experienceBuilder.build());
    IdentityAccessClient.PrincipalStatusReceipt receipt =
        client.disablePrincipal(
            "test-access-token",
            principalId,
            2,
            "Disable compromised test identity.",
            idempotencyKey);

    assertThat(receipt.result()).isEqualTo("UPDATED");
    assertThat(receipt.revision()).isEqualTo(3);
    experienceServer.verify();
  }

  @Test
  void forwardsGrantDecisionAndMembershipCreationThroughUX01WithSelectedContext() {
    String decisionId = "decision-approval-201";
    String membershipId = "00000000-0000-4000-8000-000000000221";
    String idempotencyKey = "00000000-0000-4000-8000-000000000222";
    Instant expiresAt = Instant.parse("2030-01-01T00:00:00Z");
    RestClient.Builder experienceBuilder =
        RestClient.builder().baseUrl("http://experience.test");
    MockRestServiceServer experienceServer = MockRestServiceServer.bindTo(experienceBuilder).build();
    experienceServer
        .expect(
            requestTo(
                "http://experience.test/v1/grant-decisions?tenant_id=tenant-test"
                    + "&environment_id=environment-test"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-access-token"))
        .andExpect(jsonPath("$.id").value(decisionId))
        .andExpect(jsonPath("$.principal_id").value("local-member"))
        .andExpect(jsonPath("$.expires_at").value("2030-01-01T00:00:00Z"))
        .andRespond(
            withSuccess(
                """
                {
                  "id":"decision-approval-201",
                  "revision":1,
                  "result":"CREATED",
                  "runtime_effect":"NONE"
                }
                """,
                MediaType.APPLICATION_JSON));
    experienceServer
        .expect(
            requestTo(
                "http://experience.test/v1/memberships?tenant_id=tenant-test"
                    + "&environment_id=environment-test"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-access-token"))
        .andExpect(header("Idempotency-Key", idempotencyKey))
        .andExpect(jsonPath("$.id").value(membershipId))
        .andExpect(jsonPath("$.authorization_ref").value(decisionId))
        .andExpect(jsonPath("$.expected_revision").value(0))
        .andRespond(
            withSuccess(
                """
                {
                  "id":"00000000-0000-4000-8000-000000000221",
                  "revision":1,
                  "result":"CREATED",
                  "runtime_effect":"NONE"
                }
                """,
                MediaType.APPLICATION_JSON));

    IdentityAccessClient client = new IdentityAccessClient(experienceBuilder.build());
    IdentityAccessClient.GrantDecisionReceipt decision =
        client.createGrantDecision(
            "test-access-token",
            sessionContext(),
            decisionId,
            "local-member",
            "catalog.journey.read",
            "*",
            expiresAt,
            "Approved read-only access");
    IdentityAccessClient.MembershipMutationReceipt membership =
        client.createMembership(
            "test-access-token",
            sessionContext(),
            membershipId,
            "local-member",
            "catalog.journey.read",
            "*",
            expiresAt,
            "Approved read-only access",
            decisionId,
            idempotencyKey);

    assertThat(decision.result()).isEqualTo("CREATED");
    assertThat(membership.result()).isEqualTo("CREATED");
    experienceServer.verify();
  }

  @Test
  void forwardsSelectedContextAndCursorToUX01PrincipalDirectory() {
    RestClient.Builder experienceBuilder =
        RestClient.builder().baseUrl("http://experience.test");
    MockRestServiceServer experienceServer =
        MockRestServiceServer.bindTo(experienceBuilder).build();
    experienceServer
        .expect(
            requestTo(
                "http://experience.test/v1/principals?limit=25&tenant_id=tenant-test"
                    + "&environment_id=environment-test&cursor=principal-cursor"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-access-token"))
        .andRespond(
            withSuccess(
                """
                {
                  "items":[
                    {
                      "id":"principal-test",
                      "display_name":"Test Principal",
                      "kind":"HUMAN",
                      "enabled":true,
                      "owner_eligible":true,
                      "revision":2
                    }
                  ],
                  "next_cursor":null
                }
                """,
                MediaType.APPLICATION_JSON));

    IdentityAccessClient client = new IdentityAccessClient(experienceBuilder.build());
    IdentityAccessClient.PrincipalPage page =
        client.listPrincipals("test-access-token", sessionContext(), "principal-cursor");

    assertThat(page.items())
        .singleElement()
        .satisfies(
            principal -> {
              assertThat(principal.displayName()).isEqualTo("Test Principal");
              assertThat(principal.ownerEligible()).isTrue();
            });
    experienceServer.verify();
  }

  @Test
  void listsIdentityProvidersAndAdmitsPrincipalThroughUx01() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://experience.test");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(
            requestTo(
                "http://experience.test/v1/identity-providers?tenant_id=tenant-test"
                    + "&environment_id=environment-test"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-access-token"))
        .andRespond(
            withSuccess(
                """
                {
                  "items":[
                    {"id":"keycloak-local","display_name":"Local Keycloak","status":"TRUSTED"},
                    {"id":"old-provider","display_name":"Old Provider","status":"REVOKED"}
                  ]
                }
                """,
                MediaType.APPLICATION_JSON));
    server
        .expect(
            requestTo(
                "http://experience.test/v1/principals?tenant_id=tenant-test"
                    + "&environment_id=environment-test"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-access-token"))
        .andExpect(header("Idempotency-Key", "00000000-0000-4000-8000-000000000202"))
        .andExpect(
            org.springframework.test.web.client.match.MockRestRequestMatchers.content()
                .json(
                    """
                    {
                      "id":"principal-new",
                      "provider_id":"keycloak-local",
                      "directory_subject_ref":"directory-subject-new",
                      "reason":"Approved directory onboarding",
                      "expected_revision":0
                    }
                    """))
        .andRespond(
            org.springframework.test.web.client.response.MockRestResponseCreators.withStatus(
                    org.springframework.http.HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    """
                    {
                      "id":"principal-new",
                      "revision":1,
                      "result":"CREATED",
                      "runtime_effect":"NONE"
                    }
                    """));

    IdentityAccessClient client = new IdentityAccessClient(builder.build());
    IdentityAccessClient.IdentityProviderList providers =
        client.listIdentityProviders("test-access-token", sessionContext());
    IdentityAccessClient.PrincipalAdmissionReceipt receipt =
        client.admitPrincipal(
            "test-access-token",
            sessionContext(),
            "principal-new",
            "keycloak-local",
            "directory-subject-new",
            "Approved directory onboarding",
            "00000000-0000-4000-8000-000000000202");

    assertThat(providers.items())
        .extracting(IdentityAccessClient.IdentityProvider::status)
        .containsExactly("TRUSTED", "REVOKED");
    assertThat(receipt.result()).isEqualTo("CREATED");
    server.verify();
  }

  @Test
  void requestsAS01DelegationForTheSelectedContext() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://experience.test");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(requestTo("http://experience.test/internal/v1/delegations"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-access-token"))
        .andExpect(jsonPath("$.audience").value("S02"))
        .andExpect(jsonPath("$.tenant_id").value("tenant-test"))
        .andExpect(jsonPath("$.environment_id").value("environment-test"))
        .andExpect(jsonPath("$.capability_ids[0]").value("catalog.journey.read"))
        .andRespond(
            withSuccess(
                """
                {
                  "access_token":"s02-delegation",
                  "token_type":"Bearer",
                  "expires_in":30
                }
                """,
                MediaType.APPLICATION_JSON));

    IdentityAccessClient client = new IdentityAccessClient(builder.build());
    IdentityAccessClient.Delegation delegation =
        client.createDelegation(
            "test-access-token",
            "S02",
            null,
            List.of("catalog.journey.read"),
            "tenant-test",
            "environment-test");

    assertThat(delegation.accessToken()).isEqualTo("s02-delegation");
    server.verify();
  }

  @Test
  void forwardsDelegationOnlyToTheCatalogAndParsesItsJourneyPage() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://catalog.test");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(requestTo("http://catalog.test/v1/customer-journeys?pageSize=25&cursor=page-token"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer s02-delegation"))
        .andRespond(
            withSuccess(
                """
                {
                  "items":[
                    {
                      "id":"journey-test",
                      "name":"Resilience review",
                      "owner_id":"owner-test",
                      "purpose":"Review resilience controls",
                      "lifecycle":"DRAFT",
                      "revision":1
                    }
                  ],
                  "next_cursor":null
                }
                """,
                MediaType.APPLICATION_JSON));

    CatalogClient client = new CatalogClient(builder.build());
    CatalogClient.JourneyPage page = client.listJourneys("s02-delegation", "page-token");

    assertThat(page.items()).singleElement().extracting(CatalogClient.Journey::name)
        .isEqualTo("Resilience review");
    server.verify();
  }

  @Test
  void parsesJourneyDetailsAndTheirNestedRecordsFromS02() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://catalog.test");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(requestTo("http://catalog.test/v1/customer-journeys/journey-test"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer s02-delegation"))
        .andRespond(
            withSuccess(
                """
                {
                  "id":"journey-test",
                  "name":"Resilience review",
                  "owner_id":"owner-test",
                  "purpose":"Review resilience controls",
                  "lifecycle":"DRAFT",
                  "revision":1,
                  "steps":[
                    {
                      "id":"step-test",
                      "position":1,
                      "name":"Map dependencies",
                      "intended_outcome":"Identify critical paths",
                      "revision":1
                    }
                  ],
                  "capabilities":[
                    {
                      "id":"link-test",
                      "capability_kind":"SERVICE",
                      "capability_id":"service-test",
                      "step_id":"step-test",
                      "criticality":"HIGH",
                      "revision":1
                    }
                  ]
                }
                """,
                MediaType.APPLICATION_JSON));

    CatalogClient client = new CatalogClient(builder.build());
    CatalogClient.JourneyDetails journey = client.getJourney("s02-delegation", "journey-test");

    assertThat(journey.steps()).singleElement().extracting(CatalogClient.Step::intendedOutcome)
        .isEqualTo("Identify critical paths");
    assertThat(journey.capabilities())
        .singleElement()
        .extracting(CatalogClient.Capability::capabilityId)
        .isEqualTo("service-test");
    server.verify();
  }

  @Test
  void postsDraftJourneyWithServerDelegationAndIdempotencyKey() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://catalog.test");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(requestTo("http://catalog.test/v1/customer-journeys"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer s02-write-delegation"))
        .andExpect(header("Idempotency-Key", "request-key"))
        .andExpect(jsonPath("$.id").value("cmd_owner-test_request-key"))
        .andExpect(jsonPath("$.owner_id").value("owner-test"))
        .andExpect(jsonPath("$.lifecycle").value("DRAFT"))
        .andRespond(
            withSuccess(
                """
                {
                  "id":"cmd_owner-test_request-key",
                  "revision":1,
                  "record_kind":"CUSTOMER_JOURNEY",
                  "result":"CREATED",
                  "runtime_effect":"NONE"
                }
                """,
                MediaType.APPLICATION_JSON));

    CatalogClient client = new CatalogClient(builder.build());
    CatalogClient.MutationReceipt receipt =
        client.registerJourney(
            "s02-write-delegation",
            "request-key",
            new CatalogClient.JourneyRegistration(
                "cmd_owner-test_request-key",
                "Resilience review",
                "owner-test",
                "Review resilience controls",
                "DRAFT"));

    assertThat(receipt.result()).isEqualTo("CREATED");
    server.verify();
  }

  @Test
  void postsServiceBoundAgentWithDelegationAndIdempotencyKey() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://catalog.test");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(requestTo("http://catalog.test/v1/agent-workloads"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer s02-agent-write"))
        .andExpect(header("Idempotency-Key", "request-key"))
        .andExpect(jsonPath("$.service_id").value("svc-agent"))
        .andExpect(jsonPath("$.runtime_principal_id").value("runtime-test"))
        .andRespond(
            withSuccess(
                """
                {
                  "id":"cmd_owner-test_request-key",
                  "revision":1,
                  "record_kind":"AGENT_WORKLOAD",
                  "result":"CREATED",
                  "runtime_effect":"NONE"
                }
                """,
                MediaType.APPLICATION_JSON));

    CatalogClient client = new CatalogClient(builder.build());
    CatalogClient.MutationReceipt receipt =
        client.registerAgent(
            "s02-agent-write",
            "request-key",
            new CatalogClient.AgentRegistration(
                "cmd_owner-test_request-key",
                "svc-agent",
                "Recovery helper",
                "SINGLE_AGENT",
                "owner-test",
                "runtime-test",
                "Coordinate recovery checks"));

    assertThat(receipt.recordKind()).isEqualTo("AGENT_WORKLOAD");
    server.verify();
  }

  @Test
  void postsJourneyStepWithServerDelegationAndIdempotencyKey() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://catalog.test");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(requestTo("http://catalog.test/v1/customer-journeys/journey-test/steps"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer s02-write"))
        .andExpect(header("Idempotency-Key", "request-key"))
        .andExpect(jsonPath("$.id").value("cmd_owner-test_request-key"))
        .andExpect(jsonPath("$.position").value(4))
        .andExpect(jsonPath("$.intended_outcome").value("Recovery check passed"))
        .andRespond(
            withSuccess(
                """
                {
                  "id":"cmd_owner-test_request-key",
                  "revision":1,
                  "record_kind":"JOURNEY_STEP",
                  "result":"CREATED",
                  "runtime_effect":"NONE"
                }
                """,
                MediaType.APPLICATION_JSON));

    CatalogClient client = new CatalogClient(builder.build());
    CatalogClient.MutationReceipt receipt =
        client.registerStep(
            "s02-write",
            "journey-test",
            "request-key",
            new CatalogClient.StepRegistration(
                "cmd_owner-test_request-key", 4, "Validate recovery", "Recovery check passed"));

    assertThat(receipt.recordKind()).isEqualTo("JOURNEY_STEP");
    server.verify();
  }

  @Test
  void postsServiceRegistrationWithStableIdAndIdempotencyKey() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://catalog.test");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(requestTo("http://catalog.test/v1/services"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer s02-service-write"))
        .andExpect(header("Idempotency-Key", "request-key"))
        .andExpect(jsonPath("$.id").value("cmd_owner-test_request-key"))
        .andExpect(jsonPath("$.owner_id").value("owner-test"))
        .andExpect(jsonPath("$.expected_revision").value(0))
        .andExpect(jsonPath("$.criticality").value(4))
        .andExpect(jsonPath("$.lifecycle").value("DRAFT"))
        .andRespond(
            withSuccess(
                """
                {
                  "id":"cmd_owner-test_request-key",
                  "revision":1,
                  "record_kind":"SERVICE",
                  "result":"CREATED",
                  "runtime_effect":"NONE"
                }
                """,
                MediaType.APPLICATION_JSON));

    CatalogClient client = new CatalogClient(builder.build());
    CatalogClient.MutationReceipt receipt =
        client.registerService(
            "s02-service-write",
            "request-key",
            new CatalogClient.ServiceRegistration(
                "cmd_owner-test_request-key",
                "Recovery API",
                "owner-test",
                "Supports recovery checks",
                0,
                4,
                "DRAFT"));

    assertThat(receipt.recordKind()).isEqualTo("SERVICE");
    server.verify();
  }

  @Test
  void linksCapabilityWithSeparateJourneyDelegationAndIdempotencyKey() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://catalog.test");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(requestTo(
            "http://catalog.test/v1/customer-journeys/journey-test/capabilities"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer s02-journey-write"))
        .andExpect(header("Idempotency-Key", "request-key"))
        .andExpect(jsonPath("$.capability_kind").value("SERVICE"))
        .andExpect(jsonPath("$.capability_id").value("service-test"))
        .andExpect(jsonPath("$.step_id").value("step-test"))
        .andExpect(jsonPath("$.criticality").value("REQUIRED"))
        .andRespond(
            withSuccess(
                """
                {
                  "id":"link-request",
                  "revision":1,
                  "record_kind":"JOURNEY_CAPABILITY",
                  "result":"CREATED",
                  "runtime_effect":"NONE"
                }
                """,
                MediaType.APPLICATION_JSON));

    CatalogClient client = new CatalogClient(builder.build());
    CatalogClient.MutationReceipt receipt =
        client.linkCapability(
            "s02-journey-write",
            "journey-test",
            "request-key",
            new CatalogClient.CapabilityLink(
                "link-request", "SERVICE", "service-test", "step-test", "REQUIRED"));

    assertThat(receipt.recordKind()).isEqualTo("JOURNEY_CAPABILITY");
    server.verify();
  }

  @Test
  void transfersJourneyOwnerWithFreshS01AuthorizationAndExpectedRevision() throws Exception {
    String idempotencyKey = "123e4567-e89b-42d3-a456-426614174000";
    BrowserContext context = sessionContext();
    CatalogClient.JourneyDetails journey =
        new CatalogClient.JourneyDetails(
            "journey-test",
            "Resilience review",
            "owner-current",
            "Review resilience controls",
            "DRAFT",
            3,
            List.of(),
            List.of());
    CatalogClient.OwnershipTransfer transfer =
        new CatalogClient.OwnershipTransfer(
            "JOURNEY", "journey-test", "owner-next", 3, "Approved ownership change", null);
    when(identity.createDelegation(
            "test-access-token",
            "S02",
            null,
            List.of("catalog.journey.read"),
            "tenant-test",
            "environment-test"))
        .thenReturn(new IdentityAccessClient.Delegation("s02-read", "Bearer", 30));
    when(catalog.getJourney("s02-read", "journey-test")).thenReturn(journey);
    when(identity.currentAccess("test-access-token", "tenant-test", "environment-test"))
        .thenReturn(transferAccess());
    when(identity.ownerCandidates(
            "test-access-token", context, "JOURNEY", "journey-test", null))
        .thenReturn(
            new IdentityAccessClient.OwnerPage(
                List.of(
                    new IdentityAccessClient.OwnerCandidate(
                        "owner-current", "Current Owner", "HUMAN", true, true, 1),
                    new IdentityAccessClient.OwnerCandidate(
                        "owner-next", "Next Owner", "HUMAN", true, true, 1)),
                null));
    when(identity.createDelegation(
            "test-access-token",
            "S02",
            null,
            List.of("ownership.transfer"),
            "tenant-test",
            "environment-test"))
        .thenReturn(new IdentityAccessClient.Delegation("s02-transfer", "Bearer", 30));
    when(catalog.transferOwnership("s02-transfer", idempotencyKey, transfer))
        .thenReturn(
            new CatalogClient.OwnershipTransferReceipt(
                "journey-test", 4, "UPDATED", "NONE"));

    mockMvc
        .perform(
            post("/ownership-transfers/JOURNEY/journey-test")
                .with(browserLogin())
                .with(csrf())
                .session(sessionWithSelectedContext())
                .param("ownerId", "owner-next")
                .param("expectedRevision", "3")
                .param("reason", "Approved ownership change")
                .param("idempotencyKey", idempotencyKey)
                .param("ownerCursor", ""))
        .andExpect(status().is3xxRedirection())
        .andExpect(
            redirectedUrl(
                "/ownership-transfers/JOURNEY/journey-test?updated=true"));

    verify(identity)
        .createDelegation(
            "test-access-token",
            "S02",
            null,
            List.of("ownership.transfer"),
            "tenant-test",
            "environment-test");
    verify(catalog).transferOwnership("s02-transfer", idempotencyKey, transfer);
  }

  @Test
  void retriesCompletedTransferWhenCurrentOwnerMatchesSubmittedOwner() throws Exception {
    String idempotencyKey = "123e4567-e89b-42d3-a456-426614174003";
    BrowserContext context = sessionContext();
    CatalogClient.OwnershipTransfer transfer =
        new CatalogClient.OwnershipTransfer(
            "JOURNEY", "journey-test", "owner-next", 3, "Approved ownership change", null);
    when(identity.createDelegation(
            "test-access-token",
            "S02",
            null,
            List.of("catalog.journey.read"),
            "tenant-test",
            "environment-test"))
        .thenReturn(new IdentityAccessClient.Delegation("s02-read", "Bearer", 30));
    when(catalog.getJourney("s02-read", "journey-test"))
        .thenReturn(
            new CatalogClient.JourneyDetails(
                "journey-test",
                "Resilience review",
                "owner-next",
                "Review resilience controls",
                "DRAFT",
                4,
                List.of(),
                List.of()));
    when(identity.currentAccess("test-access-token", "tenant-test", "environment-test"))
        .thenReturn(transferAccess());
    when(identity.ownerCandidates(
            "test-access-token", context, "JOURNEY", "journey-test", null))
        .thenReturn(
            new IdentityAccessClient.OwnerPage(
                List.of(
                    new IdentityAccessClient.OwnerCandidate(
                        "owner-next", "Next Owner", "HUMAN", true, true, 1)),
                null));
    when(identity.createDelegation(
            "test-access-token",
            "S02",
            null,
            List.of("ownership.transfer"),
            "tenant-test",
            "environment-test"))
        .thenReturn(new IdentityAccessClient.Delegation("s02-transfer", "Bearer", 30));
    when(catalog.transferOwnership("s02-transfer", idempotencyKey, transfer))
        .thenReturn(
            new CatalogClient.OwnershipTransferReceipt(
                "journey-test", 4, "REPLAYED", "NONE"));

    mockMvc
        .perform(
            post("/ownership-transfers/JOURNEY/journey-test")
                .with(browserLogin())
                .with(csrf())
                .session(sessionWithSelectedContext())
                .param("ownerId", "owner-next")
                .param("expectedRevision", "3")
                .param("reason", "Approved ownership change")
                .param("idempotencyKey", idempotencyKey)
                .param("ownerCursor", ""))
        .andExpect(status().is3xxRedirection())
        .andExpect(
            redirectedUrl(
                "/ownership-transfers/JOURNEY/journey-test?updated=true"));

    verify(catalog).transferOwnership("s02-transfer", idempotencyKey, transfer);
  }

  @Test
  void rendersTransferFormWithCurrentRevisionAndExcludesTheCurrentOwner() throws Exception {
    BrowserContext context = sessionContext();
    when(identity.createDelegation(
            "test-access-token",
            "S02",
            null,
            List.of("catalog.journey.read"),
            "tenant-test",
            "environment-test"))
        .thenReturn(new IdentityAccessClient.Delegation("s02-read", "Bearer", 30));
    when(catalog.getJourney("s02-read", "journey-test"))
        .thenReturn(
            new CatalogClient.JourneyDetails(
                "journey-test",
                "Resilience review",
                "owner-current",
                "Review resilience controls",
                "DRAFT",
                3,
                List.of(),
                List.of()));
    when(identity.currentAccess("test-access-token", "tenant-test", "environment-test"))
        .thenReturn(transferAccess());
    when(identity.ownerCandidates(
            "test-access-token", context, "JOURNEY", "journey-test", null))
        .thenReturn(
            new IdentityAccessClient.OwnerPage(
                List.of(
                    new IdentityAccessClient.OwnerCandidate(
                        "owner-current", "Current Owner", "HUMAN", true, true, 1),
                    new IdentityAccessClient.OwnerCandidate(
                        "owner-next", "Next Owner", "HUMAN", true, true, 1)),
                "next-owner-page"));

    mockMvc
        .perform(
            get("/ownership-transfers/JOURNEY/journey-test")
                .with(browserLogin())
                .session(sessionWithSelectedContext()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Resilience review")))
        .andExpect(content().string(containsString("Current revision")))
        .andExpect(content().string(containsString("value=\"3\"")))
        .andExpect(content().string(containsString("Next Owner (owner-next)")))
        .andExpect(content().string(not(containsString("Current Owner (owner-current)"))))
        .andExpect(content().string(containsString("More eligible owners")))
        .andExpect(content().string(not(containsString("test-access-token"))));
  }

  @Test
  void transfersServiceOwnerWithServiceScopedS01Delegation() throws Exception {
    String serviceId = "service-test";
    String idempotencyKey = "123e4567-e89b-42d3-a456-426614174001";
    BrowserContext context = sessionContext();
    CatalogClient.ServiceRecord service =
        new CatalogClient.ServiceRecord(
            serviceId, "Recovery API", "owner-current", "ACTIVE", 3, 5);
    CatalogClient.OwnershipTransfer transfer =
        new CatalogClient.OwnershipTransfer(
            "SERVICE", serviceId, "owner-next", 5, "Service ownership change", null);
    when(identity.createDelegation(
            "test-access-token",
            "S02",
            serviceId,
            List.of("catalog.service.read"),
            "tenant-test",
            "environment-test"))
        .thenReturn(new IdentityAccessClient.Delegation("s02-service-read", "Bearer", 30));
    when(catalog.findService("s02-service-read", serviceId))
        .thenReturn(java.util.Optional.of(service));
    when(identity.currentAccess("test-access-token", "tenant-test", "environment-test"))
        .thenReturn(transferAccess());
    when(identity.ownerCandidates(
            "test-access-token", context, "SERVICE", serviceId, null))
        .thenReturn(
            new IdentityAccessClient.OwnerPage(
                List.of(
                    new IdentityAccessClient.OwnerCandidate(
                        "owner-next", "Next Owner", "HUMAN", true, true, 1)),
                null));
    when(identity.createDelegation(
            "test-access-token",
            "S02",
            serviceId,
            List.of("ownership.transfer"),
            "tenant-test",
            "environment-test"))
        .thenReturn(new IdentityAccessClient.Delegation("s02-service-transfer", "Bearer", 30));
    when(catalog.transferOwnership("s02-service-transfer", idempotencyKey, transfer))
        .thenReturn(
            new CatalogClient.OwnershipTransferReceipt(serviceId, 6, "UPDATED", "NONE"));

    mockMvc
        .perform(
            post("/ownership-transfers/SERVICE/" + serviceId)
                .with(browserLogin())
                .with(csrf())
                .session(sessionWithSelectedContext())
                .param("ownerId", "owner-next")
                .param("expectedRevision", "5")
                .param("reason", "Service ownership change")
                .param("idempotencyKey", idempotencyKey)
                .param("ownerCursor", ""))
        .andExpect(status().is3xxRedirection())
        .andExpect(
            redirectedUrl(
                "/ownership-transfers/SERVICE/" + serviceId + "?updated=true"));

    verify(identity)
        .createDelegation(
            "test-access-token",
            "S02",
            serviceId,
            List.of("ownership.transfer"),
            "tenant-test",
            "environment-test");
    verify(catalog).transferOwnership("s02-service-transfer", idempotencyKey, transfer);
  }

  @Test
  void rejectsUnlistedOwnerBeforeRequestingTransferDelegation() throws Exception {
    BrowserContext context = sessionContext();
    when(identity.createDelegation(
            "test-access-token",
            "S02",
            null,
            List.of("catalog.journey.read"),
            "tenant-test",
            "environment-test"))
        .thenReturn(new IdentityAccessClient.Delegation("s02-read", "Bearer", 30));
    when(catalog.getJourney("s02-read", "journey-test"))
        .thenReturn(
            new CatalogClient.JourneyDetails(
                "journey-test",
                "Resilience review",
                "owner-current",
                "Review resilience controls",
                "DRAFT",
                1,
                List.of(),
                List.of()));
    when(identity.currentAccess("test-access-token", "tenant-test", "environment-test"))
        .thenReturn(transferAccess());
    when(identity.ownerCandidates(
            "test-access-token", context, "JOURNEY", "journey-test", null))
        .thenReturn(
            new IdentityAccessClient.OwnerPage(
                List.of(
                    new IdentityAccessClient.OwnerCandidate(
                        "owner-eligible", "Eligible Owner", "HUMAN", true, true, 1)),
                null));

    mockMvc
        .perform(
            post("/ownership-transfers/JOURNEY/journey-test")
                .with(browserLogin())
                .with(csrf())
                .session(sessionWithSelectedContext())
                .param("ownerId", "owner-not-listed")
                .param("expectedRevision", "1")
                .param("reason", "Should not be accepted")
                .param("idempotencyKey", "123e4567-e89b-42d3-a456-426614174002")
                .param("ownerCursor", ""))
        .andExpect(status().isForbidden())
        .andExpect(
            content()
                .string(
                    containsString(
                        "Select an active, eligible human owner from the current S01 owner list.")));

    verify(identity, never())
        .createDelegation(
            "test-access-token",
            "S02",
            null,
            List.of("ownership.transfer"),
            "tenant-test",
            "environment-test");
    verify(catalog, never())
        .transferOwnership(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  void hidesTransferControlsWhenCurrentS01AccessLacksTransferAuthority() throws Exception {
    when(identity.createDelegation(
            "test-access-token",
            "S02",
            null,
            List.of("catalog.journey.read"),
            "tenant-test",
            "environment-test"))
        .thenReturn(new IdentityAccessClient.Delegation("s02-read", "Bearer", 30));
    when(catalog.getJourney("s02-read", "journey-test"))
        .thenReturn(
            new CatalogClient.JourneyDetails(
                "journey-test",
                "Resilience review",
                "owner-current",
                "Review resilience controls",
                "DRAFT",
                1,
                List.of(),
                List.of()));
    when(identity.currentAccess("test-access-token", "tenant-test", "environment-test"))
        .thenReturn(
            new IdentityAccessClient.MeAccess(
                "local-reader",
                "Local Reader",
                "HUMAN",
                null,
                "GLOBAL_GRANTS_ONLY",
                List.of("identity.owner.select")));

    mockMvc
        .perform(
            get("/ownership-transfers/JOURNEY/journey-test")
                .with(browserLogin())
                .session(sessionWithSelectedContext()))
        .andExpect(status().isForbidden())
        .andExpect(
            content()
                .string(
                    containsString(
                        "The selected context does not grant ownership.transfer and identity.owner.select.")));

    verify(identity, never())
        .ownerCandidates(
            "test-access-token", sessionContext(), "JOURNEY", "journey-test", null);
  }

  @Test
  void serializesOwnershipTransferReceiptAndReadsScopedServiceDetails() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://catalog.test");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(requestTo("http://catalog.test/v1/services?pageSize=25&includeRetired=true"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer s02-service-read"))
        .andRespond(
            withSuccess(
                """
                {
                  "items":[
                    {
                      "id":"service-test",
                      "name":"Recovery API",
                      "owner_id":"owner-current",
                      "lifecycle":"ACTIVE",
                      "criticality":3,
                      "revision":5
                    }
                  ],
                  "next_cursor":null
                }
                """,
                MediaType.APPLICATION_JSON));
    server
        .expect(requestTo("http://catalog.test/v1/ownership:transfer"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer s02-transfer"))
        .andExpect(header("Idempotency-Key", "transfer-key"))
        .andExpect(jsonPath("$.object_kind").value("SERVICE"))
        .andExpect(jsonPath("$.object_id").value("service-test"))
        .andExpect(jsonPath("$.owner_id").value("owner-next"))
        .andExpect(jsonPath("$.expected_revision").value(5))
        .andExpect(jsonPath("$.expected_anchor_revision").value(org.hamcrest.Matchers.nullValue()))
        .andRespond(
            withSuccess(
                """
                {
                  "id":"service-test",
                  "revision":6,
                  "result":"UPDATED",
                  "runtime_effect":"NONE"
                }
                """,
                MediaType.APPLICATION_JSON));

    CatalogClient client = new CatalogClient(builder.build());
    assertThat(client.findService("s02-service-read", "service-test"))
        .contains(
            new CatalogClient.ServiceRecord(
                "service-test", "Recovery API", "owner-current", "ACTIVE", 3, 5));
    CatalogClient.OwnershipTransferReceipt receipt =
        client.transferOwnership(
            "s02-transfer",
            "transfer-key",
            new CatalogClient.OwnershipTransfer(
                "SERVICE", "service-test", "owner-next", 5, "Transfer reason", null));

    assertThat(receipt.result()).isEqualTo("UPDATED");
    assertThat(receipt.runtimeEffect()).isEqualTo("NONE");
    server.verify();
  }

  private RequestPostProcessor browserLogin() {
    return request -> {
      oidcLogin()
          .idToken(token -> token.subject("local-admin-subject"))
          .postProcessRequest(request);
      return oauth2Client("keycloak")
          .principalName("local-admin-subject")
          .accessToken(
              new OAuth2AccessToken(
                  OAuth2AccessToken.TokenType.BEARER,
                  "test-access-token",
                  Instant.now(),
                  Instant.now().plusSeconds(60)))
          .postProcessRequest(request);
    };
  }

  private MockHttpSession sessionWithSelectedContext() {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute(
        BrowserContext.SESSION_KEY,
        new BrowserContext("tenant-test", "Test tenant", "environment-test", "Test environment"));
    return session;
  }

  private BrowserContext sessionContext() {
    return new BrowserContext(
        "tenant-test", "Test tenant", "environment-test", "Test environment");
  }

  private static IdentityAccessClient.MeAccess transferAccess() {
    return new IdentityAccessClient.MeAccess(
        "local-admin",
        "Local Administrator",
        "HUMAN",
        null,
        "GLOBAL_GRANTS_ONLY",
        List.of("ownership.transfer", "identity.owner.select"));
  }
}
