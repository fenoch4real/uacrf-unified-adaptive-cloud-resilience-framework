package org.uacrf.edge;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
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
import org.springframework.web.client.RestClient;

class DecisionGovernanceRouteControllerTests {
  private static final String URL = "http://decision-governance.test";

  @Test
  void forwardsOnlyTheFixedPolicyBundleOperationsAndPreservesRequestAndOwnerResponse()
      throws Exception {
    RestClient.Builder builder = RestClient.builder().baseUrl(URL);
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(
                new DecisionGovernanceRouteController(
                    new DecisionGovernanceRouteClient(builder.build())))
            .build();

    server
        .expect(requestTo(URL + "/v1/authorization-policy-bundles?state=draft&state=active&cursor=a%2Bb"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer incoming-token"))
        .andExpect(header("Idempotency-Key", "request-key-1"))
        .andRespond(
            withStatus(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"items\":[{\"id\":\"bundle-1\"}]}"));
    server
        .expect(requestTo(URL + "/v1/authorization-policy-bundles"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer incoming-token"))
        .andExpect(header("Idempotency-Key", "request-key-2"))
        .andRespond(
            withStatus(HttpStatus.CREATED)
                .header(HttpHeaders.LOCATION, "/v1/authorization-policy-bundles/bundle-2")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"id\":\"bundle-2\"}"));
    server
        .expect(requestTo(URL + "/v1/authorization-policy-bundles/bundle-2"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"NOT_FOUND\"}"));
    server
        .expect(requestTo(URL + "/v1/authorization-policy-bundles/bundle-2%3Arevision?revision=1"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withStatus(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"policy\":{\"id\":\"bundle-2:revision\"}}"));

    mvc.perform(
            get("/internal/decision-governance/v1/authorization-policy-bundles")
                .param("state", "draft", "active")
                .param("cursor", "a+b")
                .header(HttpHeaders.AUTHORIZATION, "Bearer incoming-token")
                .header("Idempotency-Key", "request-key-1"))
        .andExpect(status().isOk())
        .andExpect(content().json("{\"items\":[{\"id\":\"bundle-1\"}]}"))
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    mvc.perform(
            post("/internal/decision-governance/v1/authorization-policy-bundles")
                .header(HttpHeaders.AUTHORIZATION, "Bearer incoming-token")
                .header("Idempotency-Key", "request-key-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"baseline\"}"))
        .andExpect(status().isCreated())
        .andExpect(header().string(HttpHeaders.LOCATION, "/v1/authorization-policy-bundles/bundle-2"))
        .andExpect(content().json("{\"id\":\"bundle-2\"}"));
    mvc.perform(
            get("/internal/decision-governance/v1/authorization-policy-bundles/bundle-2")
                .header(HttpHeaders.AUTHORIZATION, "Bearer incoming-token"))
        .andExpect(status().isNotFound())
        .andExpect(content().json("{\"code\":\"NOT_FOUND\"}"));
    mvc.perform(
            get("/internal/decision-governance/v1/authorization-policy-bundles/bundle-2:revision")
                .param("revision", "1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer incoming-token"))
        .andExpect(status().isOk())
        .andExpect(content().json("{\"policy\":{\"id\":\"bundle-2:revision\"}}"));
    server.verify();
  }

  @Test
  void doesNotExposeDecisionPermitOrEvaluationRoutes() throws Exception {
    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(
                new DecisionGovernanceRouteController(
                    new DecisionGovernanceRouteClient(RestClient.builder().baseUrl(URL).build())))
            .build();

    mvc.perform(
            post(
                    "/internal/decision-governance/v1/authorization-policy-bundles/bundle-1:decide")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isMethodNotAllowed());
    mvc.perform(post("/internal/decision-governance/v1/permits").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isNotFound());
    mvc.perform(get("/internal/decision-governance/v1/evaluations")).andExpect(status().isNotFound());
  }
}
