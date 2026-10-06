# One IDM environment for the idm CLI (DirXMLDev), written by Terraform: the environments
# file (vault URL, bind DN, driver set, tier, trust, SSH), the secrets file the drivers' deploy
# needs, both readable by their owner only, and optionally the deploy itself.
#
# Each instance of this module owns one environment and its own pair of files
# (environments-<name>.properties, secrets-<name>.properties), so several environments do not
# contend for one file; the deploy points IDM_ENVIRONMENTS at this instance's file. The same
# files serve `bin/idm --env <name>` and DirXMLDevWeb started with that IDM_ENVIRONMENTS.
#
# A value given as a command (bind_password_command, secret_commands) is written as the CLI's
# `…Command=` reference and read at deploy time, so a secret provisioned by Terraform into a
# manager (AWS, Azure, GCP, Vault, …) is referenced there by name and never copied into state.
# A literal (bind_password, secrets) lands in the 0600 file — and in Terraform state, which is
# why the examples read them from a manager's data source rather than variables.

terraform {
  required_version = ">= 1.5"
  required_providers {
    local = {
      source  = "hashicorp/local"
      version = ">= 2.4"
    }
  }
}

locals {
  dir               = var.directory != null ? var.directory : path.root
  environments_file = "${local.dir}/environments-${var.name}.properties"
  secrets_file      = "${local.dir}/secrets-${var.name}.properties"

  env_lines = concat(
    [
      "# written by Terraform (module idm-environment); edit the configuration, not this file",
      "${var.name}.url=${var.url}",
      "${var.name}.bindDn=${var.bind_dn}",
      "${var.name}.driverSet=${var.driver_set_dn}",
      "${var.name}.tier=${var.tier}",
      "${var.name}.secrets=secrets-${var.name}.properties",
    ],
    var.trust_all ? ["${var.name}.trustAll=true"] : [],
    var.requires != null ? ["${var.name}.requires=${var.requires}"] : [],
    var.ssh_host != null ? ["${var.name}.sshHost=${var.ssh_host}"] : [],
    var.ssh_user != null ? ["${var.name}.sshUser=${var.ssh_user}"] : [],
    var.bind_password != null ? ["${var.name}.password=${replace(var.bind_password, "\\", "\\\\")}"] : [],
    var.bind_password_command != null ? ["${var.name}.passwordCommand=${replace(var.bind_password_command, "\\", "\\\\")}"] : [],
    [for k, v in var.extra_properties : "${var.name}.${k}=${replace(v, "\\", "\\\\")}"],
  )

  # the secrets file is the CLI's raw dialect: key=value split at the first '=', both sides
  # trimmed, no escaping (driver names hold spaces, passwords hold backslashes)
  secret_lines = concat(
    ["# written by Terraform (module idm-environment) for environment ${var.name}"],
    [for k, v in var.secrets : "${k}=${v}"],
    [for k, c in var.secret_commands : "${k}Command=${c}"],
  )

  deploy_args = concat(
    [var.idm, "vault.deploy", var.tree, "--env", var.name, "--json"],
    var.deploy_mode == "dry-run" ? ["--dry-run"] : ["--yes", "--confirm", var.name],
    flatten([for d in var.drivers : ["--driver", d]]),
    var.restart ? [] : ["--no-restart"],
    ["--secrets", var.secrets_mode],
    var.allow_missing_secrets ? ["--allow-missing-secrets"] : [],
  )
  deploy_command = join(" ", [for a in local.deploy_args : format("'%s'", replace(a, "'", "'\\''"))])

  # a change anywhere in the tree (or in either file) runs the deploy again
  tree_digest = var.tree == null ? "" : sha1(join("\n", [for f in sort(fileset(var.tree, "**")) : "${f}:${filesha1("${var.tree}/${f}")}"]))
}

resource "local_sensitive_file" "environments" {
  filename        = local.environments_file
  content         = "${join("\n", local.env_lines)}\n"
  file_permission = "0600"
}

resource "local_sensitive_file" "secrets" {
  filename        = local.secrets_file
  content         = "${join("\n", local.secret_lines)}\n"
  file_permission = "0600"
}

resource "terraform_data" "deploy" {
  count = var.deploy_mode == "none" ? 0 : 1

  triggers_replace = {
    tree         = local.tree_digest
    environments = local_sensitive_file.environments.content_sha256
    secrets      = local_sensitive_file.secrets.content_sha256
    command      = local.deploy_command
  }

  provisioner "local-exec" {
    command = local.deploy_command
    environment = {
      IDM_ENVIRONMENTS = abspath(local.environments_file)
    }
  }

  depends_on = [local_sensitive_file.environments, local_sensitive_file.secrets]
}
