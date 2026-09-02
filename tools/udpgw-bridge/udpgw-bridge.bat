@echo off
setlocal
rem Windows launcher. Arguments are passed straight through to the bridge.
rem The jar sits next to this script in a release, and under build\ in a source tree.
set "JAR=%~dp0udpgw-bridge.jar"
if not exist "%JAR%" set "JAR=%~dp0build\udpgw-bridge.jar"
if not exist "%JAR%" (
    echo udpgw-bridge.jar not found next to this script or in build\ 1>&2
    echo Download a release that includes the jar, or build one with a JDK: 1>&2
    echo   javac --release 11 -d build\classes src\network\exclave\udpgw\UdpgwBridge.java 1>&2
    exit /b 1
)
java -jar "%JAR%" %*
exit /b %ERRORLEVEL%
