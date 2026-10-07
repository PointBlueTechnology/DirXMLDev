#!/bin/sh
# Live check of the event store commands against a throwaway PostgreSQL in Docker: the Event Logger's
# DDL (fixtures/events/create.sql, from its release 2.0.0) and the contract's example rows
# (fixtures/events/rows.sql), then events.describe, query events with every selector, query event,
# and events.case into a synthetic tree. Needs Docker and a built DirXMLDev (bin/idm). Exits non-zero
# on the first failure; skipped with a notice when Docker is not there.
set -e
HERE=$(cd "$(dirname "$0")/.." && pwd)
if ! docker version >/dev/null 2>&1; then
  echo "smoke-events: Docker is not available; skipped"
  exit 0
fi
PORT=$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()')
NAME="idm-events-smoke-$$"
WORK=$(mktemp -d)
cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; rm -rf "$WORK"; }
trap cleanup EXIT
docker run -d --rm --name "$NAME" -p "127.0.0.1:$PORT:5432" -e POSTGRES_PASSWORD=smoke -e POSTGRES_DB=idmEvent postgres:16 >/dev/null
i=0
until docker exec "$NAME" pg_isready -U postgres -d idmEvent >/dev/null 2>&1; do
  i=$((i + 1)); [ $i -gt 60 ] && { echo "postgres did not start"; docker logs "$NAME" | tail -5; exit 1; }
  sleep 1
done
sleep 2
docker exec -i "$NAME" psql -q -U postgres -d idmEvent < "$HERE/src/test/resources/fixtures/events/create.sql"
docker exec -i "$NAME" psql -q -U postgres -d idmEvent < "$HERE/src/test/resources/fixtures/events/rows.sql"
docker exec -i "$NAME" psql -q -U postgres -d idmEvent <<'SQL'
CREATE USER eventlogger_reader WITH PASSWORD 'reader';
GRANT CONNECT ON DATABASE "idmEvent" TO eventlogger_reader;
GRANT USAGE ON SCHEMA public TO eventlogger_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO eventlogger_reader;
SQL
"$HERE/bin/idm" import-ldif "$HERE/src/test/resources/fixtures/synthetic/driverset.ldif" "$WORK/tree" >/dev/null
cat > "$WORK/environments.properties" <<ENV
lab.url=ldaps://127.0.0.1:1
lab.bindDn=cn=nobody,o=none
lab.password=unused
lab.driverSet=cn=SynthSet,o=synth
lab.tier=dev
lab.eventsUrl=jdbc:postgresql://127.0.0.1:$PORT/idmEvent
lab.eventsUser=eventlogger_reader
lab.eventsPassword=reader
masked.url=ldaps://127.0.0.1:1
masked.bindDn=cn=nobody,o=none
masked.password=unused
masked.driverSet=cn=SynthSet,o=synth
masked.tier=dev
masked.eventsUrl=jdbc:postgresql://127.0.0.1:$PORT/idmEvent
masked.eventsUser=eventlogger_reader
masked.eventsPassword=reader
masked.eventsPseudonymise=true
ENV
chmod 600 "$WORK/environments.properties"
export IDM_ENVIRONMENTS="$WORK/environments.properties"
IDM="$HERE/bin/idm"
T="$WORK/tree"
check() { if [ "$2" = "0" ]; then echo "ok   $1"; else echo "FAIL $1"; exit 1; fi; }
expect() { # name, expected substring, command...
  n=$1; want=$2; shift 2
  out=$("$@" 2>&1) || true
  case "$out" in *"$want"*) echo "ok   $n";; *) echo "FAIL $n"; echo "$out" | head -20; exit 1;; esac
}
expect "describe: rows, newest, migrated" "rows               9" "$IDM" events.describe "$T" --env lab
expect "describe: schema versions seen" "maxSchemaVersion   2" "$IDM" events.describe "$T" --env lab
expect "query: an object's timeline ascends from the add" "add" "$IDM" query "$T" events --env lab --dn "\\SYNTH\\data\\people\\jdoe"
first=$("$IDM" query "$T" events --env lab --dn "\\SYNTH\\data\\people\\jdoe" --json | python3 -c 'import json,sys; print(json.load(sys.stdin)["events"][0]["type"])')
check "query: the timeline's first row is the add ($first)" "$([ "$first" = add ] && echo 0 || echo 1)"
expect "query: an LDAP DN converts with the store's tree name" "synth#1" "$IDM" query "$T" events --env lab --dn "cn=jdoe,ou=people,o=data" --json
expect "query: a subtree" "admins" "$IDM" query "$T" events --env lab --under "\\SYNTH\\data\\groups"
expect "query: by the end of a DN" "john.doe" "$IDM" query "$T" events --env lab --name "\\john.doe"
expect "query: a driver by its tree name, own rows" "synth#7" "$IDM" query "$T" events --env lab --driver Loop --own --json
n=$("$IDM" query "$T" events --env lab --driver Loop --logged --json | python3 -c 'import json,sys; print(json.load(sys.stdin)["count"])')
check "query: what the driver's policies logged (2 rows, got $n)" "$([ "$n" = 2 ] && echo 0 || echo 1)"
expect "query: a policy's input documents" "sub-ctp-Normalize/input" "$IDM" query "$T" events --env lab --driver Loop --policy sub-ctp-Normalize --stage input
expect "query: types and class" "admins" "$IDM" query "$T" events --env lab --type modify --class Group
expect "query: a time window" "synth#5" "$IDM" query "$T" events --env lab --since 2026-10-07T14:18:00Z --until 2026-10-07T14:19:30Z --json
expect "query: free text with a window" "Clerk" "$IDM" query "$T" events --env lab --text Clerk --since 2026-01-01T00:00:00Z --json
expect "query: modify events touching an attribute" "synth#7" "$IDM" query "$T" events --env lab --attr Member --json
expect "query event: the other rows of the same engine event" "sub-ctp-Normalize/output" "$IDM" query "$T" event 2 --env lab
expect "query: one engine event by its engine id" "sub-ctp-Normalize/output" "$IDM" query "$T" events --env lab --event-id "synth#2"
first=$("$IDM" query "$T" events --env lab --event-id "synth#2" --json | python3 -c 'import json,sys; print(json.load(sys.stdin)["events"][0]["policy"])')
check "query: the driver's own row comes first (policy=$first)" "$([ "$first" = None ] && echo 0 || echo 1)"
expect "query event: the modify diff" "Given Name: Johnny → John" "$IDM" query "$T" event 2 --env lab
expect "query event: the XML when asked" "<modify-attr" "$IDM" query "$T" event 2 --env lab --xml
expect "query: a version-1 row still lists" "synth#0" "$IDM" query "$T" events --env lab --type modify --class User --json
expect "query: a bad type is refused" "not add, modify" "$IDM" query "$T" events --env lab --type purge
expect "masked: the row still reads" "\"schemaVersion\": 2" "$IDM" query "$T" event 1 --env masked --json
masked=$("$IDM" query "$T" event 1 --env masked --json | python3 -c 'import json,sys; d=json.load(sys.stdin); print("John" in json.dumps(d) or "jdoe" in d["srcDn"])')
check "masked: John and jdoe are gone (leaked=$masked)" "$([ "$masked" = False ] && echo 0 || echo 1)"
expect "events.case: a dry run names the files" "would write" "$IDM" events.case "$T" --env lab --id 1 --name jdoe-add --dry-run
expect "events.case: writes into the tree's cases/" "wrote" "$IDM" events.case "$T" --env lab --id 1 --name jdoe-add
check "events.case: input.xds and case.properties exist" "$([ -f "$T/cases/jdoe-add/input.xds" ] && grep -q '^driver=Loop$' "$T/cases/jdoe-add/case.properties" && grep -q '^source=events:lab:1$' "$T/cases/jdoe-add/case.properties" && echo 0 || echo 1)"
expect "events.case: an existing case is refused without --replace" "REFUSED" "$IDM" events.case "$T" --env lab --id 1 --name jdoe-add
expect "events.case: a policy row records its policy" "sourcePolicy=sub-ctp-Normalize" sh -c "\"$IDM\" events.case \"$T\" --env lab --id 7 --name policy-in >/dev/null && cat \"$T/cases/policy-in/case.properties\""
expect "events.case: a row without XML says what it needs" "storeXML=false" "$IDM" events.case "$T" --env lab --id 3 --name no-xml
expect "events.case: a version-1 row without XML is refused" "schema version 1" "$IDM" events.case "$T" --env lab --id 9 --name old
expect "events.case: the masked store marks the case" "pseudonymised=true" sh -c "\"$IDM\" events.case \"$T\" --env masked --id 1 --name masked-add >/dev/null && cat \"$T/cases/masked-add/case.properties\""
echo "smoke-events: all passed"
