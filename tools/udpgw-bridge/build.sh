#!/bin/bash
# Builds udpgw-bridge.jar. Any JDK 11 or newer will do.
set -euo pipefail
cd "$(dirname "$0")"

# JAVA_HOME is often stale or points at a JRE with no compiler, so verify it before trusting it
# and otherwise fall back to whatever is on PATH.
JAVAC=""
JAR=""
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ] && [ -x "$JAVA_HOME/bin/jar" ]; then
    JAVAC="$JAVA_HOME/bin/javac"
    JAR="$JAVA_HOME/bin/jar"
elif command -v javac > /dev/null && command -v jar > /dev/null; then
    JAVAC=$(command -v javac)
    JAR=$(command -v jar)
else
    for home in /usr/lib/jvm/*/; do
        if [ -x "$home/bin/javac" ] && [ -x "$home/bin/jar" ]; then
            JAVAC="$home/bin/javac"
            JAR="$home/bin/jar"
            break
        fi
    done
fi

if [ -z "$JAVAC" ]; then
    echo "no JDK found: install one, or point JAVA_HOME at a JDK (not a JRE)" >&2
    if [ -n "${JAVA_HOME:-}" ]; then
        echo "JAVA_HOME is currently '$JAVA_HOME'" >&2
    fi
    exit 1
fi
echo "using $JAVAC"

rm -rf build
mkdir -p build/classes
"$JAVAC" --release 11 -Xlint:all -d build/classes src/network/exclave/udpgw/UdpgwBridge.java
printf 'Main-Class: network.exclave.udpgw.UdpgwBridge\n' > build/manifest.txt
"$JAR" --create --file build/udpgw-bridge.jar --manifest build/manifest.txt -C build/classes .
echo "built $(pwd)/build/udpgw-bridge.jar"
