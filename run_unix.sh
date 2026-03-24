#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

ARCH="$(uname -m)"
case "$ARCH" in
    x86_64|amd64)
        NATIVE_DIR="$ROOT_DIR/natives/linux64"
        ;;
    i386|i486|i586|i686|x86)
        NATIVE_DIR="$ROOT_DIR/natives/linux32"
        ;;
    *)
        echo "Unsupported Linux architecture: $ARCH" >&2
        exit 1
        ;;
esac

JAR_PATH="$ROOT_DIR/build/libs/Discord Audio Stream Bot.jar"

gradle shadowJar

exec java \
    -Djava.library.path="$NATIVE_DIR" \
    --enable-native-access=ALL-UNNAMED \
    -jar "$JAR_PATH"
