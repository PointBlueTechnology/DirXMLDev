# Identity Manager work in this repository

Fictional template. Copy to `AGENTS.md` at the root of a client repository
and replace the two paths. Commit it. It is the agent setup: Claude Code,
Cursor, Codex, Copilot, or any other agent that can read a file and run a
shell.

The driver set in `tree/` is the source of truth. The Identity Vault is a
deploy target. Change the tree with DirXMLDev:

- `bin/idm`: `/absolute/path/to/DirXMLDev/bin/idm`
- Full instructions: `/absolute/path/to/DirXMLDev/docs/agent-guide.md`
- Day-to-day commands: `/absolute/path/to/DirXMLDev/docs/day-to-day.md`

Run `bin/idm` with no arguments before inventing a flag. `bin/apps --help`
lists the Identity Applications helper.

## Rules

- Show `vault.diff` and `vault.deploy --dry-run` in the conversation before
  any write. Wait for a person. Then `--yes` or `--step`.
- Production needs `--confirm` and the environment name, typed by a person.
  The tree must be committed. If the vault has drifted, stop and say so.
  `--capture-drift` records that state; it does not deploy.
- Never print a secret, a snapshot, or a password. Ask for a missing secret
  to be added to `secrets-<env>.properties` (or a keychain / command /
  environment variable). Do not ask anyone to paste the value into chat.
- Never pass `--force`. A refusal is information.
- Do not delete a driver or empty a kind (entitlements, forms, PRDs, …)
  unless a person asked for that. `--delete-driver` and `--delete-all` are
  explicit. A plan full of deletes means re-import with `import-live`.
- `driver.stop` leaves events queueing in the cache. `driver.cache clear`
  prints the count first; read it to the person. On staging and production
  it also needs `--confirm`.
- One operation, one commit. Commit `deploy-log/` after a deploy.
- `validate` on a tree imported from a running vault should report 0 errors.
  If it does not, report that. Do not edit the tree to silence it.

`environments.properties` and `secrets*.properties` are gitignored and mode
600. Do not commit them.
