package io.uacrf.discovery.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.uacrf.crypto.AwsSigV4SignerSupport;
import io.uacrf.crypto.AwsSigV4SignerSupport.AwsCredentials;
import io.uacrf.crypto.GcpOAuthAssertionSupport;
import io.uacrf.discovery.api.DiscoveryDomainException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Document;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Resolves read-only AWS credentials for S03 discovery & telemetry polling (strictly separate from S11 execution).
 * Supports explicit AWS keys / STS AssumeRole / AWS_* env vars / ~/.aws/credentials INI profiles / proxy bearer token.
 */
@Component
public class AwsCredentialProvider {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String region;
    private final String accessKeyId;
    private final String secretAccessKey;
    private final String sessionToken;
    private final String credentialsFile;
    private final String profile;
    private final String roleArn;
    private final String externalId;
    private final String stsUrl;
    private final String explicitBearerToken;
    private final AtomicReference<CachedStsCredentials> stsCache = new AtomicReference<>();
    private RestClient restClient;

    @Autowired
    public AwsCredentialProvider(
            @Value("${uacrf.discovery.adapters.aws.region:us-east-1}") String region,
            @Value("${uacrf.discovery.adapters.aws.access-key-id:}") String accessKeyId,
            @Value("${uacrf.discovery.adapters.aws.secret-access-key:}") String secretAccessKey,
            @Value("${uacrf.discovery.adapters.aws.session-token:}") String sessionToken,
            @Value("${uacrf.discovery.adapters.aws.credentials-file:}") String credentialsFile,
            @Value("${uacrf.discovery.adapters.aws.profile:default}") String profile,
            @Value("${uacrf.discovery.adapters.aws.role-arn:}") String roleArn,
            @Value("${uacrf.discovery.adapters.aws.external-id:}") String externalId,
            @Value("${uacrf.discovery.adapters.aws.sts-url:}") String stsUrl,
            @Value("${uacrf.discovery.adapters.aws.bearer-token:}") String explicitBearerToken) {
        this.region = region;
        this.accessKeyId = accessKeyId;
        this.secretAccessKey = secretAccessKey;
        this.sessionToken = sessionToken;
        this.credentialsFile = credentialsFile;
        this.profile = profile;
        this.roleArn = roleArn;
        this.externalId = externalId;
        this.stsUrl = (stsUrl != null && !stsUrl.isBlank())
                ? stsUrl.trim()
                : "https://sts." + region + ".amazonaws.com/";
        this.explicitBearerToken = explicitBearerToken == null ? "" : explicitBearerToken.trim();
        this.restClient = RestClient.create();
    }

    public void setRestClientForTesting(RestClient customClient) {
        this.restClient = customClient;
        this.stsCache.set(null);
    }

    public String getExplicitBearerToken() {
        return explicitBearerToken;
    }

    public AwsCredentials resolveSigV4Credentials() {
        AwsCredentials base = AwsSigV4SignerSupport.resolveCredentials(
                accessKeyId, secretAccessKey, sessionToken, credentialsFile, profile,
                System.getenv(), System.getProperty("user.home", ""));
        if (!base.isComplete()) {
            return base;
        }
        if (roleArn == null || roleArn.isBlank()) {
            return base;
        }
        CachedStsCredentials cached = stsCache.get();
        if (cached != null && cached.expiresAt().isAfter(Instant.now().plusSeconds(30))) {
            return cached.credentials();
        }
        if (GcpOAuthAssertionSupport.isUnsafeEgressHost(stsUrl)) {
            throw new DiscoveryDomainException(HttpStatus.FORBIDDEN, "SSRF_EGRESS_BLOCKED",
                    "Blocked unsafe AWS STS endpoint host [" + URI.create(stsUrl).getHost() + "].");
        }
        try {
            StringBuilder form = new StringBuilder("Action=AssumeRole&Version=2011-06-15")
                    .append("&RoleArn=").append(urlEnc(roleArn.trim()))
                    .append("&RoleSessionName=uacrf-discovery-ro")
                    .append("&DurationSeconds=900");
            if (externalId != null && !externalId.isBlank()) {
                form.append("&ExternalId=").append(urlEnc(externalId.trim()));
            }
            byte[] bodyBytes = form.toString().getBytes(StandardCharsets.UTF_8);
            URI uri = URI.create(stsUrl);
            Map<String, String> signed = AwsSigV4SignerSupport.signRequest(
                    "POST", uri, "sts", region,
                    Map.of("Content-Type", "application/x-www-form-urlencoded"),
                    bodyBytes, base, Instant.now());

            var spec = restClient.post().uri(uri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form.toString());
            signed.forEach(spec::header);
            String raw = spec.retrieve().body(String.class);
            AwsCredentials assumed = parseStsResponse(raw);
            if (!assumed.isComplete()) {
                throw new DiscoveryDomainException(HttpStatus.PRECONDITION_FAILED, "INVALID_AWS_CREDENTIALS",
                        "AWS STS AssumeRole returned incomplete temporary credentials.");
            }
            stsCache.set(new CachedStsCredentials(assumed, Instant.now().plusSeconds(840)));
            return assumed;
        } catch (DiscoveryDomainException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new DiscoveryDomainException(HttpStatus.PRECONDITION_FAILED, "INVALID_AWS_CREDENTIALS",
                    "Failed to resolve AWS read-only STS credentials: " + ex.getMessage());
        }
    }

    private AwsCredentials parseStsResponse(String raw) throws Exception {
        if (raw == null || raw.isBlank()) {
            return new AwsCredentials("", "", "");
        }
        if (raw.trim().startsWith("{")) {
            JsonNode creds = objectMapper.readTree(raw).path("AssumeRoleResponse").path("AssumeRoleResult").path("Credentials");
            if (creds.isMissingNode()) {
                creds = objectMapper.readTree(raw).path("Credentials");
            }
            return new AwsCredentials(
                    creds.path("AccessKeyId").asText(""),
                    creds.path("SecretAccessKey").asText(""),
                    creds.path("SessionToken").asText("")
            );
        }
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document doc = dbf.newDocumentBuilder().parse(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
        String keyId = textTag(doc, "AccessKeyId");
        String secret = textTag(doc, "SecretAccessKey");
        String token = textTag(doc, "SessionToken");
        return new AwsCredentials(keyId, secret, token);
    }

    private static String textTag(Document doc, String tag) {
        var nodes = doc.getElementsByTagName(tag);
        return nodes.getLength() > 0 ? nodes.item(0).getTextContent().trim() : "";
    }

    private static String urlEnc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    private record CachedStsCredentials(AwsCredentials credentials, Instant expiresAt) {
    }
}
