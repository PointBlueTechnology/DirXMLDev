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
SIM_VER="${IDM_SIM_VERSION:-1.5.2}"
SIM_JAR="${HOME}/.m2/repository/com/pointblue/dirxml/dirxml-simulator/${SIM_VER}/dirxml-simulator-${SIM_VER}.jar"

missing=""
for j in dirxml.jar dirxml_misc.jar nxsl.jar xp.jar CommonDriverShim.jar jclient.jar dhutil.jar XDS.jar js.jar ldap.jar; do
  if [ ! -f "$HERE/lib/$j" ]; then
    missing="${missing}
  lib/${j}"
  fi
done

sim_missing=""
if [ ! -f "$SIM_JAR" ]; then
  sim_missing="
  ${SIM_JAR}"
fi

if [ -n "$missing" ] || [ -n "$sim_missing" ]; then
  if [ "$inform" = 1 ]; then
    headline="Engine not available on this machine (not a test failure). Full mvn test needs the jars below; doctor and the write gate do not."
  else
    headline="ERROR: DirXMLDev cannot run mvn test until the proprietary engine jars and the DirXML Simulator are installed."
  fi
  cat >&2 <<EOF
${headline}

Missing:${missing}${sim_missing}

The NetIQ/OpenText jars are proprietary and are not committed (lib/ is gitignored).
dirxml-simulator is not on Maven Central. The sources import both, so the suite
cannot pass without them. pom.xml keeps those dependencies in the engine profile
(active when lib/dirxml.jar exists) so that mvn test on a machine without the
jars stops in the validate check — the file list above — instead of an
unresolved-artifact error. A clean GitHub-hosted runner has neither.
The check follows symlinks. Maven's requireFilesExist rule does not: it treats a
path whose canonical file differs (a directory symlink of lib/, or a symlink of
a jar) as missing, so this build does not use that rule.

Setup (docs/install.md, section 2):
  1. Make lib/ visible to Maven. Either of these works:
       ln -s /path/to/DirXMLSimulator/lib lib
       # or copy the ten jars into a real lib/ directory
     The jars are dirxml.jar, dirxml_misc.jar, nxsl.jar, xp.jar,
     CommonDriverShim.jar, jclient.jar, dhutil.jar, XDS.jar, js.jar, and ldap.jar.
     They come from an IDM engine (/opt/novell/eDirectory/lib/dirxml/classes/)
     or a Designer install. Do not commit them.
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
