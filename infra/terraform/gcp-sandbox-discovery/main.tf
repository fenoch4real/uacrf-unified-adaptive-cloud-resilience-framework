terraform {
  required_version = ">= 1.9.0"
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6.10"
    }
  }
}

provider "google" {
  project = var.project_id
  region  = var.region
}

# Enable only read-only discovery & monitoring APIs needed by S03
resource "google_project_service" "discovery_apis" {
  for_each = toset([
    "cloudasset.googleapis.com",
    "monitoring.googleapis.com",
    "logging.googleapis.com",
    "cloudresourcemanager.googleapis.com"
  ])
  service            = each.value
  disable_on_destroy = false
}

# Strictly read-only discovery identity (NFR-SEC-003: separated from recovery execution identities)
resource "google_service_account" "uacrf_discovery_ro" {
  account_id   = "uacrf-discovery-ro"
  display_name = "UACRF Read-Only Cloud Discovery (${var.environment})"
  description  = "Least-privilege read-only service account for UACRF S03 Cloud Asset Inventory and Monitoring ingestion."
  depends_on   = [google_project_service.discovery_apis]
}

resource "google_project_iam_member" "discovery_roles" {
  for_each = toset([
    "roles/cloudasset.viewer",
    "roles/monitoring.viewer",
    "roles/logging.viewer",
    "roles/browser"
  ])
  project = var.project_id
  role    = each.value
  member  = "serviceAccount:${google_service_account.uacrf_discovery_ro.email}"
}

# Optional minimal sandbox resources (free-tier / low-cost) so live discovery returns real topology assets
resource "google_storage_bucket" "sandbox_evidence_sample" {
  count                       = var.create_sample_sandbox_resources ? 1 : 0
  name                        = "uacrf-sandbox-assets-${var.project_id}"
  location                    = "US"
  uniform_bucket_level_access = true
  force_destroy               = true
}

resource "google_pubsub_topic" "sandbox_orders_events" {
  count      = var.create_sample_sandbox_resources ? 1 : 0
  name       = "uacrf-sandbox-order-events"
  depends_on = [google_project_service.discovery_apis]
}
