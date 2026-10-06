variable "name" {
  description = "The environment's name, as `bin/idm --env` names it (letters, digits, '.', '_', '-')."
  type        = string
  validation {
    condition     = can(regex("^[A-Za-z0-9][A-Za-z0-9_.-]*$", var.name))
    error_message = "An environment name is letters, digits, '.', '_' and '-'."
  }
}

variable "url" {
  description = "The vault's LDAPS URL, e.g. ldaps://vault.example.com:636."
  type        = string
}

variable "bind_dn" {
  description = "The account the deploy binds as."
  type        = string
}

variable "driver_set_dn" {
  description = "The driver set's DN, e.g. cn=driverset1,o=system."
  type        = string
}

variable "tier" {
  description = "dev, stg or prd (prd makes the CLI ask more of a deploy)."
  type        = string
  default     = "dev"
  validation {
    condition     = contains(["dev", "stg", "prd"], var.tier)
    error_message = "tier is dev, stg or prd."
  }
}

variable "trust_all" {
  description = "Accept any certificate (a lab, a tunnel). Never for production."
  type        = bool
  default     = false
}

variable "requires" {
  description = "An environment whose green deploy of the same commit must come first (the CLI's `requires`)."
  type        = string
  default     = null
}

variable "ssh_host" {
  description = "The engine host, for `driver.trace tail` over SSH."
  type        = string
  default     = null
}

variable "ssh_user" {
  type    = string
  default = null
}

variable "bind_password" {
  description = "The bind account's password, as a literal in the 0600 file. It also lands in Terraform state: prefer bind_password_command."
  type        = string
  default     = null
  sensitive   = true
}

variable "bind_password_command" {
  description = "A command whose stdout is the bind password, run by the CLI when it connects (e.g. `aws secretsmanager get-secret-value … --output text`). Nothing is copied."
  type        = string
  default     = null
}

variable "extra_properties" {
  description = "Other `<name>.<key>=value` lines for the environments file (formsUrl, appsUser, k8sHost, …)."
  type        = map(string)
  default     = {}
}

variable "secrets" {
  description = "Driver secrets as literals: `<driver>.shim-auth-password`, `<driver>.remote-loader-password`, `<driver>.named.<name>`, `driverset.named.<name>`. They land in the 0600 file and in state; prefer secret_commands."
  type        = map(string)
  default     = {}
  sensitive   = true
}

variable "secret_commands" {
  description = "Driver secrets as commands the CLI runs at deploy time (same keys as `secrets`), e.g. a secret manager's CLI by name."
  type        = map(string)
  default     = {}
}

variable "directory" {
  description = "Where the two files go (default: the root module's directory)."
  type        = string
  default     = null
}

variable "deploy_mode" {
  description = "none: write the files only. dry-run: run `vault.deploy --dry-run`. deploy: run it for real (`--yes --confirm <name>`)."
  type        = string
  default     = "none"
  validation {
    condition     = contains(["none", "dry-run", "deploy"], var.deploy_mode)
    error_message = "deploy_mode is none, dry-run or deploy."
  }
}

variable "tree" {
  description = "The IDM-as-code tree to deploy (needed unless deploy_mode is none). Any change under it triggers the deploy again."
  type        = string
  default     = null
}

variable "idm" {
  description = "The idm launcher (DirXMLDev's bin/idm)."
  type        = string
  default     = "idm"
}

variable "drivers" {
  description = "Only these drivers (default: the whole driver set)."
  type        = list(string)
  default     = []
}

variable "restart" {
  description = "Restart the drivers a change needs restarted."
  type        = bool
  default     = true
}

variable "secrets_mode" {
  description = "Which driver secrets the deploy sets: none, missing or all."
  type        = string
  default     = "missing"
  validation {
    condition     = contains(["none", "missing", "all"], var.secrets_mode)
    error_message = "secrets_mode is none, missing or all."
  }
}

variable "allow_missing_secrets" {
  type    = bool
  default = false
}
