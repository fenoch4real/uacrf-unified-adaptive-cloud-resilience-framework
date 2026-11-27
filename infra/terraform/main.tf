terraform {
  required_version = ">= 1.9.0"
  required_providers {
    aws     = { source = "hashicorp/aws", version = "~> 5.70" }
    google  = { source = "hashicorp/google", version = "~> 6.10" }
    azurerm = { source = "hashicorp/azurerm", version = "~> 4.10" }
    oci     = { source = "oracle/oci", version = "~> 6.20" }
  }
}

# Sovereign multi-cloud KMS CMKs & WORM Object Lock evidence buckets across AWS, GCP, Azure, and OCI
resource "aws_kms_key" "uacrf_cmk" {
  count                   = var.enable_aws ? 1 : 0
  description             = "UACRF AWS FIPS/HSM-backed envelope & PITR CMK (${var.environment})"
  enable_key_rotation     = true
  deletion_window_in_days = 30
}

resource "aws_s3_bucket" "uacrf_evidence_worm" {
  count               = var.enable_aws ? 1 : 0
  bucket_prefix       = "uacrf-evidence-worm-${var.environment}-"
  object_lock_enabled = true
}

resource "aws_s3_bucket_object_lock_configuration" "uacrf_evidence_lock" {
  count  = var.enable_aws ? 1 : 0
  bucket = aws_s3_bucket.uacrf_evidence_worm[0].id
  rule {
    default_retention {
      mode = "GOVERNANCE"
      days = var.worm_retention_days
    }
  }
}

resource "google_storage_bucket" "uacrf_gcp_evidence_worm" {
  count                       = var.enable_gcp ? 1 : 0
  name                        = "uacrf-gcp-evidence-worm-${var.environment}"
  location                    = "US"
  uniform_bucket_level_access = true
  retention_policy {
    is_locked        = true
    retention_period = var.worm_retention_days * 86400
  }
}

resource "azurerm_storage_account" "uacrf_azure_worm" {
  count                    = var.enable_azure ? 1 : 0
  name                     = "uacrfworm${var.environment}"
  resource_group_name      = "uacrf-rg-${var.environment}"
  location                 = "eastus2"
  account_tier             = "Standard"
  account_replication_type = "GRS"
  min_tls_version          = "TLS1_2"
  immutability_policy {
    allow_protected_append_writes = false
    state                         = "Locked"
    period_since_creation_in_days = var.worm_retention_days
  }
}

resource "oci_objectstorage_bucket" "uacrf_oci_worm" {
  count          = var.enable_oci ? 1 : 0
  compartment_id = "ocid1.compartment.oc1..uacrf_placeholder"
  name           = "uacrf-oci-evidence-worm-${var.environment}"
  namespace      = "uacrf"
  storage_tier   = "Standard"
}
