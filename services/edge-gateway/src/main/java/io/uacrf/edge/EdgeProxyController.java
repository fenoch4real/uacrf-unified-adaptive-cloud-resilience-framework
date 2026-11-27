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
    private final RestClient discoveryClient;
    private final RestClient evidenceClient;
    private final RestClient graphClient;
    private final RestClient assessmentClient;
    private final RestClient objectivesClient;
    private final RestClient policyClient;
    private final RestClient recoveryClient;
    private final RestClient knowledgeClient;
    private final RestClient advisoryClient;

    public EdgeProxyController(
            @Value("${uacrf.edge.identity-base-url}") String identityBaseUrl,
            @Value("${uacrf.edge.catalog-base-url}") String catalogBaseUrl,
            @Value("${uacrf.edge.discovery-base-url}") String discoveryBaseUrl,
            @Value("${uacrf.edge.evidence-base-url}") String evidenceBaseUrl,
            @Value("${uacrf.edge.graph-base-url}") String graphBaseUrl,
            @Value("${uacrf.edge.assessment-base-url}") String assessmentBaseUrl,
            @Value("${uacrf.edge.objectives-base-url}") String objectivesBaseUrl,
            @Value("${uacrf.edge.policy-base-url}") String policyBaseUrl,
            @Value("${uacrf.edge.recovery-base-url}") String recoveryBaseUrl,
            @Value("${uacrf.edge.knowledge-base-url}") String knowledgeBaseUrl,
            @Value("${uacrf.edge.advisory-base-url}") String advisoryBaseUrl,
            @Value("${uacrf.edge.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${uacrf.edge.read-timeout-ms:10000}") int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        this.identityClient = RestClient.builder().baseUrl(identityBaseUrl).requestFactory(factory).build();
        this.catalogClient = RestClient.builder().baseUrl(catalogBaseUrl).requestFactory(factory).build();
        this.discoveryClient = RestClient.builder().baseUrl(discoveryBaseUrl).requestFactory(factory).build();
        this.evidenceClient = RestClient.builder().baseUrl(evidenceBaseUrl).requestFactory(factory).build();
        this.graphClient = RestClient.builder().baseUrl(graphBaseUrl).requestFactory(factory).build();
        this.assessmentClient = RestClient.builder().baseUrl(assessmentBaseUrl).requestFactory(factory).build();
        this.objectivesClient = RestClient.builder().baseUrl(objectivesBaseUrl).requestFactory(factory).build();
        this.policyClient = RestClient.builder().baseUrl(policyBaseUrl).requestFactory(factory).build();
        this.recoveryClient = RestClient.builder().baseUrl(recoveryBaseUrl).requestFactory(factory).build();
        this.knowledgeClient = RestClient.builder().baseUrl(knowledgeBaseUrl).requestFactory(factory).build();
        this.advisoryClient = RestClient.builder().baseUrl(advisoryBaseUrl).requestFactory(factory).build();
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

    @RequestMapping({"/v1/connections", "/v1/connections/**", "/v1/telemetry/**", "/v1/discovery/**"})
    public ResponseEntity<byte[]> routeToDiscovery(HttpServletRequest request,
                                                   @RequestBody(required = false) byte[] body) {
        return forward(discoveryClient, request, body, "DISCOVERY_UNAVAILABLE");
    }

    @RequestMapping({"/v1/evidence", "/v1/evidence/**"})
    public ResponseEntity<byte[]> routeToEvidence(HttpServletRequest request,
                                                  @RequestBody(required = false) byte[] body) {
        return forward(evidenceClient, request, body, "EVIDENCE_UNAVAILABLE");
    }

    @RequestMapping({"/v1/graph", "/v1/graph/**"})
    public ResponseEntity<byte[]> routeToGraph(HttpServletRequest request,
                                               @RequestBody(required = false) byte[] body) {
        return forward(graphClient, request, body, "GRAPH_UNAVAILABLE");
    }

    @RequestMapping({"/v1/assessments", "/v1/assessments/**", "/v1/path-certifications/**", "/v1/decision-replays/**"})
    public ResponseEntity<byte[]> routeToAssessment(HttpServletRequest request,
                                                    @RequestBody(required = false) byte[] body) {
        return forward(assessmentClient, request, body, "ASSESSMENT_UNAVAILABLE");
    }

    @RequestMapping({"/v1/objectives", "/v1/objectives/**", "/v1/control/**"})
    public ResponseEntity<byte[]> routeToObjectives(HttpServletRequest request,
                                                    @RequestBody(required = false) byte[] body) {
        return forward(objectivesClient, request, body, "OBJECTIVES_UNAVAILABLE");
    }

    @RequestMapping({"/v1/policy", "/v1/policy/**"})
    public ResponseEntity<byte[]> routeToPolicy(HttpServletRequest request,
                                                @RequestBody(required = false) byte[] body) {
        return forward(policyClient, request, body, "POLICY_UNAVAILABLE");
    }

    @RequestMapping({"/v1/recovery", "/v1/recovery/**"})
    public ResponseEntity<byte[]> routeToRecovery(HttpServletRequest request,
                                                  @RequestBody(required = false) byte[] body) {
        return forward(recoveryClient, request, body, "RECOVERY_UNAVAILABLE");
    }

    @RequestMapping({"/v1/knowledge/**", "/v1/learning/**", "/v1/experiments", "/v1/experiments/**", "/v1/quantitative/**"})
    public ResponseEntity<byte[]> routeToKnowledge(HttpServletRequest request,
                                                   @RequestBody(required = false) byte[] body) {
        return forward(knowledgeClient, request, body, "KNOWLEDGE_UNAVAILABLE");
    }

    @RequestMapping({"/v1/advisory", "/v1/advisory/**"})
    public ResponseEntity<byte[]> routeToAdvisory(HttpServletRequest request,
                                                  @RequestBody(required = false) byte[] body) {
        return forward(advisoryClient, request, body, "ADVISORY_UNAVAILABLE");
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
