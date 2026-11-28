package io.uacrf.discovery.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.uacrf.contracts.graph.Neo4jOntology;
import io.uacrf.crypto.CanonicalJson;
import io.uacrf.discovery.api.DiscoveryDomainException;
import io.uacrf.discovery.api.DiscoveryDtos.*;
import io.uacrf.discovery.service.PiiPhiScrubber;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Instant;
import java.util.*;

/**
 * Multi-cloud Catalog & Asset Inventory Poller Gateway for S03.
 * Supports read-only discovery profiles and live API response normalizers across
 * AWS (Resource Explorer / Config), GCP (Cloud Asset Inventory), AZURE (Resource Graph),
 * OCI (Resource Search), and OTEL behind fail-closed enabled=false config gates.
 */
@Component
public class CloudInventoryPollerGateway {

    private record NativeTypeRule(
            String ruleFamily,
            String nodeLabel,
            String relationshipType,
            String runtimeKind,
            String importance
    ) {
    }

    private static final List<ResourceFamilyDescriptor> RESOURCE_FAMILIES = List.of(
            new ResourceFamilyDescriptor("INF-CMP", "Compute & Autoscaling", "ComputeNode",
                    "EC2 Auto Scaling / AWS Config", "VM / VM Scale Sets", "GCE / Managed Instance Groups", "OCI Compute / Instance Pools"),
            new ResourceFamilyDescriptor("PLT-K8S", "Kubernetes Control & Workloads", "Cluster",
                    "Amazon EKS", "Azure AKS", "Google GKE", "OCI OKE"),
            new ResourceFamilyDescriptor("APP-SLO", "Serverless & App Runtimes", "ApplicationComponent",
                    "AWS Lambda / App Mesh", "Azure Functions / Container Apps", "Cloud Run / Cloud Functions", "OCI Functions"),
            new ResourceFamilyDescriptor("INF-NET", "Network, Load Balancing & DNS", "Network",
                    "VPC / ELB / Route 53", "VNet / Front Door / Traffic Manager / Azure DNS", "VPC / Cloud LB / Cloud DNS", "VCN / OCI LB / OCI DNS"),
            new ResourceFamilyDescriptor("INF-STO-OBJ", "Object Storage & Immutability", "DataStore",
                    "Amazon S3 + Object Lock", "Azure Blob + Immutability", "Cloud Storage + Bucket Lock", "OCI Object Storage + Retention"),
            new ResourceFamilyDescriptor("INF-STO-BLK", "Block & File Storage", "DataStore",
                    "EBS / EFS / FSx", "Managed Disks / Azure Files", "Persistent Disk / Filestore", "OCI Block Volumes / FSS"),
            new ResourceFamilyDescriptor("INF-DB-REL", "Relational Databases", "Database",
                    "Amazon RDS / Aurora", "Azure SQL / Managed PostgreSQL", "Cloud SQL / AlloyDB / Spanner", "OCI Autonomous DB / MySQL"),
            new ResourceFamilyDescriptor("INF-DB-NOSQL", "NoSQL & Distributed Stores", "Database",
                    "Amazon DynamoDB", "Azure Cosmos DB", "Firestore / Bigtable", "OCI NoSQL Database"),
            new ResourceFamilyDescriptor("PLT-MSG", "Queues & Event Streams", "Queue",
                    "SQS / SNS / Kinesis / MSK", "Service Bus / Event Hubs", "Pub/Sub / Managed Kafka", "OCI Streaming / Queue"),
            new ResourceFamilyDescriptor("PLT-MSH", "API Gateways & Ingress Mesh", "API",
                    "API Gateway / ALB / NLB", "API Management / App Gateway", "Apigee / API Gateway / Service Mesh", "OCI API Gateway"),
            new ResourceFamilyDescriptor("PLT-CICD", "CI/CD & Artifact Deployment", "Deployment",
                    "CodePipeline / CodeDeploy", "Azure DevOps / GitHub Actions", "Cloud Build / Cloud Deploy", "OCI DevOps"),
            new ResourceFamilyDescriptor("PLT-OBS-INV", "Cloud Asset Inventory & Change", "EvidenceSource",
                    "AWS Config / Resource Explorer", "Azure Resource Graph / Activity Log", "Cloud Asset Inventory", "OCI Resource Search / Audit"),
            new ResourceFamilyDescriptor("PLT-OBS-TEL", "Metrics, Logs & Traces", "EvidenceSource",
                    "CloudWatch / X-Ray / OTel", "Azure Monitor / Log Analytics", "Cloud Monitoring / Logging / Trace", "OCI Monitoring / Logging"),
            new ResourceFamilyDescriptor("SEC-IAM", "Identity, KMS & Secrets", "Identity",
                    "IAM / KMS / Secrets Manager", "Entra ID / Key Vault / Managed Identity", "Cloud IAM / KMS / Secret Manager", "OCI IAM / Vault"),
            new ResourceFamilyDescriptor("SEC-POSTURE", "Security Posture & WAF", "Control",
                    "Security Hub / GuardDuty / WAF", "Defender for Cloud / Sentinel", "Security Command Center / Cloud Armor", "OCI Cloud Guard / WAF"),
            new ResourceFamilyDescriptor("GOV-RUN", "Backup & Disaster Recovery", "RecoveryPath",
                    "AWS Backup / Elastic DR / ARC", "Azure Backup / Site Recovery", "Backup and DR Service", "OCI Full Stack DR"),
            new ResourceFamilyDescriptor("AI-MODEL", "AI Models, Endpoints & Feature Stores", "ModelEndpoint",
                    "SageMaker / Bedrock", "Azure ML / Azure OpenAI", "Vertex AI / Feature Store", "OCI Data Science / GenAI"),
            new ResourceFamilyDescriptor("GOV-CMP", "Cloud Governance Policy", "Policy",
                    "AWS Config Conformance / SCP", "Azure Policy Initiatives", "Organization Policy / OPA", "OCI Security Zones"),
            new ResourceFamilyDescriptor("GOV-WFL", "Durable Recovery Orchestration", "Runbook",
                    "Temporal on EKS / SSM / Step Functions", "Temporal on AKS / Durable Functions", "Temporal on GKE / Cloud Workflows", "Temporal on OKE / OCI Functions"),
            new ResourceFamilyDescriptor("GOV-TST", "Controlled Fault Simulation", "ValidationPlan",
                    "AWS Fault Injection Service (FIS)", "Azure Chaos Studio", "GCP Fault Injection / Chaos Mesh", "Chaos Mesh on OKE")
    );

    private static final Map<String, NativeTypeRule> NATIVE_TYPE_RULES = Map.ofEntries(
            Map.entry("aws::ec2::autoscalinggroup", new NativeTypeRule("INF-CMP", "ComputeNode", "RUNS_ON", "ASG", "MANDATORY")),
            Map.entry("aws::eks::cluster", new NativeTypeRule("PLT-K8S", "Cluster", "DEPLOYED_IN", "EKS", "MANDATORY")),
            Map.entry("aws::lambda::function", new NativeTypeRule("APP-SLO", "ApplicationComponent", "RUNS_ON", "LAMBDA", "MANDATORY")),
            Map.entry("aws::elasticloadbalancingv2::loadbalancer", new NativeTypeRule("INF-NET", "Network", "ROUTES_TO", "ELB", "MANDATORY")),
            Map.entry("aws::route53::hostedzone", new NativeTypeRule("INF-NET", "DNSService", "USES_DNS", "DNS", "MANDATORY")),
            Map.entry("aws::s3::bucket", new NativeTypeRule("INF-STO-OBJ", "DataStore", "READS_FROM", "S3", "MANDATORY")),
            Map.entry("aws::rds::dbinstance", new NativeTypeRule("INF-DB-REL", "Database", "READS_FROM", "RDS", "MANDATORY")),
            Map.entry("aws::dynamodb::table", new NativeTypeRule("INF-DB-NOSQL", "Database", "WRITES_TO", "DYNAMODB", "MANDATORY")),
            Map.entry("aws::sqs::queue", new NativeTypeRule("PLT-MSG", "Queue", "PUBLISHES_TO", "SQS", "DEGRADABLE")),
            Map.entry("aws::apigatewayv2::api", new NativeTypeRule("PLT-MSH", "API", "ROUTES_TO", "APIGW", "MANDATORY")),
            Map.entry("aws::kms::key", new NativeTypeRule("SEC-IAM", "EncryptionKey", "USES_KEY", "KMS", "MANDATORY")),
            Map.entry("aws::sagemaker::endpoint", new NativeTypeRule("AI-MODEL", "ModelEndpoint", "CALLS", "SAGEMAKER", "MANDATORY")),
            Map.entry("compute.googleapis.com/instancegroupmanager", new NativeTypeRule("INF-CMP", "ComputeNode", "RUNS_ON", "MIG", "MANDATORY")),
            Map.entry("container.googleapis.com/cluster", new NativeTypeRule("PLT-K8S", "Cluster", "DEPLOYED_IN", "GKE", "MANDATORY")),
            Map.entry("run.googleapis.com/service", new NativeTypeRule("APP-SLO", "ApplicationComponent", "RUNS_ON", "CLOUD_RUN", "MANDATORY")),
            Map.entry("compute.googleapis.com/forwardingrule", new NativeTypeRule("INF-NET", "Network", "ROUTES_TO", "CLOUD_LB", "MANDATORY")),
            Map.entry("dns.googleapis.com/managedzone", new NativeTypeRule("INF-NET", "DNSService", "USES_DNS", "CLOUD_DNS", "MANDATORY")),
            Map.entry("storage.googleapis.com/bucket", new NativeTypeRule("INF-STO-OBJ", "DataStore", "READS_FROM", "GCS", "MANDATORY")),
            Map.entry("sqladmin.googleapis.com/instance", new NativeTypeRule("INF-DB-REL", "Database", "READS_FROM", "CLOUD_SQL", "MANDATORY")),
            Map.entry("pubsub.googleapis.com/topic", new NativeTypeRule("PLT-MSG", "Queue", "PUBLISHES_TO", "PUBSUB", "DEGRADABLE")),
            Map.entry("aiplatform.googleapis.com/endpoint", new NativeTypeRule("AI-MODEL", "ModelEndpoint", "CALLS", "VERTEX_AI", "MANDATORY")),
            Map.entry("microsoft.compute/virtualmachinescalesets", new NativeTypeRule("INF-CMP", "ComputeNode", "RUNS_ON", "VMSS", "MANDATORY")),
            Map.entry("microsoft.containerservice/managedclusters", new NativeTypeRule("PLT-K8S", "Cluster", "DEPLOYED_IN", "AKS", "MANDATORY")),
            Map.entry("microsoft.web/sites", new NativeTypeRule("APP-SLO", "ApplicationComponent", "RUNS_ON", "AZURE_FUNCTIONS", "MANDATORY")),
            Map.entry("microsoft.network/frontdoors", new NativeTypeRule("INF-NET", "Network", "ROUTES_TO", "FRONT_DOOR", "MANDATORY")),
            Map.entry("microsoft.storage/storageaccounts", new NativeTypeRule("INF-STO-OBJ", "DataStore", "READS_FROM", "AZURE_BLOB", "MANDATORY")),
            Map.entry("microsoft.sql/servers", new NativeTypeRule("INF-DB-REL", "Database", "READS_FROM", "AZURE_SQL", "MANDATORY")),
            Map.entry("microsoft.documentdb/databaseaccounts", new NativeTypeRule("INF-DB-NOSQL", "Database", "WRITES_TO", "COSMOS_DB", "MANDATORY")),
            Map.entry("microsoft.servicebus/namespaces", new NativeTypeRule("PLT-MSG", "Queue", "PUBLISHES_TO", "SERVICE_BUS", "DEGRADABLE")),
            Map.entry("microsoft.machinelearningservices/workspaces/onlineendpoints", new NativeTypeRule("AI-MODEL", "ModelEndpoint", "CALLS", "AZURE_ML", "MANDATORY")),
            Map.entry("oci:core:instancepool", new NativeTypeRule("INF-CMP", "ComputeNode", "RUNS_ON", "INSTANCE_POOL", "MANDATORY")),
            Map.entry("oci:containerengine:cluster", new NativeTypeRule("PLT-K8S", "Cluster", "DEPLOYED_IN", "OKE", "MANDATORY")),
            Map.entry("oci:database:autonomousdatabase", new NativeTypeRule("INF-DB-REL", "Database", "READS_FROM", "OCI_ADB", "MANDATORY")),
            Map.entry("oci:objectstorage:bucket", new NativeTypeRule("INF-STO-OBJ", "DataStore", "READS_FROM", "OCI_OSS", "MANDATORY")),
            Map.entry("oci:streaming:stream", new NativeTypeRule("PLT-MSG", "Queue", "PUBLISHES_TO", "OCI_STREAMING", "DEGRADABLE")),
            Map.entry("oci:datascience:modeldeployment", new NativeTypeRule("AI-MODEL", "ModelEndpoint", "CALLS", "OCI_AI", "MANDATORY"))
    );

    private final PiiPhiScrubber scrubber;
    private final GcpCredentialProvider gcpCredentialProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, CloudInventoryProfile> profiles;
    private final Map<String, String> bearerTokens;
    private RestClient restClient;

    @Autowired
    public CloudInventoryPollerGateway(
            PiiPhiScrubber scrubber,
            GcpCredentialProvider gcpCredentialProvider,
            @Value("${uacrf.discovery.adapters.aws.enabled:false}") boolean awsEnabled,
            @Value("${uacrf.discovery.adapters.aws.region:us-east-1}") String awsRegion,
            @Value("${uacrf.discovery.adapters.aws.auth-mode:IAM_ASSUME_ROLE}") String awsAuthMode,
            @Value("${uacrf.discovery.adapters.aws.role-arn:}") String awsRoleArn,
            @Value("${uacrf.discovery.adapters.aws.endpoint-url:}") String awsEndpointUrl,
            @Value("${uacrf.discovery.adapters.aws.bearer-token:}") String awsToken,
            @Value("${uacrf.discovery.adapters.gcp.enabled:false}") boolean gcpEnabled,
            @Value("${uacrf.discovery.adapters.gcp.project-id:uacrf-unconfigured}") String gcpProject,
            @Value("${uacrf.discovery.adapters.gcp.auth-mode:WORKLOAD_IDENTITY}") String gcpAuthMode,
            @Value("${uacrf.discovery.adapters.gcp.endpoint-url:}") String gcpEndpointUrl,
            @Value("${uacrf.discovery.adapters.gcp.bearer-token:}") String gcpToken,
            @Value("${uacrf.discovery.adapters.azure.enabled:false}") boolean azureEnabled,
            @Value("${uacrf.discovery.adapters.azure.subscription-id:unconfigured}") String azureSub,
            @Value("${uacrf.discovery.adapters.azure.auth-mode:MANAGED_IDENTITY}") String azureAuthMode,
            @Value("${uacrf.discovery.adapters.azure.endpoint-url:}") String azureEndpointUrl,
            @Value("${uacrf.discovery.adapters.azure.bearer-token:}") String azureToken,
            @Value("${uacrf.discovery.adapters.oci.enabled:false}") boolean ociEnabled,
            @Value("${uacrf.discovery.adapters.oci.tenancy-ocid:unconfigured}") String ociTenancy,
            @Value("${uacrf.discovery.adapters.oci.region:us-ashburn-1}") String ociRegion,
            @Value("${uacrf.discovery.adapters.oci.auth-mode:INSTANCE_PRINCIPAL}") String ociAuthMode,
            @Value("${uacrf.discovery.adapters.oci.endpoint-url:}") String ociEndpointUrl,
            @Value("${uacrf.discovery.adapters.oci.bearer-token:}") String ociToken) {
        this.scrubber = scrubber;
        this.gcpCredentialProvider = gcpCredentialProvider;
        this.restClient = RestClient.create();

        String resolvedAwsUrl = !isBlank(awsEndpointUrl)
                ? awsEndpointUrl
                : "https://resource-explorer-2." + awsRegion + ".amazonaws.com/Search";
        String resolvedGcpUrl = !isBlank(gcpEndpointUrl)
                ? gcpEndpointUrl
                : "https://cloudasset.googleapis.com/v1/projects/" + gcpProject + ":searchAllResources";
        String resolvedAzureUrl = !isBlank(azureEndpointUrl)
                ? azureEndpointUrl
                : "https://management.azure.com/providers/Microsoft.ResourceGraph/resources?api-version=2022-10-01";
        String resolvedOciUrl = !isBlank(ociEndpointUrl)
                ? ociEndpointUrl
                : "https://query." + ociRegion + ".oci.oraclecloud.com/20180409/resources";

        this.profiles = Map.of(
                "AWS", new CloudInventoryProfile("AWS", awsEnabled, awsAuthMode, resolvedAwsUrl,
                        "AWS Config / Resource Explorer 2", Map.of("region", awsRegion, "roleArn", nullToEmpty(awsRoleArn))),
                "GCP", new CloudInventoryProfile("GCP", gcpEnabled, gcpAuthMode, resolvedGcpUrl,
                        "Google Cloud Asset Inventory v1", Map.of("projectId", gcpProject)),
                "AZURE", new CloudInventoryProfile("AZURE", azureEnabled, azureAuthMode, resolvedAzureUrl,
                        "Azure Resource Graph v2022-10-01", Map.of("subscriptionId", azureSub)),
                "OCI", new CloudInventoryProfile("OCI", ociEnabled, ociAuthMode, resolvedOciUrl,
                        "OCI Search Service", Map.of("region", ociRegion, "tenancyOcid", ociTenancy))
        );
        this.bearerTokens = Map.of(
                "AWS", nullToEmpty(awsToken),
                "GCP", nullToEmpty(gcpToken),
                "AZURE", nullToEmpty(azureToken),
                "OCI", nullToEmpty(ociToken)
        );
    }

    /**
     * Allows unit and contract tests to inject a RestClient backed by MockRestServiceServer.
     */
    public void setRestClientForTesting(RestClient customClient) {
        this.restClient = customClient;
    }

    public List<CloudInventoryProfile> listProfiles() {
        return List.of("AWS", "GCP", "AZURE", "OCI").stream().map(profiles::get).toList();
    }

    public CloudInventoryProfile getProfile(String providerKey) {
        String norm = providerKey == null ? "" : providerKey.trim().toUpperCase(Locale.ROOT);
        CloudInventoryProfile profile = profiles.get(norm);
        if (profile == null) {
            throw new DiscoveryDomainException(HttpStatus.BAD_REQUEST, "UNKNOWN_CLOUD_PROVIDER",
                    "Unsupported cloud inventory provider [" + providerKey + "]. Supported: AWS, GCP, AZURE, OCI.");
        }
        return profile;
    }

    public List<ResourceFamilyDescriptor> listResourceFamilies() {
        return RESOURCE_FAMILIES;
    }

    public void validateSafeEndpoint(String url) {
        URI uri = URI.create(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            throw new DiscoveryDomainException(HttpStatus.FORBIDDEN, "INVALID_ENDPOINT_SCHEME",
                    "Unsupported endpoint scheme [" + scheme + "]");
        }
        if (host.equals("169.254.169.254") || host.equals("metadata.google.internal")
                || host.startsWith("169.254.") || host.equals("localhost") || host.equals("127.0.0.1")) {
            throw new DiscoveryDomainException(HttpStatus.FORBIDDEN, "SSRF_EGRESS_BLOCKED",
                    "Blocked unsafe cloud inventory target host [" + host + "] per SSRF egress policy.");
        }
    }

    public InventoryNormalizationResult pollAndNormalize(UUID tenantId, UUID environmentId, String providerKey, String scopeFilter) {
        CloudInventoryProfile profile = getProfile(providerKey);
        if (!profile.enabled()) {
            throw new DiscoveryDomainException(HttpStatus.PRECONDITION_FAILED, "PROVIDER_ADAPTER_DISABLED",
                    "Cloud inventory adapter [" + profile.provider() + "] is in config standby (enabled=false). "
                            + "Use /v1/inventory/normalize for offline payloads or configure read-only federated credentials.");
        }
        validateSafeEndpoint(profile.endpointUrl());
        String token = "GCP".equals(profile.provider())
                ? gcpCredentialProvider.resolveAccessToken()
                : bearerTokens.getOrDefault(profile.provider(), "");
        if (isBlank(token)) {
            throw new DiscoveryDomainException(HttpStatus.PRECONDITION_FAILED, "MISSING_READ_ONLY_CREDENTIALS",
                    "Read-only federated/workload credential is not configured for provider [" + profile.provider() + "].");
        }

        JsonNode responseTree = executeProviderQuery(profile, token, scopeFilter);
        List<RawCloudAssetRecord> rawAssets = parseProviderResponse(profile.provider(), responseTree);
        return normalizeBatch(tenantId, environmentId, profile.provider(), "LIVE_ADAPTER", rawAssets);
    }

    public InventoryNormalizationResult normalizeBatch(
            UUID tenantId, UUID environmentId, String providerKey, String telemetrySource, List<RawCloudAssetRecord> assets) {
        CloudInventoryProfile profile = getProfile(providerKey);
        String sourceTag = isBlank(telemetrySource) ? "LIVE_ADAPTER" : telemetrySource.trim();
        Instant now = Instant.now();

        List<ObservationDto> observations = new ArrayList<>();
        List<DiscoveredEdgeDto> edges = new ArrayList<>();
        List<NormalizedComputePlacementDto> computePlacements = new ArrayList<>();
        List<NormalizedAliasDto> aliases = new ArrayList<>();

        for (RawCloudAssetRecord asset : assets) {
            NativeTypeRule rule = resolveRule(asset.nativeType());
            Neo4jOntology.NodeLabel canonicalLabel = Neo4jOntology.requireNodeLabel(rule.nodeLabel());
            Neo4jOntology.RelationshipType canonicalRel = Neo4jOntology.requireRelationshipType(rule.relationshipType());

            String safeResourceId = scrubber.scrubText(asset.resourceId()).sanitizedText();
            String safeDisplay = scrubber.scrubText(isBlank(asset.displayName()) ? asset.nativeType() : asset.displayName()).sanitizedText();
            String safeServiceId = isBlank(asset.serviceCanonicalId())
                    ? "urn:uacrf:svc:" + slug(safeDisplay)
                    : scrubber.scrubText(asset.serviceCanonicalId()).sanitizedText();
            String resourceCanonicalId = "urn:uacrf:cloud:" + profile.provider().toLowerCase(Locale.ROOT)
                    + ":" + slug(safeResourceId);
            String region = isBlank(asset.region()) ? "global" : asset.region();
            String az = isBlank(asset.availabilityZone()) ? region + "-default" : asset.availabilityZone();
            String failureDomain = isBlank(asset.failureDomain())
                    ? "fd-" + profile.provider().toLowerCase(Locale.ROOT) + "-" + slug(region)
                    : asset.failureDomain();

            Map<String, Object> digestInput = new TreeMap<>();
            digestInput.put("provider", profile.provider());
            digestInput.put("resourceId", safeResourceId);
            digestInput.put("nativeType", asset.nativeType());
            digestInput.put("ruleFamily", rule.ruleFamily());
            digestInput.put("region", region);
            String digest = CanonicalJson.digestObject(digestInput);

            observations.add(new ObservationDto(
                    UUID.randomUUID(), tenantId, environmentId, null,
                    safeServiceId, profile.provider(), "CLOUD_INVENTORY_" + rule.ruleFamily(),
                    "inventory." + rule.ruleFamily().toLowerCase(Locale.ROOT).replace('-', '.') + ".discovered",
                    asset.currentCapacity() != null ? asset.currentCapacity().doubleValue() : 1.0,
                    "count", null, "INFO", sourceTag, digest, safeResourceId, now, now
            ));

            edges.add(new DiscoveredEdgeDto(
                    UUID.randomUUID(), tenantId, environmentId,
                    safeServiceId, "Service", safeServiceId,
                    canonicalRel.name(), resourceCanonicalId, canonicalLabel.name(), safeDisplay,
                    rule.importance(), "CONFIRMED", profile.sourceName(), false, sourceTag, now
            ));

            String fdCanonicalId = "urn:uacrf:fd:" + slug(failureDomain);
            edges.add(new DiscoveredEdgeDto(
                    UUID.randomUUID(), tenantId, environmentId,
                    resourceCanonicalId, canonicalLabel.name(), safeDisplay,
                    Neo4jOntology.RelationshipType.MEMBER_OF_FAILURE_DOMAIN.name(),
                    fdCanonicalId, Neo4jOntology.NodeLabel.FailureDomain.name(), failureDomain,
                    "MANDATORY", "CONFIRMED", profile.sourceName(), false, sourceTag, now
            ));

            aliases.add(new NormalizedAliasDto(
                    safeServiceId, profile.provider(), safeResourceId, 1.0, profile.sourceName(), now
            ));

            if (Set.of("INF-CMP", "PLT-K8S", "APP-SLO").contains(rule.ruleFamily())) {
                String resolvedRuntime = isBlank(asset.runtimeKind()) ? rule.runtimeKind() : asset.runtimeKind();
                int minCap = asset.minCapacity() != null ? asset.minCapacity() : 1;
                int curCap = asset.currentCapacity() != null ? asset.currentCapacity() : minCap;
                int maxCap = asset.maxCapacity() != null ? asset.maxCapacity() : Math.max(curCap, minCap);
                computePlacements.add(new NormalizedComputePlacementDto(
                        safeServiceId, profile.provider(), resolvedRuntime, region, az,
                        failureDomain, safeDisplay, minCap, maxCap, curCap, maxCap > minCap
                ));
            }
        }

        return new InventoryNormalizationResult(
                profile.provider(), sourceTag, assets.size(),
                observations, edges, computePlacements, aliases
        );
    }

    /**
     * Parses raw native JSON payloads returned by AWS Resource Explorer 2, GCP Cloud Asset Inventory,
     * Azure Resource Graph, or OCI Search Service into canonical RawCloudAssetRecord entries.
     */
    public List<RawCloudAssetRecord> parseProviderResponse(String providerKey, JsonNode root) {
        if (root == null || root.isMissingNode()) {
            return List.of();
        }
        List<RawCloudAssetRecord> items = new ArrayList<>();
        switch (providerKey.toUpperCase(Locale.ROOT)) {
            case "AWS" -> {
                for (JsonNode n : root.path("Resources")) {
                    String arn = n.path("Arn").asText("");
                    String type = n.path("ResourceType").asText("aws::cloudresource");
                    String region = n.path("Region").asText("us-east-1");
                    String owningSvc = n.path("OwningAccountId").asText("aws");
                    items.add(new RawCloudAssetRecord(
                            arn, type, extractNameFromArn(arn), null, region,
                            region + "a", "fd-aws-" + region, null, null, null, null,
                            Map.of("owningAccountId", owningSvc)
                    ));
                }
            }
            case "GCP" -> {
                for (JsonNode n : root.path("results")) {
                    String name = n.path("name").asText("");
                    String type = n.path("assetType").asText("compute.googleapis.com/Resource");
                    String location = n.path("location").asText("global");
                    String display = n.path("displayName").asText(lastPathSegment(name));
                    items.add(new RawCloudAssetRecord(
                            name, type, display, null, location, location,
                            "fd-gcp-" + location, null, null, null, null, Map.of()
                    ));
                }
            }
            case "AZURE" -> {
                for (JsonNode n : root.path("data")) {
                    String id = n.path("id").asText("");
                    String type = n.path("type").asText("microsoft.resources/resource");
                    String name = n.path("name").asText(lastPathSegment(id));
                    String location = n.path("location").asText("eastus");
                    items.add(new RawCloudAssetRecord(
                            id, type, name, null, location, location + "-1",
                            "fd-azure-" + location, null, null, null, null, Map.of()
                    ));
                }
            }
            case "OCI" -> {
                for (JsonNode n : root.path("items")) {
                    String ocid = n.path("identifier").asText("");
                    String type = n.path("resourceType").asText("oci:core:instance");
                    String name = n.path("displayName").asText(ocid);
                    String ad = n.path("availabilityDomain").asText("AD-1");
                    String region = extractOciRegion(ocid);
                    items.add(new RawCloudAssetRecord(
                            ocid, type, name, null, region, ad,
                            "fd-oci-" + region, null, null, null, null, Map.of()
                    ));
                }
            }
            default -> throw new DiscoveryDomainException(HttpStatus.BAD_REQUEST, "UNKNOWN_CLOUD_PROVIDER", providerKey);
        }
        return items;
    }

    private JsonNode executeProviderQuery(CloudInventoryProfile profile, String token, String scopeFilter) {
        String filter = isBlank(scopeFilter) ? "*" : scopeFilter;
        String rawBody = switch (profile.provider()) {
            case "AWS" -> restClient.post().uri(profile.endpointUrl())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("QueryString", filter, "MaxResults", 200))
                    .retrieve().body(String.class);
            case "GCP" -> {
                String gcpUri = "*".equals(filter)
                        ? profile.endpointUrl()
                        : profile.endpointUrl() + "?query=" + URI.create("http://dummy?q=" + filter).getRawQuery().substring(2);
                yield restClient.get().uri(gcpUri)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .retrieve().body(String.class);
            }
            case "AZURE" -> restClient.post().uri(profile.endpointUrl())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("query", "Resources | limit 200"))
                    .retrieve().body(String.class);
            case "OCI" -> restClient.post().uri(profile.endpointUrl())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("type", "Structured", "query", "query all resources"))
                    .retrieve().body(String.class);
            default -> throw new DiscoveryDomainException(HttpStatus.BAD_REQUEST, "UNKNOWN_CLOUD_PROVIDER", profile.provider());
        };
        try {
            return rawBody == null || rawBody.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(rawBody);
        } catch (Exception ex) {
            throw new DiscoveryDomainException(HttpStatus.BAD_GATEWAY, "INVALID_PROVIDER_JSON",
                    "Failed to parse cloud inventory response for [" + profile.provider() + "]: " + ex.getMessage());
        }
    }

    private static NativeTypeRule resolveRule(String rawNativeType) {
        String norm = rawNativeType == null ? "" : rawNativeType.trim().toLowerCase(Locale.ROOT);
        NativeTypeRule exact = NATIVE_TYPE_RULES.get(norm);
        if (exact != null) {
            return exact;
        }
        if (norm.contains("kubernetes") || norm.contains("eks") || norm.contains("gke") || norm.contains("aks") || norm.contains("oke")) {
            return new NativeTypeRule("PLT-K8S", "Cluster", "DEPLOYED_IN", "K8S", "MANDATORY");
        }
        if (norm.contains("compute") || norm.contains("autoscal") || norm.contains("instance")) {
            return new NativeTypeRule("INF-CMP", "ComputeNode", "RUNS_ON", "COMPUTE", "MANDATORY");
        }
        if (norm.contains("sql") || norm.contains("db") || norm.contains("dynamo") || norm.contains("cosmos")) {
            return new NativeTypeRule("INF-DB-REL", "Database", "READS_FROM", "DATABASE", "MANDATORY");
        }
        if (norm.contains("storage") || norm.contains("bucket") || norm.contains("s3") || norm.contains("blob")) {
            return new NativeTypeRule("INF-STO-OBJ", "DataStore", "READS_FROM", "OBJECT_STORAGE", "MANDATORY");
        }
        if (norm.contains("queue") || norm.contains("topic") || norm.contains("stream") || norm.contains("pubsub") || norm.contains("servicebus")) {
            return new NativeTypeRule("PLT-MSG", "Queue", "PUBLISHES_TO", "QUEUE", "DEGRADABLE");
        }
        if (norm.contains("model") || norm.contains("sagemaker") || norm.contains("aiplatform") || norm.contains("bedrock")) {
            return new NativeTypeRule("AI-MODEL", "ModelEndpoint", "CALLS", "AI_ENDPOINT", "MANDATORY");
        }
        return new NativeTypeRule("PLT-OBS-INV", "CloudResource", "DEPENDS_ON", "CLOUD_RESOURCE", "OPTIONAL");
    }

    private static String extractNameFromArn(String arn) {
        int idx = Math.max(arn.lastIndexOf('/'), arn.lastIndexOf(':'));
        return idx >= 0 && idx + 1 < arn.length() ? arn.substring(idx + 1) : arn;
    }

    private static String lastPathSegment(String path) {
        int idx = path.lastIndexOf('/');
        return idx >= 0 && idx + 1 < path.length() ? path.substring(idx + 1) : path;
    }

    private static String extractOciRegion(String ocid) {
        String[] parts = ocid.split("\\.");
        return parts.length >= 4 && !parts[3].isBlank() ? parts[3] : "us-ashburn-1";
    }

    private static String slug(String value) {
        String clean = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        clean = clean.replaceAll("^-+|-+$", "");
        return clean.isBlank() ? "resource" : (clean.length() > 64 ? clean.substring(0, 64) : clean);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
