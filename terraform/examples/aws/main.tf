# A staging environment whose bind password and driver secrets live in AWS Secrets Manager.
# Terraform owns the secrets there (or reads ones created elsewhere); the idm CLI reads them
# by name at deploy time through the aws CLI, so no value is copied into the files or state.
terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.0"
    }
  }
}

provider "aws" {
  region = var.region
}

# the secrets Terraform provisions (values come from wherever your pipeline keeps them;
# a random_password for a rotated service account is the usual pattern)
resource "aws_secretsmanager_secret" "ad_shim" {
  name = "idm/${var.name}/ad-shim"
}

resource "aws_secretsmanager_secret_version" "ad_shim" {
  secret_id     = aws_secretsmanager_secret.ad_shim.id
  secret_string = var.ad_shim_password
}

locals {
  read = "aws secretsmanager get-secret-value --region ${var.region} --query SecretString --output text --secret-id"
}

module "stg" {
  source = "../../modules/idm-environment"

  name          = var.name
  tier          = "stg"
  url           = var.url
  bind_dn       = var.bind_dn
  driver_set_dn = var.driver_set_dn

  bind_password_command = "${local.read} idm/${var.name}/bind"
  secret_commands = {
    "AD Driver.shim-auth-password" = "${local.read} ${aws_secretsmanager_secret.ad_shim.name}"
  }

  deploy_mode = "deploy"
  tree        = var.tree
  idm         = var.idm
}

variable "region" {
  type    = string
  default = "us-east-1"
}
variable "name" {
  type    = string
  default = "stg"
}
variable "url" {
  type = string
}
variable "bind_dn" {
  type = string
}
variable "driver_set_dn" {
  type = string
}
variable "ad_shim_password" {
  type      = string
  sensitive = true
}
variable "tree" {
  type = string
}
variable "idm" {
  type = string
}
