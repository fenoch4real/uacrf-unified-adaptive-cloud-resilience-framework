package org.uacrf.edge;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
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
  void forwardsAsyncGraphCommandsAndCommandStatusWithContextAndErrors() throws Exception {
    RestClient.Builder builder = RestClient.builder().baseUrl(URL);
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(new TopologyRouteController(new TopologyRouteClient(builder.build())))
            .build();

    String classifyRequest =
        "{\"service_id\":\"svc-a\",\"case_id\":\"case-a\",\"reason\":\"review changed graph\",\"expected_revision\":0,\"authorized_graph_revision_id\":\"graph-a\",\"current_graph_revision_id\":\"graph-b\",\"selection_digest\":\"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\"}";
    String classifyResponse =
        "{\"command_id\":\"cmd-diff\",\"status\":\"PENDING\",\"status_resource\":\"/v1/commands/cmd-diff\",\"domain_completion\":false}";
    server
        .expect(requestTo(URL + "/v1/graphs/differences:classify"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tenant-token"))
        .andExpect(header("Idempotency-Key", "diff-key"))
        .andExpect(content().string(classifyRequest))
        .andRespond(
            withStatus(HttpStatus.ACCEPTED)
                .header(HttpHeaders.LOCATION, "/v1/commands/cmd-diff")
                .contentType(MediaType.APPLICATION_JSON)
                .body(classifyResponse));

    String selectRequest =
        "{\"service_id\":\"svc-a\",\"case_id\":\"case-a\",\"graph_revision_id\":\"graph-a\",\"reason\":\"review changed graph\",\"expected_revision\":0,\"query_id\":\"critical-paths\",\"query_parameters_ref\":\"artifact://scope-a\",\"query_parameters_digest\":\"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\",\"purpose\":\"change-impact-review\",\"maximum_nodes\":100}";
    String selectResponse =
        "{\"command_id\":\"cmd-select\",\"status\":\"PENDING\",\"status_resource\":\"/v1/commands/cmd-select\",\"domain_completion\":false}";
    server
        .expect(requestTo(URL + "/v1/graphs/subgraphs:select"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tenant-token"))
        .andExpect(header("Idempotency-Key", "select-key"))
        .andExpect(content().string(selectRequest))
        .andRespond(
            withStatus(HttpStatus.ACCEPTED)
                .header(HttpHeaders.LOCATION, "/v1/commands/cmd-select")
                .contentType(MediaType.APPLICATION_JSON)
                .body(selectResponse));

    server
        .expect(requestTo(URL + "/v1/commands/cmd-select"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tenant-token"))
        .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body(selectResponse));

    String conflict = "{\"code\":\"REVISION_CONFLICT\"}";
    server
        .expect(requestTo(URL + "/v1/graphs/differences:classify"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Idempotency-Key", "stale-key"))
        .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON).body(conflict));

    String missingCommand = "{\"code\":\"COMMAND_NOT_FOUND\"}";
    server
        .expect(requestTo(URL + "/v1/commands/missing"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tenant-token"))
        .andRespond(
            withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body(missingCommand));

    mvc.perform(post("/internal/topology/v1/graphs/differences:classify")
            .header(HttpHeaders.AUTHORIZATION, "Bearer tenant-token")
            .header("Idempotency-Key", "diff-key")
            .contentType(MediaType.APPLICATION_JSON)
            .content(classifyRequest))
        .andExpect(status().isAccepted())
        .andExpect(header().string(HttpHeaders.LOCATION, "/v1/commands/cmd-diff"))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(classifyResponse));
    mvc.perform(post("/internal/topology/v1/graphs/subgraphs:select")
            .header(HttpHeaders.AUTHORIZATION, "Bearer tenant-token")
            .header("Idempotency-Key", "select-key")
            .contentType(MediaType.APPLICATION_JSON)
            .content(selectRequest))
        .andExpect(status().isAccepted())
        .andExpect(header().string(HttpHeaders.LOCATION, "/v1/commands/cmd-select"))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(selectResponse));
    mvc.perform(get("/internal/topology/v1/commands/cmd-select")
            .header(HttpHeaders.AUTHORIZATION, "Bearer tenant-token"))
        .andExpect(status().isOk())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(selectResponse));
    mvc.perform(post("/internal/topology/v1/graphs/differences:classify")
            .header("Idempotency-Key", "stale-key")
            .contentType(MediaType.APPLICATION_JSON)
            .content(classifyRequest))
        .andExpect(status().isConflict())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(conflict));
    mvc.perform(get("/internal/topology/v1/commands/missing")
            .header(HttpHeaders.AUTHORIZATION, "Bearer tenant-token"))
        .andExpect(status().isNotFound())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(missingCommand));

    server.verify();
  }

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
