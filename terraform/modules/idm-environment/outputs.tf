output "environments_file" {
  description = "The environments file this instance wrote; point IDM_ENVIRONMENTS (or DirXMLDevWeb) at it."
  value       = abspath(local.environments_file)
}

output "secrets_file" {
  value = abspath(local.secrets_file)
}

output "deploy_command" {
  description = "The deploy as the module runs it (also what to run by hand, with IDM_ENVIRONMENTS set to environments_file)."
  value       = local.deploy_command
}

output "name" {
  value = var.name
}
