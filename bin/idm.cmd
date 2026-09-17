@echo off
rem DirXML Dev launcher for Windows: bin\idm <command> [args]
rem Mirrors bin/idm: JDK 21 from IDM_JAVA_HOME (else JAVA_HOME), class path from
rem target\classes + lib\*.jar + the simulator jar in %USERPROFILE%\.m2, JVM options from IDM_JAVA_OPTS.
setlocal EnableDelayedExpansion
set "HERE=%~dp0.."
set "JH=%IDM_JAVA_HOME%"
if "%JH%"=="" set "JH=%JAVA_HOME%"
if "%JH%"=="" (
  echo ERROR: set IDM_JAVA_HOME ^(or JAVA_HOME^) to a JDK 21 install 1>&2
  exit /b 1
)
if not exist "%JH%\bin\java.exe" (
  echo ERROR: no java.exe under "%JH%" 1>&2
  exit /b 1
)
set "SIM_VER=%IDM_SIM_VERSION%"
if "%SIM_VER%"=="" set "SIM_VER=1.5.2"
set "SIM_JAR=%USERPROFILE%\.m2\repository\com\pointblue\dirxml\dirxml-simulator\%SIM_VER%\dirxml-simulator-%SIM_VER%.jar"
if not exist "%SIM_JAR%" (
  echo ERROR: simulator jar %SIM_VER% not installed; run "mvn install" in the DirXMLSimulator repo 1>&2
  exit /b 1
)
if not exist "%HERE%\target\classes" (
  pushd "%HERE%"
  set "JAVA_HOME=%JH%"
  call mvn -q -o compile
  popd
)
set "CP=%HERE%\target\classes;%SIM_JAR%"
for %%j in ("%HERE%\lib\*.jar") do set "CP=!CP!;%%~fj"
"%JH%\bin\java.exe" %IDM_JAVA_OPTS% -cp "%CP%" com.pointblue.dirxml.dev.Cli %*
exit /b %ERRORLEVEL%
