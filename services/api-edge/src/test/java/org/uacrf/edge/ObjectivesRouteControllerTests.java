package org.uacrf.edge;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

class ObjectivesRouteControllerTests {
  private static final String URL = "http://objectives.test";

  @Test
  void forwardsPublicOperationsToTheFixedOwnerAndDoesNotRouteInternalEndpoints() throws Exception {
    RestClient.Builder builder = RestClient.builder().baseUrl(URL);
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(new ObjectivesRouteController(new ObjectivesRouteClient(builder.build())))
            .build();
    server
        .expect(requestTo(URL + "/v1/objectives?pageSize=5&workloadId=w1&cursor=n"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withStatus(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{}"));
    server
        .expect(requestTo(URL + "/v1/objectives"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("{}"));
    server
        .expect(requestTo(URL + "/v1/objectives:local-idempotent-create"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer delegated"))
        .andExpect(header("Idempotency-Key", "local-1"))
        .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("{}"));
    server
        .expect(requestTo(URL + "/v1/objectives:local-idempotent-create"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer delegated"))
        .andExpect(header("Idempotency-Key", "local-1"))
        .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{}"));
    server
        .expect(requestTo(URL + "/v1/objective-imports:preview"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Idempotency-Key", "preview-1"))
        .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON).body("{}"));
    server
        .expect(requestTo(URL + "/v1/objectives/o-1:idempotent-submit"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer delegated"))
        .andExpect(header("Idempotency-Key", "submission-1"))
        .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{}"));
    server
        .expect(requestTo(URL + "/v1/objectives/o-1:idempotent-decide"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer delegated"))
        .andExpect(header("Idempotency-Key", "review-1"))
        .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON).body("{}"));
    server
        .expect(requestTo(URL + "/v1/objectives/o-1:idempotent-activate"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer delegated"))
        .andExpect(header("Idempotency-Key", "activation-1"))
        .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON).body("{}"));
    server
        .expect(requestTo(URL + "/v1/objective-imports/import-1"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON).body("{}"));
    server
        .expect(requestTo(URL + "/v1/objectives/o-1:decide"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{}"));
    server
        .expect(requestTo(URL + "/v1/objectives/o-1/decision-history"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer delegated"))
        .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON).body("{}"));
    server
        .expect(requestTo(URL + "/v1/organization-constraints/latency.p95"))
        .andExpect(method(HttpMethod.PUT))
        .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{}"));
    server.expect(requestTo(URL + "/v1/objectives/o-1")).andRespond(withException(new IOException("down")));

    mvc.perform(get("/internal/objectives/v1/objectives").param("pageSize", "5").param("workloadId", "w1").param("cursor", "n"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    mvc.perform(post("/internal/objectives/v1/objectives").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isCreated());
    mvc.perform(post("/internal/objectives/v1/objectives:local-idempotent-create")
            .header(HttpHeaders.AUTHORIZATION, "Bearer delegated")
            .header("Idempotency-Key", "local-1").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isCreated());
    mvc.perform(post("/internal/objectives/v1/objectives:local-idempotent-create")
            .header(HttpHeaders.AUTHORIZATION, "Bearer delegated")
            .header("Idempotency-Key", "local-1").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isOk());
    mvc.perform(
            post("/internal/objectives/v1/objective-imports:preview")
                .header("Idempotency-Key", "preview-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isAccepted());
    mvc.perform(post("/internal/objectives/v1/objectives/o-1:idempotent-submit")
            .header(HttpHeaders.AUTHORIZATION, "Bearer delegated")
            .header("Idempotency-Key", "submission-1").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isOk());
    mvc.perform(post("/internal/objectives/v1/objectives/o-1:idempotent-decide")
            .header(HttpHeaders.AUTHORIZATION, "Bearer delegated")
            .header("Idempotency-Key", "review-1").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isConflict());
    mvc.perform(post("/internal/objectives/v1/objectives/o-1:idempotent-activate")
            .header(HttpHeaders.AUTHORIZATION, "Bearer delegated")
            .header("Idempotency-Key", "activation-1").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isConflict());
    mvc.perform(get("/internal/objectives/v1/objective-imports/import-1"))
        .andExpect(status().isAccepted());
    mvc.perform(post("/internal/objectives/v1/objectives/o-1:decide").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isOk());
    mvc.perform(get("/internal/objectives/v1/objectives/o-1/decision-history")
            .header(HttpHeaders.AUTHORIZATION, "Bearer delegated"))
        .andExpect(status().isForbidden()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    mvc.perform(put("/internal/objectives/v1/organization-constraints/latency.p95").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isOk());
    mvc.perform(get("/internal/objectives/v1/objectives/o-1")).andExpect(status().isBadGateway());
    mvc.perform(post("/internal/objectives/internal/v1/workloads/w1/objectives:receipt").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isNotFound());
    server.verify();
  }
}
