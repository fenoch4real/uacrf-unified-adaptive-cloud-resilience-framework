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

class EvidenceRouteControllerTests {
  private static final String URL = "http://evidence.test";

  @Test
  void forwardsReadsAndReviewToTheFixedOwnerAndDoesNotRouteInternalEndpoints() throws Exception {
    RestClient.Builder builder = RestClient.builder().baseUrl(URL);
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(new EvidenceRouteController(new EvidenceRouteClient(builder.build())))
            .build();
    server
        .expect(requestTo(URL + "/v1/evidence?pageSize=5&cursor=n"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withStatus(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{}"));
    server
        .expect(requestTo(URL + "/v1/evidence/e-1/reviews"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{}"));
    server.expect(requestTo(URL + "/v1/evidence/e-1")).andRespond(withException(new IOException("down")));

    mvc.perform(get("/internal/evidence/v1/evidence").param("pageSize", "5").param("cursor", "n"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    mvc.perform(
            post("/internal/evidence/v1/evidence/e-1/reviews")
                .header(HttpHeaders.AUTHORIZATION, "Bearer t")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk());
    mvc.perform(get("/internal/evidence/v1/evidence/e-1")).andExpect(status().isBadGateway());
    mvc.perform(post("/internal/evidence/internal/v1/observations").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isNotFound());
    server.verify();
  }
}
