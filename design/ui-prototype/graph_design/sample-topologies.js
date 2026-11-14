window.UACRF_GRAPH_SAMPLE = {
  fixtureVersion: 1,
  notice: "Fictional, illustrative sample only. No provider account, credentials, evidence, or accepted graph revision is represented.",
  apiResponse: {
    graph_revision_id: "sample-northstar-aws-r42",
    digest: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    source_watermark: 42,
    state: "AVAILABLE",
    runtime_verification: "NOT_ESTABLISHED",
    completeness: "ACCEPTED_REVISION_ONLY",
    nodes: [
      { canonical_id: "checkout", label: "Service", properties: { scope_service_id: "checkout", workload_kind: "SERVICE", workload_id: "checkout", qualification_id: "sample-qualification-checkout", qualification_state: "QUALIFIED", expires_at: 1800000000, metadata_json: "{\"name\":\"Checkout API\",\"provider\":\"AWS\",\"region\":\"us-east-1\"}" } },
      { canonical_id: "order-support", label: "Service", properties: { scope_service_id: "checkout", workload_kind: "AGENT", workload_id: "order-support", qualification_id: "sample-qualification-agent", qualification_state: "QUALIFIED", expires_at: 1800000000, metadata_json: "{\"name\":\"Order Support Agent\",\"provider\":\"AWS\",\"region\":\"us-east-1\"}" } },
      { canonical_id: "orders-api", label: "Service", properties: { scope_service_id: "checkout", workload_kind: "SERVICE", workload_id: "orders-api", qualification_id: "sample-qualification-orders", qualification_state: "QUALIFIED", expires_at: 1800000000, metadata_json: "{\"name\":\"Orders API\",\"provider\":\"AWS\",\"region\":\"us-east-1\"}" } },
      { canonical_id: "payments-api", label: "Service", properties: { scope_service_id: "checkout", workload_kind: "SERVICE", workload_id: "payments-api", qualification_id: "sample-qualification-payments", qualification_state: "QUALIFIED", expires_at: 1800000000, metadata_json: "{\"name\":\"Payments API\",\"provider\":\"AWS\",\"region\":\"us-east-1\"}" } },
      { canonical_id: "aurora-orders", label: "Resource", properties: { scope_service_id: "checkout", workload_kind: "RESOURCE", qualification_id: "sample-qualification-aurora", qualification_state: "QUALIFIED", expires_at: 1800000000, metadata_json: "{\"name\":\"Aurora PostgreSQL\",\"provider\":\"AWS\",\"region\":\"us-east-1\"}" } },
      { canonical_id: "eks-prod", label: "Resource", properties: { scope_service_id: "checkout", workload_kind: "RESOURCE", qualification_id: "sample-qualification-eks", qualification_state: "QUALIFIED", expires_at: 1800000000, metadata_json: "{\"name\":\"EKS production\",\"provider\":\"AWS\",\"region\":\"us-east-1\"}" } },
      { canonical_id: "elasticache-orders", label: "Resource", properties: { scope_service_id: "checkout", workload_kind: "RESOURCE", qualification_id: "sample-qualification-cache", qualification_state: "QUALIFIED", expires_at: 1800000000, metadata_json: "{\"name\":\"ElastiCache session\",\"provider\":\"AWS\",\"region\":\"us-east-1\"}" } },
      { canonical_id: "kms-orders", label: "Resource", properties: { scope_service_id: "checkout", workload_kind: "RESOURCE", qualification_id: "sample-qualification-kms", qualification_state: "QUALIFIED", expires_at: 1800000000, metadata_json: "{\"name\":\"KMS encryption key\",\"provider\":\"AWS\",\"region\":\"us-east-1\"}" } },
      { canonical_id: "bedrock-model", label: "Resource", properties: { scope_service_id: "checkout", workload_kind: "RESOURCE", qualification_id: "sample-qualification-model", qualification_state: "QUALIFIED", expires_at: 1800000000, metadata_json: "{\"name\":\"Bedrock support model\",\"provider\":\"AWS\",\"region\":\"us-east-1\"}" } },
      { canonical_id: "s3-knowledge", label: "Resource", properties: { scope_service_id: "checkout", workload_kind: "RESOURCE", qualification_id: "sample-qualification-knowledge", qualification_state: "QUALIFIED", expires_at: 1800000000, metadata_json: "{\"name\":\"S3 knowledge store\",\"provider\":\"AWS\",\"region\":\"us-east-1\"}" } },
      { canonical_id: "agent-role", label: "Resource", properties: { scope_service_id: "checkout", workload_kind: "RESOURCE", qualification_id: "sample-qualification-role", qualification_state: "QUALIFIED", expires_at: 1800000000, metadata_json: "{\"name\":\"Support IAM role\",\"provider\":\"AWS\",\"region\":\"us-east-1\"}" } }
    ],
    edges: [
      { relationship_id: "checkout-orders", source_id: "checkout", target_id: "orders-api", type: "CALLS", properties: { qualification_id: "sample-edge-checkout-orders", qualification_state: "QUALIFIED", confidence: 0.99, valid_from: 1794000000, expires_at: 1800000000, metadata_json: "{}" } },
      { relationship_id: "checkout-payments", source_id: "checkout", target_id: "payments-api", type: "CALLS", properties: { qualification_id: "sample-edge-checkout-payments", qualification_state: "QUALIFIED", confidence: 0.99, valid_from: 1794000000, expires_at: 1800000000, metadata_json: "{}" } },
      { relationship_id: "checkout-cache", source_id: "checkout", target_id: "elasticache-orders", type: "DEPENDS_ON", properties: { qualification_id: "sample-edge-checkout-cache", qualification_state: "QUALIFIED", confidence: 0.96, valid_from: 1794000000, expires_at: 1800000000, metadata_json: "{}" } },
      { relationship_id: "checkout-runtime", source_id: "checkout", target_id: "eks-prod", type: "RUNS_ON", properties: { qualification_id: "sample-edge-checkout-runtime", qualification_state: "QUALIFIED", confidence: 1.0, valid_from: 1794000000, expires_at: 1800000000, metadata_json: "{}" } },
      { relationship_id: "payments-database", source_id: "payments-api", target_id: "aurora-orders", type: "READS_FROM", properties: { qualification_id: "sample-edge-payments-database", qualification_state: "QUALIFIED", confidence: 0.99, valid_from: 1794000000, expires_at: 1800000000, metadata_json: "{}" } },
      { relationship_id: "payments-key", source_id: "payments-api", target_id: "kms-orders", type: "USES_KEY", properties: { qualification_id: "sample-edge-payments-key", qualification_state: "QUALIFIED", confidence: 0.98, valid_from: 1794000000, expires_at: 1800000000, metadata_json: "{}" } },
      { relationship_id: "orders-database", source_id: "orders-api", target_id: "aurora-orders", type: "READS_FROM", properties: { qualification_id: "sample-edge-orders-database", qualification_state: "QUALIFIED", confidence: 0.99, valid_from: 1794000000, expires_at: 1800000000, metadata_json: "{}" } },
      { relationship_id: "orders-runtime", source_id: "orders-api", target_id: "eks-prod", type: "RUNS_ON", properties: { qualification_id: "sample-edge-orders-runtime", qualification_state: "QUALIFIED", confidence: 1.0, valid_from: 1794000000, expires_at: 1800000000, metadata_json: "{}" } },
      { relationship_id: "agent-orders", source_id: "order-support", target_id: "orders-api", type: "CALLS", properties: { qualification_id: "sample-edge-agent-orders", qualification_state: "QUALIFIED", confidence: 0.97, valid_from: 1794000000, expires_at: 1800000000, metadata_json: "{}" } },
      { relationship_id: "agent-model", source_id: "order-support", target_id: "bedrock-model", type: "USES_MODEL", properties: { qualification_id: "sample-edge-agent-model", qualification_state: "QUALIFIED", confidence: 0.95, valid_from: 1794000000, expires_at: 1800000000, metadata_json: "{}" } },
      { relationship_id: "agent-knowledge", source_id: "order-support", target_id: "s3-knowledge", type: "READS_FROM", properties: { qualification_id: "sample-edge-agent-knowledge", qualification_state: "QUALIFIED", confidence: 0.96, valid_from: 1794000000, expires_at: 1800000000, metadata_json: "{}" } },
      { relationship_id: "agent-identity", source_id: "order-support", target_id: "agent-role", type: "USES_IDENTITY", properties: { qualification_id: "sample-edge-agent-identity", qualification_state: "QUALIFIED", confidence: 0.98, valid_from: 1794000000, expires_at: 1800000000, metadata_json: "{}" } },
      { relationship_id: "agent-runtime", source_id: "order-support", target_id: "eks-prod", type: "RUNS_ON", properties: { qualification_id: "sample-edge-agent-runtime", qualification_state: "QUALIFIED", confidence: 1.0, valid_from: 1794000000, expires_at: 1800000000, metadata_json: "{}" } }
    ]
  },
  views: {
    service: {
      label: "Service view",
      root_ids: ["checkout"],
      node_ids: ["checkout", "orders-api", "payments-api", "aurora-orders", "eks-prod", "elasticache-orders", "kms-orders"],
      positions: { checkout: [60, 170], "orders-api": [360, 80], "payments-api": [360, 265], "aurora-orders": [690, 90], "elasticache-orders": [690, 240], "eks-prod": [360, 450], "kms-orders": [690, 420] }
    },
    agent: {
      label: "Agent view",
      root_ids: ["order-support"],
      node_ids: ["order-support", "orders-api", "aurora-orders", "eks-prod", "bedrock-model", "s3-knowledge", "agent-role"],
      positions: { "order-support": [60, 220], "orders-api": [360, 70], "aurora-orders": [690, 70], "bedrock-model": [690, 210], "s3-knowledge": [690, 350], "agent-role": [360, 430], "eks-prod": [690, 490] }
    },
    both: {
      label: "Services + agents",
      root_ids: ["checkout", "order-support"],
      node_ids: ["checkout", "order-support", "orders-api", "payments-api", "aurora-orders", "eks-prod", "elasticache-orders", "kms-orders", "bedrock-model", "s3-knowledge", "agent-role"],
      positions: { checkout: [35, 115], "order-support": [35, 410], "orders-api": [330, 70], "payments-api": [330, 240], "aurora-orders": [620, 150], "elasticache-orders": [620, 310], "kms-orders": [620, 465], "eks-prod": [330, 515], "bedrock-model": [850, 100], "s3-knowledge": [850, 260], "agent-role": [850, 420] }
    }
  }
};
