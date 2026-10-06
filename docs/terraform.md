# Terraform

Two things Terraform can do for an IDM-as-code tree today, with nothing new to install:

## 1. Hold the secrets, and let the CLI read them by name

Every credential the CLI reads — an environment's bind password, a driver's shim or Remote
Loader password, a named password — can be a command instead of a value
(`<key>Command=…` in `environments.properties` or `secrets-<env>.properties`; see
[vault-deploy.md](vault-deploy.md), "Secrets"). So the pattern with Terraform is:

1. Terraform provisions the secret into a manager — `aws_secretsmanager_secret_version`,
   `azurerm_key_vault_secret`, `google_secret_manager_secret_version`, a Vault KV entry, …
2. The secrets file references it by name:
   ```properties
   AD Driver.shim-auth-passwordCommand=aws secretsmanager get-secret-value --secret-id idm/stg/ad-shim --query SecretString --output text
   AD Driver.remote-loader-passwordCommand=az keyvault secret show --vault-name idm-stg --name ad-rl --query value -o tsv
   ```
3. `idm vault.deploy --env stg --secrets missing` reads the value when it sets it, and a
   rotation in Terraform is picked up on the next deploy. Nothing is copied into the tree,
   the files or Terraform state.

Commands for CyberArk's Central Credential Provider and BeyondTrust Password Safe, whose APIs
take several steps, are in [`bin/secrets/`](../bin/secrets): `cyberark-ccp.sh HOST APPID SAFE
OBJECT` and `beyondtrust.sh HOST SYSTEM ACCOUNT` (their own keys come from the environment:
`CCP_CERT`, `BT_API_KEY`, `BT_RUNAS`). Bitwarden on a workstation is `bw get password "item"`
with `BW_SESSION` exported. DirXMLDevWeb's Connections view writes these lines from a preset
and tests them; the CLI needs only the file.

## 2. Write the environment and run the deploy: module `idm-environment`

[`terraform/modules/idm-environment`](../terraform/modules/idm-environment) owns one environment:
it writes `environments-<name>.properties` and `secrets-<name>.properties` (both `0600`) from
its inputs, and with `deploy_mode = "deploy"` runs
`idm vault.deploy <tree> --env <name> --yes --confirm <name> --secrets missing` with
`IDM_ENVIRONMENTS` pointing at its file. The deploy runs again when anything under the tree or
in either file changes; otherwise `terraform apply` is a no-op. `deploy_mode = "dry-run"` plans
without writing, `"none"` only writes the files.

```hcl
module "stg" {
  source = "github.com/PointBlueTechnology/DirXMLDev//terraform/modules/idm-environment?ref=v0.9.0"

  name          = "stg"
  tier          = "stg"
  url           = "ldaps://vault-stg.example.com:636"
  bind_dn       = "cn=idm-deploy,ou=sa,o=system"
  driver_set_dn = "cn=driverset1,o=system"

  bind_password_command = "aws secretsmanager get-secret-value --secret-id idm/stg/bind --query SecretString --output text"
  secret_commands = {
    "AD Driver.shim-auth-password" = "aws secretsmanager get-secret-value --secret-id idm/stg/ad-shim --query SecretString --output text"
  }

  deploy_mode = "deploy"
  tree        = "${path.root}/../tree"
  idm         = "/opt/DirXMLDev/bin/idm"
}
```

Each module instance has its own pair of files, so stages do not contend for one
`environments.properties`; `bin/idm --env stg` and DirXMLDevWeb use the same file through
`IDM_ENVIRONMENTS`. Literal values (`bind_password`, `secrets`) are accepted for labs and land
in Terraform state as well as the file; the examples use a manager instead.

The CLI's gates are unchanged inside the module: plan, snapshot, write, restart, verify, the
audit line; a driver is never deleted (`--delete-driver` is not exposed), and `tier = "prd"`
still makes the CLI as careful as from a shell. Examples: [`terraform/examples/lab`](../terraform/examples/lab)
(a lab with a literal password and a dry run) and [`terraform/examples/aws`](../terraform/examples/aws)
(AWS Secrets Manager).

Proven 2026-10-05 on the edir3 lab: `apply` wrote the files, dry-ran, then deployed an
unchanged tree (nothing to deploy, verified), and a second `apply` changed nothing.

## Not built: a native provider

`idm_environment`, `idm_driver`, `idm_deploy` resources with plan/apply over driver sets would be
a Go provider on the core's JSON diff/plan/deploy. Worth it only when a customer wants IDM
configuration itself under Terraform's state; the module above covers standing up stages and
deploying from a pipeline without it.
