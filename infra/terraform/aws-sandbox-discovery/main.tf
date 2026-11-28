terraform {
  required_version = ">= 1.9.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.70"
    }
  }
}

provider "aws" {
  region = var.region
}

data "aws_caller_identity" "current" {}

# Strictly read-only discovery role (NFR-SEC-003: separated from S11 execution identities)
resource "aws_iam_role" "uacrf_discovery_ro" {
  name        = "uacrf-discovery-ro-${var.environment}"
  description = "Least-privilege read-only IAM role for UACRF S03 AWS Resource Explorer and CloudWatch polling."

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      merge(
        {
          Effect    = "Allow"
          Principal = { AWS = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:root" }
          Action    = "sts:AssumeRole"
        },
        var.external_id != "" ? {
          Condition = {
            StringEquals = { "sts:ExternalId" = var.external_id }
          }
        } : {}
      )
    ]
  })
}

resource "aws_iam_role_policy_attachment" "discovery_managed_readonly" {
  for_each = toset([
    "arn:aws:iam::aws:policy/AWSResourceExplorerReadOnlyAccess",
    "arn:aws:iam::aws:policy/CloudWatchReadOnlyAccess",
    "arn:aws:iam::aws:policy/CloudWatchLogsReadOnlyAccess"
  ])
  role       = aws_iam_role.uacrf_discovery_ro.name
  policy_arn = each.value
}
