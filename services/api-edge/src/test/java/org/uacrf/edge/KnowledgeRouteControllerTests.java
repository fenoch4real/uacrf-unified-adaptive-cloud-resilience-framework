package org.uacrf.edge;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
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

class KnowledgeRouteControllerTests {
  private static final String URL = "http://knowledge.test";

  @Test
  void routesPublicMetadataReviewAndRetrievalButNotInternalReceipt() throws Exception {
    RestClient.Builder builder = RestClient.builder().baseUrl(URL);
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(
                new KnowledgeRouteController(new KnowledgeRouteClient(builder.build())))
            .build();

    server
        .expect(requestTo(URL + "/v1/knowledge-items?item_id=item-1&purpose=incident"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("[]"));
    server
        .expect(requestTo(URL + "/v1/knowledge-items"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("{}"));
    server
        .expect(requestTo(URL + "/v1/knowledge-items:idempotent"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Idempotency-Key", "retry-key"))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer delegated"))
        .andExpect(header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
        .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("{}"));
    server
        .expect(requestTo(URL + "/v1/knowledge-items/item-1:review"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{}"));
    server
        .expect(requestTo(URL + "/v1/knowledge-items/item-1/versions/v1:candidate-content"))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer reader"))
        .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{}"));
    server
        .expect(requestTo(URL + "/v1/knowledge-items/item-1:review-bound"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer reviewer"))
        .andExpect(header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
        .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{}"));
    server
        .expect(requestTo(URL + "/v1/knowledge-items/item-1:withdraw"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{}"));
    server
        .expect(requestTo(URL + "/v1/knowledge-items/item-1:withdraw-bound"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer withdrawer"))
        .andExpect(header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
        .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{}"));
    server
        .expect(requestTo(URL + "/v1/retrievals"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("{}"));
    server
        .expect(requestTo(URL + "/v1/retrievals:idempotent"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Idempotency-Key", "retrieval-key"))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer searcher"))
        .andExpect(header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
        .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("{}"));
    server
        .expect(requestTo(URL + "/v1/retrievals?limit=5&after=12345678-1234-4234-8234-123456789012"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer reader"))
        .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{}"));
    server
        .expect(requestTo(URL + "/v1/retrievals/r-1"))
        .andRespond(withException(new IOException("down")));

    mvc.perform(get("/internal/knowledge/v1/knowledge-items").param("item_id", "item-1").param("purpose", "incident"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    mvc.perform(post("/internal/knowledge/v1/knowledge-items").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isCreated());
    mvc.perform(post("/internal/knowledge/v1/knowledge-items:idempotent")
            .header("Idempotency-Key", "retry-key").header(HttpHeaders.AUTHORIZATION, "Bearer delegated")
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isCreated());
    mvc.perform(post("/internal/knowledge/v1/knowledge-items/item-1:review").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isOk());
    mvc.perform(get("/internal/knowledge/v1/knowledge-items/item-1/versions/v1:candidate-content")
            .header(HttpHeaders.AUTHORIZATION, "Bearer reader")).andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    mvc.perform(post("/internal/knowledge/v1/knowledge-items/item-1:review-bound")
            .header(HttpHeaders.AUTHORIZATION, "Bearer reviewer").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isOk());
    mvc.perform(post("/internal/knowledge/v1/knowledge-items/item-1:withdraw").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isOk());
    mvc.perform(post("/internal/knowledge/v1/knowledge-items/item-1:withdraw-bound")
            .header(HttpHeaders.AUTHORIZATION, "Bearer withdrawer").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isOk());
    mvc.perform(post("/internal/knowledge/v1/retrievals").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isCreated());
    mvc.perform(post("/internal/knowledge/v1/retrievals:idempotent")
            .header("Idempotency-Key", "retrieval-key").header(HttpHeaders.AUTHORIZATION, "Bearer searcher")
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isCreated()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    mvc.perform(get("/internal/knowledge/v1/retrievals").param("limit", "5")
            .param("after", "12345678-1234-4234-8234-123456789012")
            .header(HttpHeaders.AUTHORIZATION, "Bearer reader"))
        .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    mvc.perform(get("/internal/knowledge/v1/retrievals/r-1")).andExpect(status().isBadGateway());
    mvc.perform(post("/internal/knowledge/internal/v1/retrievals/r-1:receipt").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isNotFound());
    server.verify();
  }
}
