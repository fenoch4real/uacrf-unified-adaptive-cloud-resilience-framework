variable "environment" {
  type    = string
  default = "prod"
}

variable "enable_aws" {
  type    = bool
  default = true
}

variable "enable_gcp" {
  type    = bool
  default = true
}

variable "enable_azure" {
  type    = bool
  default = true
}

variable "enable_oci" {
  type    = bool
  default = true
}

variable "worm_retention_days" {
  type    = number
  default = 365
}
