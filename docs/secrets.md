# Credentials and secret managers

Every credential the `idm` CLI reads — an environment's bind password, the applications
password, a driver's shim or Remote Loader password, a named password, a shim auth id that is
an API key — comes from one of four forms of the same key, in a properties file the CLI reads
(`environments.properties` for the environment, `secrets-<env>.properties` for the drivers;
both kept at mode 600):

| form | line | reads |
|---|---|---|
| literal | `k=…` | the value as written (a lab; the file is 0600) |
| environment variable | `kEnv=VAR` | `$VAR` of the process that runs `idm` |
| command | `kCommand=cmd` | the stdout of `cmd` run with `/bin/sh -c` (`cmd.exe /c` on Windows), trailing newlines stripped |
| Keychain | `kKeychain=service[/account]` | macOS: `security find-generic-password -s service [-a account] -w` |

The literal is tried first, then the others. Values come back as `char[]`, are zeroed after
use, and nothing in the CLI prints one, writes one into a tree, a snapshot, an audit line or a
log. [vault-deploy.md](vault-deploy.md#secrets--what-the-tree-can-never-carry) says which
driver secrets a deploy needs and how it sets them; this page is about **where the values
come from**, and the `Command` form is the door to every secret manager.

## Secret managers

A `…Command=` line runs on the host that runs `idm` (your workstation; the server, in
DirXMLDevWeb's hosted mode) with that shell's environment. Export what the manager's CLI needs
there, and keep the manager's own key out of the files too (the Keychain is the usual place:
`export BT_API_KEY=$(security find-generic-password -s beyondtrust -w)`).

| Manager | Line in the secrets file | Needs |
|---|---|---|
| **AWS Secrets Manager** | `AD Driver.shim-auth-passwordCommand=aws secretsmanager get-secret-value --secret-id idm/stg/ad-shim --query SecretString --output text` (append `\| jq -r .password` for a JSON secret) | `aws` signed in (`--profile`, `--region` as needed) |
| **Azure Key Vault** | `AD Driver.remote-loader-passwordCommand=az keyvault secret show --vault-name idm-stg --name ad-rl --query value -o tsv` | `az login` |
| **Google Cloud Secret Manager** | `AD Driver.named.svcCommand=gcloud secrets versions access latest --secret=idm-stg-svc --project=my-project` | `gcloud auth login` |
| **CyberArk Central Credential Provider** | `AD Driver.shim-auth-passwordCommand=/path/to/DirXMLDev/bin/secrets/cyberark-ccp.sh ccp.example.com DirXMLDev IDM-STG ad-shim` | `curl`, `python3`; a client certificate in `CCP_CERT` (and `CCP_KEY`), a private CA in `CCP_CA`; the CCP application allows this host or certificate |
| **BeyondTrust Password Safe** | `AD Driver.shim-auth-passwordCommand=/path/to/DirXMLDev/bin/secrets/beyondtrust.sh pbps.example.com ad.example.com svc-idm-shim` | `python3`; the API registration's key and run-as user in `BT_API_KEY` and `BT_RUNAS` (a private CA in `BT_CA`) |
| **Bitwarden** (a workstation) | `stg.passwordCommand=bw get password "IDM stg bind"` (a custom field: `bw get item "…" \| jq -r '.fields[] \| select(.name == "api key") \| .value'`) | `bw`, unlocked: `export BW_SESSION=$(bw unlock --raw)` |
| **Bitwarden Secrets Manager** | `AD Driver.named.svcCommand=bws secret get be8e0ad8-… \| jq -r .value` | `bws`, `BWS_ACCESS_TOKEN` |
| **HashiCorp Vault** | `AD Driver.shim-auth-passwordCommand=vault kv get -field=password secret/idm/stg/ad-shim` | `vault login` |
| **1Password** | `AD Driver.shim-auth-passwordCommand=op read "op://IDM/stg ad shim/password"` | `op signin` |
| **Terraform output** | `stg.passwordCommand=terraform -chdir=/infra/idm output -raw stg_bind_password` | `terraform`; the value sits in state — prefer a manager Terraform provisions the secret into ([terraform.md](terraform.md)) |
| anything else | `kCommand=<whatever prints the value>` | — |

The helper scripts in [`bin/secrets/`](../bin/secrets) exist for the two managers whose API
takes several calls. `cyberark-ccp.sh HOST APPID SAFE OBJECT` calls the CCP web service and
prints `Content`; `beyondtrust.sh HOST SYSTEM ACCOUNT` signs in with the API registration,
requests the credential, reads it, checks the request back in and signs out. Both print the
value and nothing else, read their own keys from the environment, and need only `curl` and
`python3`.

**Checking a line without seeing the value**: `idm doctor --env stg` opens the connection
with the environment's password; for a driver secret, a dry-run deploy reports
`secret '…' not provided` when a command printed nothing or failed. DirXMLDevWeb's Connections
view builds these lines from a preset and has a "does it yield a value?" test for each.

**Rotation**: the value is read when it is needed, so a secret rotated in the manager is used
by the next `vault.deploy --secrets all` (which re-sets every provided secret) with no file to
edit. Terraform's place in that is in [terraform.md](terraform.md).

## The environment's own password

The same four forms apply to `<env>.password`, `<env>.appsPassword` and `<env>.appsSecret` in
`environments.properties`; on a workstation the Keychain form is the usual one
(`stg.passwordKeychain=idm-stg/cn=admin,ou=sa,o=system`, added with
`security add-generic-password -s idm-stg -a 'cn=admin,ou=sa,o=system' -w`), and a server
uses a command or an environment variable.

## Keys, once more

```properties
<driver>.shim-auth-password        DirXML-ShimAuthPassword
<driver>.remote-loader-password    the Remote Loader password
<driver>.mutual-auth-key-password       the Remote Loader's mutual-authentication key password (optional)
<driver>.mutual-auth-keystore-password  the Remote Loader's mutual-authentication keystore password (optional)
<driver>.shim-auth-id              a shim auth id that is itself a credential (client id, API key); kept out of the tree
<driver>.named.<name>              a named password the driver's policies or password-ref GCVs read
driverset.named.<name>             a named password shared by the driver set
```

Keys hold driver names with spaces and values hold anything; the secrets file is split at the
first `=` with no escaping, so a value with a backslash is written as it is.
