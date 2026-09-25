#!/bin/sh
# Compile and run the tests that do not need the proprietary IDM jars or the simulator.
# Invoked by `mvn -Pidm.portable test` after junit has been copied to target/portable-lib.
# Output classes go to target/portable-classes so they are not what `bin/idm` loads.
# See docs/install.md section 2.4.
set -e
HERE=$(cd "$(dirname "$0")/.." && pwd)
cd "$HERE"
LIB="$HERE/target/portable-lib"
OUT="$HERE/target/portable-classes"

if ! ls "$LIB"/junit-*.jar >/dev/null 2>&1; then
  echo "ERROR: $LIB has no junit jar. Run: mvn -B -Pidm.portable test" >&2
  exit 1
fi

CP=$(find "$LIB" -name '*.jar' | tr '\n' ':')
rm -rf "$OUT"
mkdir -p "$OUT"
javac --release 21 -encoding UTF-8 -cp "$CP" -d "$OUT" \
  src/stub/java/com/pointblue/dirxml/dev/deploy/Vault.java \
  src/main/java/com/pointblue/dirxml/dev/json/Json.java \
  src/main/java/com/pointblue/dirxml/dev/deploy/SecretSource.java \
  src/main/java/com/pointblue/dirxml/dev/deploy/Secrets.java \
  src/main/java/com/pointblue/dirxml/dev/deploy/Environments.java \
  src/main/java/com/pointblue/dirxml/dev/deploy/AgentWriteGate.java \
  src/main/java/com/pointblue/dirxml/dev/operate/TraceViewer.java \
  src/main/java/com/pointblue/dirxml/dev/Doctor.java \
  src/test/java/com/pointblue/dirxml/dev/DoctorTest.java \
  src/test/java/com/pointblue/dirxml/dev/deploy/AgentWriteGateTest.java \
  src/test/java/com/pointblue/dirxml/dev/deploy/TraceViewerArgsTest.java \
  src/test/java/com/pointblue/dirxml/dev/operate/TraceViewerTest.java

java -cp "$OUT:$CP" org.junit.runner.JUnitCore \
  com.pointblue.dirxml.dev.DoctorTest \
  com.pointblue.dirxml.dev.deploy.AgentWriteGateTest \
  com.pointblue.dirxml.dev.deploy.TraceViewerArgsTest \
  com.pointblue.dirxml.dev.operate.TraceViewerTest
