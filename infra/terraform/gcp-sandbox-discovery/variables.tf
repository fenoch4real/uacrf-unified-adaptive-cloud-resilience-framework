variable "project_id" {
  type        = string
  description = "Target GCP sandbox project ID that UACRF discovery-service will read."
}

variable "region" {
  type        = string
  default     = "us-central1"
  description = "Primary GCP region for sandbox assets."
}

variable "environment" {
  type    = string
  default = "sandbox"
}

variable "create_sample_sandbox_resources" {
  type        = bool
  default     = true
  description = "Create a tiny GCS bucket and Pub/Sub topic so initial Asset Inventory scans return real resources."
}
