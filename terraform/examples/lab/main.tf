# A lab environment: the password as a literal (fine for a lab, it lands in state), a dry run
# of the tree against it on every apply. Run with
#   terraform init && terraform apply -var tree=/path/to/tree -var idm=/path/to/DirXMLDev/bin/idm -var bind_password=…
module "lab" {
  source = "../../modules/idm-environment"

  name          = var.name
  tier          = "dev"
  url           = var.url
  bind_dn       = var.bind_dn
  driver_set_dn = var.driver_set_dn
  trust_all     = true
  bind_password = var.bind_password

  secrets = var.secrets

  deploy_mode = var.deploy_mode
  tree        = var.tree
  idm         = var.idm
}

variable "name" {
  type    = string
  default = "lab"
}
variable "url" {
  type    = string
  default = "ldaps://127.0.0.1:6638"
}
variable "bind_dn" {
  type    = string
  default = "cn=admin,ou=sa,o=system"
}
variable "driver_set_dn" {
  type    = string
  default = "cn=driverset1,o=system"
}
variable "bind_password" {
  type      = string
  sensitive = true
}
variable "secrets" {
  type      = map(string)
  default   = {}
  sensitive = true
}
variable "deploy_mode" {
  type    = string
  default = "dry-run"
}
variable "tree" {
  type    = string
  default = null
}
variable "idm" {
  type    = string
  default = "idm"
}

output "environments_file" {
  value = module.lab.environments_file
}
output "deploy_command" {
  value = module.lab.deploy_command
}
