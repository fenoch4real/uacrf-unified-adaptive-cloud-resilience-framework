package org.uacrf.edge;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
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

class ConnectionsRouteControllerTests {
  private static final String URL = "http://connections.test";

  @Test
  void forwardsMutationsAndReadsToTheFixedOwner() throws Exception {
    RestClient.Builder builder = RestClient.builder().baseUrl(URL);
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(
                new ConnectionsRouteController(new ConnectionsRouteClient(builder.build())))
            .build();
    for (String path : new String[] {"/v1/connections", "/v1/connections/c-1/source-bindings"}) {
      server
          .expect(requestTo(URL + path))
          .andExpect(method(HttpMethod.POST))
          .andExpect(
              org.springframework.test.web.client.match.MockRestRequestMatchers.header(
                  "Idempotency-Key", "request-key"))
          .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("{}"));
    }
    server
        .expect(requestTo(URL + "/v1/connections?pageSize=5&cursor=n"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withStatus(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{}"));
    server
        .expect(requestTo(URL + "/v1/connections/c-1"))
        .andRespond(withException(new IOException("down")));

    for (String path : new String[] {"/internal/connections/v1/connections", "/internal/connections/v1/connections/c-1/source-bindings"}) {
      mvc.perform(
              post(path)
                  .header(HttpHeaders.AUTHORIZATION, "Bearer t")
                  .header("Idempotency-Key", "request-key")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{}"))
          .andExpect(status().isCreated())
          .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    }
    mvc.perform(get("/internal/connections/v1/connections").param("pageSize", "5").param("cursor", "n"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
    mvc.perform(get("/internal/connections/v1/connections/c-1")).andExpect(status().isBadGateway());
    server.verify();
  }

  @Test
  void forwardsObservationIntakeWithoutAnIdempotencyKeyAndReadsQuarantine() throws Exception {
    RestClient.Builder builder = RestClient.builder().baseUrl(URL);
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(
                new ConnectionsRouteController(new ConnectionsRouteClient(builder.build())))
            .build();
    server
        .expect(requestTo(URL + "/v1/connections/c-1/observations"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON).body("{}"));
    server
        .expect(requestTo(URL + "/v1/connections/c-1/quarantine?pageSize=10"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{}"));
    server
        .expect(requestTo(URL + "/v1/connections/c-1/observations?pageSize=25"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{}"));

    mvc.perform(
            post("/internal/connections/v1/connections/c-1/observations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer t")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isAccepted());
    mvc.perform(get("/internal/connections/v1/connections/c-1/quarantine").param("pageSize", "10"))
        .andExpect(status().isOk());
    mvc.perform(get("/internal/connections/v1/connections/c-1/observations"))
        .andExpect(status().isOk());
    server.verify();
  }
}
