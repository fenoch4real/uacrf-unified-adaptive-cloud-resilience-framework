variable "region" {
  type        = string
  description = "Primary AWS region for sandbox discovery."
  default     = "us-east-1"
}

variable "environment" {
  type        = string
  description = "Environment label."
  default     = "sandbox"
}

variable "external_id" {
  type        = string
  description = "Optional STS ExternalId required when UACRF assumes uacrf-discovery-ro."
  default     = "uacrf-sandbox"
}
