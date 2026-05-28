#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
AUTOJOIN_DIR="$ROOT_DIR/autojoin"

if [[ ! -d "$AUTOJOIN_DIR" ]]; then
  echo "Error: autojoin directory not found at $AUTOJOIN_DIR" >&2
  exit 1
fi

export JAVA_HOME="/c/Program Files/Java/jdk-24"
MAVEN_BIN="mvn"

cd "$AUTOJOIN_DIR"
"$MAVEN_BIN" test
