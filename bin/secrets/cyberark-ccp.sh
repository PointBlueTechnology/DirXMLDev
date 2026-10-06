#!/bin/sh
# A secret from CyberArk's Central Credential Provider (CCP) web service, for a
#   <key>Command=/path/to/cyberark-ccp.sh HOST APPID SAFE OBJECT
# line in a DirXMLDev secrets or environments file. Prints the password only.
#
# Authentication is whatever the CCP application is configured for: the calling host's
# address, an OS user, or a client certificate — give the certificate with
#   CCP_CERT=/path/client.pem   (and CCP_KEY=/path/client.key if separate)
# and a private CA with CCP_CA=/path/ca.pem. Nothing is cached or written.
set -eu
if [ $# -ne 4 ]; then
  echo "usage: cyberark-ccp.sh HOST APPID SAFE OBJECT" >&2
  exit 2
fi
HOST=$1; APPID=$2; SAFE=$3; OBJECT=$4
enc() { printf '%s' "$1" | python3 -c 'import sys,urllib.parse; print(urllib.parse.quote(sys.stdin.read(), safe=""))'; }
URL="https://$HOST/AIMWebService/api/Accounts?AppID=$(enc "$APPID")&Safe=$(enc "$SAFE")&Object=$(enc "$OBJECT")"
set -- curl -sS -f
[ -n "${CCP_CERT:-}" ] && set -- "$@" --cert "$CCP_CERT"
[ -n "${CCP_KEY:-}" ] && set -- "$@" --key "$CCP_KEY"
[ -n "${CCP_CA:-}" ] && set -- "$@" --cacert "$CCP_CA"
"$@" "$URL" | python3 -c 'import json,sys
d=json.load(sys.stdin)
if "Content" not in d:
    sys.stderr.write("CCP returned no Content: " + json.dumps({k:v for k,v in d.items() if k!="Content"}) + "\n"); sys.exit(1)
sys.stdout.write(d["Content"])'
