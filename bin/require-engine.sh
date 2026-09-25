#!/bin/sh
# Names every proprietary IDM jar and the DirXML Simulator jar a full `mvn test` needs.
# Exit 0 when they are all present. Exit 1 when any is missing, with setup steps.
# `--inform` prints the same list and exits 0: GitHub Actions uses that so a clean
# runner is not a failed check. The jars are not in git and the simulator is not
# on Maven Central. See docs/install.md section 2. This script never prints secrets.
#
# REQUIRED_JARS: dirxml.jar dirxml_misc.jar nxsl.jar xp.jar CommonDriverShim.jar jclient.jar dhutil.jar XDS.jar js.jar ldap.jar
set -e
inform=0
if [ "${1:-}" = "--inform" ]; then
  inform=1
fi
HERE=$(cd "$(dirname "$0")/.." && pwd)
SIM_VER="${IDM_SIM_VERSION:-1.6.0}"
SIM_JAR="${HOME}/.m2/repository/com/pointblue/dirxml/dirxml-simulator/${SIM_VER}/dirxml-simulator-${SIM_VER}.jar"

missing=""
links=""
if [ -L "$HERE/lib" ]; then
  links="${links}
  lib (directory symlink)"
fi
for j in dirxml.jar dirxml_misc.jar nxsl.jar xp.jar CommonDriverShim.jar jclient.jar dhutil.jar XDS.jar js.jar ldap.jar; do
  if [ -L "$HERE/lib/$j" ]; then
    links="${links}
  lib/${j} (symlink)"
  elif [ ! -f "$HERE/lib/$j" ]; then
    missing="${missing}
  lib/${j}"
    base="${j%.jar}"
    for v in "$HERE/lib/$base"-*.jar; do
      [ -f "$v" ] && missing="${missing}  (found $(basename "$v"): copy it as ${j})"
      break
    done
  fi
done

sim_missing=""
if [ ! -f "$SIM_JAR" ]; then
  sim_missing="
  ${SIM_JAR}"
fi

if [ -n "$missing" ] || [ -n "$links" ] || [ -n "$sim_missing" ]; then
  if [ "$inform" = 1 ]; then
    headline="Engine not available on this machine (not a test failure). Full mvn test needs the jars below; doctor and the write gate do not."
  else
    headline="ERROR: DirXMLDev cannot run mvn test until the proprietary engine jars and the DirXML Simulator are installed."
  fi
  link_block=""
  if [ -n "$links" ]; then
    link_block="
Not regular files (Maven's requireFilesExist reports these as missing):${links}"
  fi
  cat >&2 <<EOF
${headline}

Missing:${missing}${sim_missing}${link_block}

The NetIQ/OpenText jars are proprietary and are not committed (lib/ is gitignored).
dirxml-simulator is not on Maven Central. The sources import both, so the suite
cannot pass without them. pom.xml keeps those dependencies in the engine profile
(active when lib/dirxml.jar exists) so that mvn test on a machine without the
jars stops in the enforcer's requireFilesExist rule — the file list above —
instead of an unresolved-artifact error. A clean GitHub-hosted runner has neither.
That rule compares a path with its canonical path. A directory symlink of lib/,
or a symlink of a jar, differs and is reported missing even when ls shows the file.
Copy the jars into a real lib/ directory.

Setup (docs/install.md, section 2):
  1. Copy dirxml.jar, dirxml_misc.jar, nxsl.jar, xp.jar, CommonDriverShim.jar,
     jclient.jar, dhutil.jar, XDS.jar, js.jar, and ldap.jar into a real lib/
     directory (regular files, not symlinks). Engines from 4.10.2 on ship
     xp.jar as xp-1.0.0.jar: copy it as xp.jar.
       mkdir -p lib && cp /path/to/DirXMLSimulator/lib/dirxml.jar lib/  # and the other nine
     They come from an IDM engine (/opt/novell/eDirectory/lib/dirxml/classes/)
     or a Designer install. Do not commit them.
     Do not use: ln -s /path/to/DirXMLSimulator/lib lib
     Do not symlink the individual jars into lib/. Neither passes requireFilesExist.
  2. Build the simulator and install it into the local Maven repository:
       cd /path/to/DirXMLSimulator && mvn install
     Expected artifact: com.pointblue.dirxml:dirxml-simulator:${SIM_VER}
     (IDM_SIM_VERSION overrides that pin only when you mean to select another build).
  3. Re-run: mvn -B test
     Without the jars, doctor and the write-gate tests still run:
       mvn -B -Pidm.portable test
     Or check the same preconditions with: bin/idm doctor
EOF
  if [ "$inform" = 1 ]; then
    exit 0
  fi
  exit 1
fi

echo "engine jars and dirxml-simulator ${SIM_VER} are present"
