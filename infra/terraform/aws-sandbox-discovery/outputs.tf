output "discovery_role_arn" {
  description = "Read-only AWS IAM role ARN for S03 discovery-service."
  value       = aws_iam_role.uacrf_discovery_ro.arn
}

output "discovery_env" {
  value = <<-EOT
    export UACRF_DISCOVERY_AWS_ENABLED=true
    export UACRF_DISCOVERY_AWS_REGION=${var.region}
    export UACRF_DISCOVERY_AWS_ROLE_ARN=${aws_iam_role.uacrf_discovery_ro.arn}
    export UACRF_DISCOVERY_AWS_EXTERNAL_ID=${var.external_id}
  EOT
}
