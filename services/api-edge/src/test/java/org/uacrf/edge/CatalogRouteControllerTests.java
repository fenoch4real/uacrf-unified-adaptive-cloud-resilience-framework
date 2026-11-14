package org.uacrf.edge;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

class CatalogRouteControllerTests {
  private static final String CATALOG_URL = "http://catalog.test";

  @Test
  void forwardsAllDelegatedCatalogMutationsToTheFixedOwner() throws Exception {
    TestRoute route = route();
    expectMutation(route.server(), "/v1/services", "service-a");
    expectMutation(route.server(), "/v1/agent-workloads", "agent-a");
    expectMutation(route.server(), "/v1/customer-journeys", "journey-a");
    expectMutation(route.server(), "/v1/customer-journeys/journey-a/steps", "step-a");
    expectMutation(
        route.server(), "/v1/customer-journeys/journey-a/capabilities", "capability-a");

    postMutation(route.mvc(), "/internal/catalog/v1/services", "service-a");
    postMutation(route.mvc(), "/internal/catalog/v1/agent-workloads", "agent-a");
    postMutation(route.mvc(), "/internal/catalog/v1/customer-journeys", "journey-a");
    postMutation(
        route.mvc(),
        "/internal/catalog/v1/customer-journeys/journey-a/steps",
        "step-a");
    postMutation(
        route.mvc(),
        "/internal/catalog/v1/customer-journeys/journey-a/capabilities",
        "capability-a");

    route.server().verify();
  }

  @Test
  void forwardsCatalogReadsAndPreservesAuthenticationChallenges() throws Exception {
    TestRoute route = route();
    route
        .server()
        .expect(requestTo(CATALOG_URL + "/v1/customer-journeys?pageSize=25&cursor=next-page"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(
            org.springframework.test.web.client.match.MockRestRequestMatchers.header(
                HttpHeaders.AUTHORIZATION, "Bearer delegated-token"))
        .andRespond(
            withStatus(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"UNAUTHENTICATED\"}"));

    route
        .mvc()
        .perform(
            get("/internal/catalog/v1/customer-journeys")
                .param("pageSize", "25")
                .param("cursor", "next-page")
                .header(HttpHeaders.AUTHORIZATION, "Bearer delegated-token"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .json("{\"code\":\"UNAUTHENTICATED\"}"));

    route.server().verify();
  }

  @Test
  void forwardsServiceAgentAndJourneyDetailReads() throws Exception {
    TestRoute route = route();
    route
        .server()
        .expect(
            requestTo(
                CATALOG_URL
                    + "/v1/services?pageSize=10&cursor=service-cursor&includeRetired=true"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(
            org.springframework.test.web.client.match.MockRestRequestMatchers.header(
                HttpHeaders.AUTHORIZATION, "Bearer delegated-token"))
        .andRespond(withStatus(HttpStatus.OK).body("{\"items\":[{\"id\":\"service-a\"}]}"));
    route
        .server()
        .expect(requestTo(CATALOG_URL + "/v1/agent-workloads?pageSize=25&cursor=agent-cursor"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(
            org.springframework.test.web.client.match.MockRestRequestMatchers.header(
                HttpHeaders.AUTHORIZATION, "Bearer delegated-token"))
        .andRespond(withStatus(HttpStatus.OK).body("{\"items\":[{\"id\":\"agent-a\"}]}"));
    route
        .server()
        .expect(requestTo(CATALOG_URL + "/v1/customer-journeys/journey-a"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(
            org.springframework.test.web.client.match.MockRestRequestMatchers.header(
                HttpHeaders.AUTHORIZATION, "Bearer delegated-token"))
        .andRespond(withStatus(HttpStatus.OK).body("{\"id\":\"journey-a\"}"));

    route
        .mvc()
        .perform(
            get("/internal/catalog/v1/services")
                .param("pageSize", "10")
                .param("cursor", "service-cursor")
                .param("includeRetired", "true")
                .header(HttpHeaders.AUTHORIZATION, "Bearer delegated-token"))
        .andExpect(status().isOk())
        .andExpect(content().json("{\"items\":[{\"id\":\"service-a\"}]}"));
    route
        .mvc()
        .perform(
            get("/internal/catalog/v1/agent-workloads")
                .param("cursor", "agent-cursor")
                .header(HttpHeaders.AUTHORIZATION, "Bearer delegated-token"))
        .andExpect(status().isOk())
        .andExpect(content().json("{\"items\":[{\"id\":\"agent-a\"}]}"));
    route
        .mvc()
        .perform(
            get("/internal/catalog/v1/customer-journeys/journey-a")
                .header(HttpHeaders.AUTHORIZATION, "Bearer delegated-token"))
        .andExpect(status().isOk())
        .andExpect(content().json("{\"id\":\"journey-a\"}"));

    route.server().verify();
  }

  @Test
  void forwardsOptionalAuthorizationAndIdempotencyForJourneyStepWrites() throws Exception {
    TestRoute route = route();
    route
        .server()
        .expect(requestTo(CATALOG_URL + "/v1/customer-journeys/journey-a/steps"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
        .andExpect(
            org.springframework.test.web.client.match.MockRestRequestMatchers.header(
                "Idempotency-Key", "step-key"))
        .andExpect(
            org.springframework.test.web.client.match.MockRestRequestMatchers.content()
                .json("{\"id\":\"step-a\",\"position\":1}"))
        .andRespond(
            withStatus(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"id\":\"step-a\",\"revision\":1}"));

    route
        .mvc()
        .perform(
            post("/internal/catalog/v1/customer-journeys/journey-a/steps")
                .header("Idempotency-Key", "step-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"step-a\",\"position\":1}"))
        .andExpect(status().isCreated())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(content().json("{\"id\":\"step-a\",\"revision\":1}"));

    route.server().verify();
  }

  @Test
  void returnsBadGatewayWhenCatalogOwnerCannotBeReached() throws Exception {
    TestRoute route = route();
    route
        .server()
        .expect(requestTo(CATALOG_URL + "/v1/services?pageSize=25"))
        .andRespond(
            request -> {
              throw new ResourceAccessException("Catalog connection failed.");
            });

    route
        .mvc()
        .perform(get("/internal/catalog/v1/services"))
        .andExpect(status().isBadGateway())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .json(
                    "{\"code\":\"CATALOG_UNAVAILABLE\","
                        + "\"message\":\"Catalog service is unavailable.\"}"));

    route.server().verify();
  }

  @Test
  void doesNotClaimTheCanonicalServiceAdmissionPath() throws Exception {
    TestRoute route = route();

    route.mvc().perform(post("/v1/services")).andExpect(status().isNotFound());

    route.server().verify();
  }

  private static TestRoute route() {
    RestClient.Builder builder = RestClient.builder().baseUrl(CATALOG_URL);
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    CatalogRouteClient client = new CatalogRouteClient(builder.build());
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new CatalogRouteController(client)).build();
    return new TestRoute(server, mvc);
  }

  private static void expectMutation(MockRestServiceServer server, String path, String id) {
    server
        .expect(requestTo(CATALOG_URL + path))
        .andExpect(method(HttpMethod.POST))
        .andExpect(
            org.springframework.test.web.client.match.MockRestRequestMatchers.header(
                HttpHeaders.AUTHORIZATION, "Bearer delegated-token"))
        .andExpect(
            org.springframework.test.web.client.match.MockRestRequestMatchers.header(
                "Idempotency-Key", "request-key"))
        .andExpect(
            org.springframework.test.web.client.match.MockRestRequestMatchers.content()
                .json("{\"id\":\"" + id + "\"}"))
        .andRespond(
            withStatus(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"id\":\"" + id + "\",\"revision\":1}"));
  }

  private static void postMutation(MockMvc mvc, String path, String id) throws Exception {
    mvc.perform(
            post(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer delegated-token")
                .header("Idempotency-Key", "request-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"" + id + "\"}"))
        .andExpect(status().isCreated())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .json("{\"id\":\"" + id + "\",\"revision\":1}"));
  }

  private record TestRoute(MockRestServiceServer server, MockMvc mvc) {}
}
