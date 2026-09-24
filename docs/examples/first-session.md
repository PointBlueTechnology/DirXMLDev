# First session (fictional)

Run from a client directory whose `environments.properties` is filled in from
[environments.properties.example](environments.properties.example). `stg` and
`AD Driver` stand in for your environment and your driver. Output lines are
illustrative, not a transcript from a vault.

```bash
# 1. Take the live driver set as code.
bin/idm import-live tree/ --env stg
bin/idm validate tree/
# OK: 0 error(s), …

bin/idm vault.diff tree/ --env stg
# no differences

git add tree/ && git commit -m "Import stg driver set"

# 2. See the subscriber chain and the fishbone.
bin/idm query tree/ chain "AD Driver" sub
bin/idm query tree/ fishbone "AD Driver"

# 3. Add a policy. Copy normalize-title.policy.xml from this directory first
#    (or pass its path). The file is fictional.
bin/idm policy.add tree/ \
  --driver "AD Driver" \
  --scope subscriber \
  --name "ACME-sub-ctp-NormalizeTitle" \
  --content-file normalize-title.policy.xml \
  --link subscriber-command \
  --at last
bin/idm validate tree/

# 4. Diff against the previous tree. --against is a directory, not a git rev.
git worktree add /tmp/tree-before HEAD
bin/idm simulate tree/ --cases cases/ --against /tmp/tree-before/tree
git worktree remove /tmp/tree-before

git add tree/ && git commit -m "AD: add ACME-sub-ctp-NormalizeTitle"

# 5. Show the plan. Nothing is written.
bin/idm vault.diff tree/ --env stg
bin/idm vault.deploy tree/ --env stg --driver "AD Driver" --dry-run

# 6. After a person has read the plan:
bin/idm vault.deploy tree/ --env stg --driver "AD Driver" --yes
bin/idm vault.verify tree/ --env stg

# Production, only when a person types the environment name:
# bin/idm vault.deploy tree/ --env prd --driver "AD Driver" --dry-run
# bin/idm vault.deploy tree/ --env prd --driver "AD Driver" --yes --confirm prd
```
