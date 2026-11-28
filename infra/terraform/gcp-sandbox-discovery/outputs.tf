output "project_id" {
  value = var.project_id
}

output "read_only_service_account_email" {
  value       = google_service_account.uacrf_discovery_ro.email
  description = "Read-only service account for S03 Cloud Asset Inventory polling (zero mutation permissions)."
}

output "uacrf_env_snippet" {
  value = <<-EOT
    export UACRF_DISCOVERY_GCP_ENABLED=true
    export UACRF_DISCOVERY_GCP_PROJECT=${var.project_id}
  EOT
}
