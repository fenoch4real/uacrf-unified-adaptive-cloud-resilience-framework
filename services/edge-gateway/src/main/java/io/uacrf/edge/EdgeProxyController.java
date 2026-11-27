package io.uacrf.edge;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * EDGE01 API Gateway routing public /v1 paths to S01 and S02 while keeping internal
 * endpoints (e.g. /v1/delegations/exchange) blocked from public ingress and enforcing no-store caching.
 */
@RestController
public class EdgeProxyController {

    private static final List<String> FORWARDED_HEADERS = List.of(
            "Authorization", "X-UACRF-Actor-Id", "Idempotency-Key", "X-Correlation-Id", "If-Match", "Content-Type"
    );

    private final RestClient identityClient;
    private final RestClient catalogClient;

    public EdgeProxyController(
            @Value("${uacrf.edge.identity-base-url}") String identityBaseUrl,
            @Value("${uacrf.edge.catalog-base-url}") String catalogBaseUrl,
            @Value("${uacrf.edge.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${uacrf.edge.read-timeout-ms:10000}") int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        this.identityClient = RestClient.builder().baseUrl(identityBaseUrl).requestFactory(factory).build();
        this.catalogClient = RestClient.builder().baseUrl(catalogBaseUrl).requestFactory(factory).build();
    }

    @RequestMapping({"/v1/tenants/**", "/v1/principals", "/v1/principals/**", "/v1/owner-candidates",
            "/v1/grants/**", "/v1/memberships", "/v1/memberships/**"})
    public ResponseEntity<byte[]> routeToIdentity(HttpServletRequest request,
                                                  @RequestBody(required = false) byte[] body) {
        return forward(identityClient, request, body, "IDENTITY_UNAVAILABLE");
    }

    @RequestMapping({"/v1/services", "/v1/services/**", "/v1/agent-workloads", "/v1/agent-workloads/**",
            "/v1/customer-journeys", "/v1/customer-journeys/**", "/v1/shared-resources", "/v1/shared-resources/**"})
    public ResponseEntity<byte[]> routeToCatalog(HttpServletRequest request,
                                                 @RequestBody(required = false) byte[] body) {
        return forward(catalogClient, request, body, "CATALOG_UNAVAILABLE");
    }

    private ResponseEntity<byte[]> forward(RestClient client, HttpServletRequest request,
                                           byte[] body, String unavailableCode) {
        String uri = request.getRequestURI()
                + (request.getQueryString() != null ? "?" + request.getQueryString() : "");
        try {
            RestClient.RequestBodySpec spec = client.method(HttpMethod.valueOf(request.getMethod())).uri(uri);
            for (String header : FORWARDED_HEADERS) {
                String value = request.getHeader(header);
                if (value != null && !value.isBlank()) {
                    spec.header(header, value);
                }
            }
            if (body != null && body.length > 0) {
                spec.body(body);
            }
            ResponseEntity<byte[]> upstream = spec.retrieve().toEntity(byte[].class);
            HttpHeaders headers = new HttpHeaders();
            if (upstream.getHeaders().getContentType() != null) {
                headers.setContentType(upstream.getHeaders().getContentType());
            }
            return ResponseEntity.status(upstream.getStatusCode())
                    .cacheControl(CacheControl.noStore())
                    .headers(headers)
                    .body(upstream.getBody());
        } catch (RestClientResponseException ex) {
            HttpHeaders headers = new HttpHeaders();
            if (ex.getResponseHeaders() != null && ex.getResponseHeaders().getContentType() != null) {
                headers.setContentType(ex.getResponseHeaders().getContentType());
            } else {
                headers.setContentType(MediaType.APPLICATION_JSON);
            }
            return ResponseEntity.status(ex.getStatusCode())
                    .cacheControl(CacheControl.noStore())
                    .headers(headers)
                    .body(ex.getResponseBodyAsByteArray());
        } catch (ResourceAccessException ex) {
            String err = "{\"code\":\"" + unavailableCode + "\",\"message\":\"Downstream service unavailable\",\"timestamp\":\""
                    + Instant.now() + "\"}";
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .cacheControl(CacheControl.noStore())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(err.getBytes());
        }
    }
}
