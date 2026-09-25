@echo off
rem DirXML Dev launcher for Windows: bin\idm <command> [args]
rem Mirrors bin/idm: JDK 21 from IDM_JAVA_HOME (else JAVA_HOME), class path from
rem target\classes + lib\*.jar + the simulator jar in %USERPROFILE%\.m2, JVM options from IDM_JAVA_OPTS.
setlocal EnableDelayedExpansion
set "HERE=%~dp0.."
set "CMD=%~1"
set "JH=%IDM_JAVA_HOME%"
if "%JH%"=="" set "JH=%JAVA_HOME%"
if "%JH%"=="" (
  if /I "%CMD%"=="doctor" (
    echo DirXML Dev — doctor
    echo   jdk: FAIL  JDK 21 not found. Set IDM_JAVA_HOME or JAVA_HOME to a JDK 21 install.
    echo DOCTOR: PROBLEMS FOUND
    exit /b 1
  )
  echo ERROR: set IDM_JAVA_HOME ^(or JAVA_HOME^) to a JDK 21 install 1>&2
  exit /b 1
)
if not exist "%JH%\bin\java.exe" (
  echo ERROR: no java.exe under "%JH%" 1>&2
  exit /b 1
)
set "SIM_VER=%IDM_SIM_VERSION%"
if "%SIM_VER%"=="" set "SIM_VER=1.6.0"
set "SIM_JAR=%USERPROFILE%\.m2\repository\com\pointblue\dirxml\dirxml-simulator\%SIM_VER%\dirxml-simulator-%SIM_VER%.jar"
if not exist "%SIM_JAR%" (
  if /I "%CMD%"=="doctor" (
    if not exist "%HERE%\target\classes\com\pointblue\dirxml\dev\Cli.class" (
      echo DirXML Dev — doctor
      echo   simulator: FAIL  %SIM_VER% missing
      echo     Run "mvn install" in the DirXMLSimulator repo so %SIM_JAR% exists. See docs/install.md section 2.
      echo   cli: FAIL  Java doctor did not start
      echo DOCTOR: PROBLEMS FOUND
      exit /b 1
    )
  ) else (
    echo ERROR: simulator jar %SIM_VER% not installed; run "mvn install" in the DirXMLSimulator repo 1>&2
    exit /b 1
  )
)
if not exist "%HERE%\target\classes\com\pointblue\dirxml\dev\Cli.class" (
  pushd "%HERE%"
  set "JAVA_HOME=%JH%"
  call mvn -q -o compile
  popd
)
set "CP=%HERE%\target\classes;%SIM_JAR%"
for %%j in ("%HERE%\lib\*.jar") do set "CP=!CP!;%%~fj"
"%JH%\bin\java.exe" %IDM_JAVA_OPTS% -Didm.home="%HERE%" -Didm.sim.version="%SIM_VER%" -Didm.sim.jar="%SIM_JAR%" -Didm.launcher=1 -cp "%CP%" com.pointblue.dirxml.dev.Cli %*
exit /b %ERRORLEVEL%
