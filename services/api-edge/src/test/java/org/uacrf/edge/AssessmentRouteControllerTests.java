package org.uacrf.edge;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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

class AssessmentRouteControllerTests {
  private static final String URL = "http://assessment.test";

  @Test
  void forwardsCaseCreationIdempotencyKey() throws Exception {
    RestClient.Builder builder = RestClient.builder().baseUrl(URL);
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    MockMvc mvc = MockMvcBuilders.standaloneSetup(
        new AssessmentRouteController(new AssessmentRouteClient(builder.build()))).build();
    server.expect(requestTo(URL + "/v1/cases:idempotent-create"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.header(
            "Idempotency-Key", "case-key"))
        .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("{}"));
    mvc.perform(post("/internal/assessment/v1/cases:idempotent-create")
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/internal/assessment/v1/cases:idempotent-create")
            .header("Idempotency-Key", "case-key").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isCreated());
    server.verify();
  }

  @Test
  void forwardsEvaluationIdempotencyKeyAndRequiresHeader() throws Exception {
    RestClient.Builder builder = RestClient.builder().baseUrl(URL);
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    MockMvc mvc = MockMvcBuilders.standaloneSetup(
        new AssessmentRouteController(new AssessmentRouteClient(builder.build()))).build();
    server.expect(requestTo(URL + "/v1/cases/case-1:idempotent-evaluate"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.header(
            "Idempotency-Key", "evaluation-key"))
        .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.header(
            HttpHeaders.AUTHORIZATION, "Bearer writer"))
        .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("{}"));
    mvc.perform(post("/internal/assessment/v1/cases/case-1:idempotent-evaluate")
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/internal/assessment/v1/cases/case-1:idempotent-evaluate")
            .header("Idempotency-Key", "evaluation-key").header(HttpHeaders.AUTHORIZATION, "Bearer writer")
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isCreated());
    server.verify();
  }

  @Test
  void forwardsPublicOperationsAndLeavesInternalReceiptUnrouted() throws Exception {
    RestClient.Builder builder = RestClient.builder().baseUrl(URL);
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(
                new AssessmentRouteController(new AssessmentRouteClient(builder.build())))
            .build();
    server
        .expect(requestTo(URL + "/v1/cases?workload_id=w1"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withStatus(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{}"));
    server
        .expect(requestTo(URL + "/v1/cases"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("{}"));
    server
        .expect(requestTo(URL + "/v1/cases/c-1:seal"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{}"));
    server.expect(requestTo(URL + "/v1/cases/c-1")).andExpect(method(HttpMethod.GET))
        .andRespond(withException(new IOException("down")));
    server.expect(requestTo(URL + "/v1/cases")).andExpect(method(HttpMethod.GET))
        .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("[]"));
    server.expect(requestTo(URL + "/v1/cases/c-1:evaluate")).andExpect(method(HttpMethod.POST))
        .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("{}"));
    server.expect(requestTo(URL + "/v1/cases/c-1/evaluations")).andExpect(method(HttpMethod.GET))
        .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("[]"));
    server.expect(requestTo(URL + "/v1/cases/c-1/evaluations:latest")).andExpect(method(HttpMethod.GET))
        .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{}"));
    server.expect(requestTo(URL + "/v1/evaluations/e-1")).andExpect(method(HttpMethod.GET))
        .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{}"));
    server.expect(requestTo(URL + "/v1/recommendations?workload_id=w1"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("[]"));
    server.expect(requestTo(URL + "/v1/model-versions")).andExpect(method(HttpMethod.POST))
    .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("{}"));
    server.expect(requestTo(URL + "/v1/model-versions?model_id=m1")).andExpect(method(HttpMethod.GET))
    .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("[]"));
    server.expect(requestTo(URL + "/v1/model-versions/mv-1")).andExpect(method(HttpMethod.GET))
    .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{}"));
    server.expect(requestTo(URL + "/v1/model-versions/mv-1:review")).andExpect(method(HttpMethod.POST))
    .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{}"));
    server.expect(requestTo(URL + "/v1/model-versions/mv-1:promote")).andExpect(method(HttpMethod.POST))
    .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{}"));
    server.expect(requestTo(URL + "/v1/incidents"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("{}"));
    server.expect(requestTo(URL + "/v1/incidents/i-1"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{}"));
    server.expect(requestTo(URL + "/v1/learning-records"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("{}"));
    server.expect(requestTo(URL + "/v1/learning-records/l-1"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.header(
            HttpHeaders.AUTHORIZATION, "Bearer learning-reader"))
        .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON).body("{}"));
    server.expect(requestTo(URL + "/v1/learning-records/l-1"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.header(
            HttpHeaders.AUTHORIZATION, "Bearer learning-reader"))
        .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
            .body("{\"id\":\"l-1\",\"state\":\"PROPOSED\"}"));
    server.expect(requestTo(URL + "/v1/learning-records/l-1:review"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.header(
            HttpHeaders.AUTHORIZATION, "Bearer reviewer"))
        .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
            .body("{\"code\":\"FEATURE_DISABLED\"}"));
    server.expect(requestTo(URL + "/v1/learning-records/l-1/review"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
            .body("{\"decision\":\"REJECT\"}"));
    server.expect(requestTo(URL + "/v1/incidents/i-1/learning-records?limit=1&after=cursor"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.header(
            HttpHeaders.AUTHORIZATION, "Bearer learning-reader"))
        .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
            .body("{\"items\":[],\"next_cursor\":null}"));
    server.expect(requestTo(URL + "/v1/incidents/i-1/learning-records"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON).body("{}"));
    server.expect(requestTo(URL + "/v1/cases/c-1/incidents?limit=1&after=cursor"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.header(
            HttpHeaders.AUTHORIZATION, "Bearer incident-reader"))
        .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
            .body("{\"items\":[],\"next_cursor\":null}"));
    server.expect(requestTo(URL + "/v1/cases/c-1/incidents"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON).body("{}"));

    mvc.perform(get("/internal/assessment/v1/cases").param("workload_id", "w1"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    mvc.perform(post("/internal/assessment/v1/cases").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isCreated());
    mvc.perform(post("/internal/assessment/v1/cases/c-1:seal").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isOk());
    mvc.perform(get("/internal/assessment/v1/cases/c-1")).andExpect(status().isBadGateway());
    mvc.perform(get("/internal/assessment/v1/cases")).andExpect(status().isOk());
    mvc.perform(post("/internal/assessment/v1/cases/c-1:evaluate")
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isCreated());
    mvc.perform(get("/internal/assessment/v1/cases/c-1/evaluations")).andExpect(status().isOk());
    mvc.perform(get("/internal/assessment/v1/cases/c-1/evaluations:latest")).andExpect(status().isOk());
    mvc.perform(get("/internal/assessment/v1/evaluations/e-1")).andExpect(status().isOk());
    mvc.perform(get("/internal/assessment/v1/recommendations").param("workload_id", "w1"))
        .andExpect(status().isOk());
    mvc.perform(post("/internal/assessment/v1/model-versions").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isCreated());
    mvc.perform(get("/internal/assessment/v1/model-versions").param("model_id", "m1"))
        .andExpect(status().isOk());
    mvc.perform(get("/internal/assessment/v1/model-versions/mv-1")).andExpect(status().isOk());
    mvc.perform(post("/internal/assessment/v1/model-versions/mv-1:review")
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isOk());
    mvc.perform(post("/internal/assessment/v1/model-versions/mv-1:promote")
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isOk());
    mvc.perform(post("/internal/assessment/v1/incidents")
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isCreated());
    mvc.perform(get("/internal/assessment/v1/incidents/i-1")).andExpect(status().isOk());
    mvc.perform(post("/internal/assessment/v1/learning-records")
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isCreated());
    mvc.perform(get("/internal/assessment/v1/learning-records/l-1")
            .header(HttpHeaders.AUTHORIZATION, "Bearer learning-reader"))
        .andExpect(status().isForbidden())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    mvc.perform(get("/internal/assessment/v1/learning-records/l-1")
            .header(HttpHeaders.AUTHORIZATION, "Bearer learning-reader"))
        .andExpect(status().isOk())
        .andExpect(content().json("{\"id\":\"l-1\",\"state\":\"PROPOSED\"}"));
    mvc.perform(post("/internal/assessment/v1/learning-records/l-1:review")
            .header(HttpHeaders.AUTHORIZATION, "Bearer reviewer")
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isConflict())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(content().json("{\"code\":\"FEATURE_DISABLED\"}"));
    mvc.perform(get("/internal/assessment/v1/learning-records/l-1/review"))
        .andExpect(status().isOk())
        .andExpect(content().json("{\"decision\":\"REJECT\"}"));
    mvc.perform(get("/internal/assessment/v1/incidents/i-1/learning-records")
            .param("limit", "1").param("after", "cursor")
            .header(HttpHeaders.AUTHORIZATION, "Bearer learning-reader"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(content().json("{\"items\":[],\"next_cursor\":null}"));
    mvc.perform(get("/internal/assessment/v1/incidents/i-1/learning-records"))
        .andExpect(status().isForbidden())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    mvc.perform(get("/internal/assessment/v1/cases/c-1/incidents")
            .param("limit", "1").param("after", "cursor")
            .header(HttpHeaders.AUTHORIZATION, "Bearer incident-reader"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(content().json("{\"items\":[],\"next_cursor\":null}"));
    mvc.perform(get("/internal/assessment/v1/cases/c-1/incidents"))
        .andExpect(status().isForbidden())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    mvc.perform(post("/internal/assessment/internal/v1/cases/c-1:receipt")
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isNotFound());
    mvc.perform(post("/internal/assessment/internal/v1/learning-records/l-1:review-receipt")
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isNotFound());
    mvc.perform(post("/internal/assessment/internal/v1/adaptation-proposals")
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isNotFound());
    mvc.perform(get("/internal/assessment/internal/v1/adaptation-proposals/a-1"))
        .andExpect(status().isNotFound());
    server.verify();
  }
}
