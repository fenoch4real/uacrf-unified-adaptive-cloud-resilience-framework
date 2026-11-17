package org.uacrf.edge;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

class TopologyRouteControllerTests {
  private static final String URL = "http://topology.test";

  @Test
  void forwardsPublicOperationsToTheFixedOwnerAndLeavesTheInternalReceiptUnrouted() throws Exception {
    RestClient.Builder builder = RestClient.builder().baseUrl(URL);
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(new TopologyRouteController(new TopologyRouteClient(builder.build())))
            .build();
    server
        .expect(requestTo(URL + "/v1/dependency-proposals?pageSize=5&cursor=n"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withStatus(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{}"));
    server.expect(requestTo(URL + "/v1/dependency-proposals/p-1")).andExpect(method(HttpMethod.GET))
        .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{}"));
    server.expect(requestTo(URL + "/v1/dependency-proposals")).andExpect(method(HttpMethod.POST))
        .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("{}"));
    server.expect(requestTo(URL + "/v1/dependency-proposals:evidence-backed")).andExpect(method(HttpMethod.POST))
        .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("{}"));
    server.expect(requestTo(URL + "/v1/dependency-proposals/p-1:review")).andExpect(method(HttpMethod.POST))
        .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{}"));
    server.expect(requestTo(URL + "/v1/graphs:accept")).andExpect(method(HttpMethod.POST))
        .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{}"));
    server.expect(requestTo(URL + "/v1/graphs/g-1")).andExpect(method(HttpMethod.GET))
        .andRespond(withException(new IOException("down")));
    server.expect(requestTo(URL + "/v1/graphs/g-1/reconciliation"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{}"));
    server.expect(requestTo(URL + "/v1/graphs/g-1/subgraph?root_type=service&root_id=s1&depth=2"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{}"));
    server.expect(requestTo(URL + "/v1/graphs/g-1/impact?entity_type=service&entity_id=s1"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{}"));

    mvc.perform(get("/internal/topology/v1/dependency-proposals").param("pageSize", "5").param("cursor", "n"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    mvc.perform(get("/internal/topology/v1/dependency-proposals/p-1")).andExpect(status().isOk());
    mvc.perform(post("/internal/topology/v1/dependency-proposals").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isCreated());
    mvc.perform(post("/internal/topology/v1/dependency-proposals:evidence-backed")
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isCreated());
    mvc.perform(post("/internal/topology/v1/dependency-proposals/p-1:review")
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isOk());
    mvc.perform(post("/internal/topology/v1/graphs:accept").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isOk());
    mvc.perform(get("/internal/topology/v1/graphs/g-1")).andExpect(status().isBadGateway());
    mvc.perform(get("/internal/topology/v1/graphs/g-1/reconciliation")).andExpect(status().isOk());
    mvc.perform(get("/internal/topology/v1/graphs/g-1/subgraph")
            .param("root_type", "service").param("root_id", "s1").param("depth", "2"))
        .andExpect(status().isOk());
    mvc.perform(get("/internal/topology/v1/graphs/g-1/impact")
            .param("entity_type", "service").param("entity_id", "s1"))
        .andExpect(status().isOk());
    mvc.perform(post("/internal/topology/internal/v1/graphs/g-1:receipt")
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isNotFound());
    server.verify();
  }
}
