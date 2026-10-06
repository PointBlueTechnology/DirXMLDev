# Module `idm-environment`

One environment for the `idm` CLI, written by Terraform: `environments-<name>.properties`
and `secrets-<name>.properties` (both `0600`), and optionally the deploy of a tree to it.

```hcl
module "stg" {
  source = "github.com/PointBlueTechnology/DirXMLDev//terraform/modules/idm-environment?ref=v0.9.0"

  name          = "stg"
  tier          = "stg"
  url           = "ldaps://vault-stg.example.com:636"
  bind_dn       = "cn=idm-deploy,ou=sa,o=system"
  driver_set_dn = "cn=driverset1,o=system"

  # the bind password and every driver secret stay in the manager Terraform provisioned them into
  bind_password_command = "aws secretsmanager get-secret-value --secret-id idm/stg/bind --query SecretString --output text"
  secret_commands = {
    "AD Driver.shim-auth-password"     = "aws secretsmanager get-secret-value --secret-id idm/stg/ad-shim --query SecretString --output text"
    "AD Driver.remote-loader-password" = "aws secretsmanager get-secret-value --secret-id idm/stg/ad-rl --query SecretString --output text"
  }

  deploy_mode = "deploy"            # none | dry-run | deploy
  tree        = "${path.root}/../tree"
  idm         = "/opt/DirXMLDev/bin/idm"
}
```

`terraform apply` writes the files, then runs
`idm vault.deploy <tree> --env stg --json --yes --confirm stg --secrets missing` with
`IDM_ENVIRONMENTS` pointing at the file it wrote. The deploy runs again whenever anything under
the tree, or either file, changes. The CLI's own gates apply unchanged: plan, snapshot, verify,
the audit line, never deleting a driver without `--delete-driver` (not exposed here: do that
by hand).

Literals (`bind_password`, `secrets`) are supported for labs; they land in Terraform state as
well as the file. Keys of `secrets` / `secret_commands` are the CLI's: `<driver>.shim-auth-password`,
`<driver>.remote-loader-password`, `<driver>.shim-auth-id`, `<driver>.named.<name>`,
`driverset.named.<name>`.

See `terraform/examples/` for a lab (literal password, dry run) and an AWS Secrets Manager
layout, and `docs/terraform.md`.
