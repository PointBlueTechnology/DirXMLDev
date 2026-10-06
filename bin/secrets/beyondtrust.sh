#!/bin/sh
# A managed account's password from BeyondTrust Password Safe, for a
#   <key>Command=/path/to/beyondtrust.sh HOST SYSTEM ACCOUNT
# line in a DirXMLDev secrets or environments file. Prints the password only.
#
# Signs in with an API registration (header "PS-Auth key=…; runas=…"), requests the
# credential, reads it and checks the request back in. The API key and run-as user come
# from the environment, never the command line:
#   BT_API_KEY=…         the API registration's key
#   BT_RUNAS=…           the user the registration runs as
#   BT_CA=/path/ca.pem   a private CA (optional)
# Put BT_API_KEY in the Keychain and export it in the shell that starts idm / idmweb,
# e.g. BT_API_KEY=$(security find-generic-password -s beyondtrust -w).
set -eu
if [ $# -ne 3 ]; then
  echo "usage: beyondtrust.sh HOST SYSTEM ACCOUNT" >&2
  exit 2
fi
: "${BT_API_KEY:?set BT_API_KEY}"; : "${BT_RUNAS:?set BT_RUNAS}"
HOST=$1 SYSTEM=$2 ACCOUNT=$3 python3 - <<'PY'
import json, os, ssl, sys, urllib.request, urllib.parse
from http.cookiejar import CookieJar
host, system, account = os.environ["HOST"], os.environ["SYSTEM"], os.environ["ACCOUNT"]
base = "https://" + host + "/BeyondTrust/api/public/v3/"
ctx = ssl.create_default_context(cafile=os.environ.get("BT_CA") or None)
opener = urllib.request.build_opener(urllib.request.HTTPSHandler(context=ctx), urllib.request.HTTPCookieProcessor(CookieJar()))
auth = "PS-Auth key=%s; runas=%s;" % (os.environ["BT_API_KEY"], os.environ["BT_RUNAS"])
def call(method, path, body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(base + path, data=data, method=method, headers={"Authorization": auth, "Content-Type": "application/json"})
    with opener.open(req) as r:
        text = r.read().decode()
        return json.loads(text) if text else None
call("POST", "Auth/SignAppin")
try:
    accounts = call("GET", "ManagedAccounts?systemName=%s&accountName=%s" % (urllib.parse.quote(system), urllib.parse.quote(account)))
    acct = accounts[0] if isinstance(accounts, list) else accounts
    req_id = call("POST", "Requests", {"SystemID": acct["SystemId"], "AccountID": acct["AccountId"], "DurationMinutes": 5, "Reason": "DirXMLDev", "ConflictOption": "reuse"})
    secret = call("GET", "Credentials/%s" % req_id)
    try:
        call("PUT", "Requests/%s/Checkin" % req_id, {"Reason": "DirXMLDev done"})
    except Exception:
        pass
    sys.stdout.write(secret if isinstance(secret, str) else json.dumps(secret))
finally:
    try:
        call("POST", "Auth/Signout")
    except Exception:
        pass
PY
