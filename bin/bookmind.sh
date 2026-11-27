#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
java=java
if [ -n "${JAVA_HOME:-}" ]; then java="$JAVA_HOME/bin/java"; fi
command -v "$java" >/dev/null || { echo 'BookMind requires Java 21. Check JAVA_HOME or PATH.' >&2; exit 1; }
echo "BookMind: http://localhost:${BOOKMIND_PORT:-8080} (Ctrl+C to stop)"
exec "$java" -jar bookmind.jar --server.address=127.0.0.1 "$@"
