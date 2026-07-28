#!/usr/bin/env bash
# Double-click / terminal launcher for macOS and Linux.
#
# Mirrors START-HERE.bat: check Java before anything else and explain the fix in
# plain language, rather than letting the generated start script fail with a
# one-line message the user never gets to read.

set -uo pipefail
cd "$(dirname "$0")"

echo
echo "  Gohil Bookkeeper"
echo "  ================"
echo

JAVA_CMD=""
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA_CMD="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
  JAVA_CMD="java"
fi

if [ -z "$JAVA_CMD" ]; then
  cat <<'EOF'
  ------------------------------------------------------------------
  Java was not found on this computer.

  This app needs Java 17 or newer. It is a free, one-time install:

    macOS   brew install --cask temurin@17
            or download from https://adoptium.net/temurin/releases/?version=17

    Linux   sudo apt install openjdk-17-jre     (Debian/Ubuntu)
            sudo dnf install java-17-openjdk    (Fedora)

  Then run this script again. To check it worked:  java -version
  ------------------------------------------------------------------
EOF
  exit 1
fi

echo "  Using Java:"
"$JAVA_CMD" -version 2>&1 | sed 's/^/    /'
echo
echo "  Starting the server. Your browser should open in a few seconds."
echo "  Leave this window open while you use the app. Press Ctrl+C to stop."
echo

./bin/gohil-bookkeeper "$@"
code=$?

echo
if [ $code -ne 0 ]; then
  cat <<EOF
  ------------------------------------------------------------------
  The server stopped with an error (code $code).

  "UnsupportedClassVersionError" means your Java is older than 17.
  "Address already in use" means port 8080 is taken — try:
      ./start-here.sh --port 8090
  ------------------------------------------------------------------
EOF
fi
exit $code
