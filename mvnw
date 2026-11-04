#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
VERSION=3.9.9
if command -v mvn >/dev/null 2>&1; then exec mvn "$@"; fi
MAVEN="$ROOT/.tools/apache-maven-$VERSION"
if [ ! -x "$MAVEN/bin/mvn" ]; then
 mkdir -p "$ROOT/.tools"
 URL="https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$VERSION/apache-maven-$VERSION-bin.tar.gz"
 curl -fsSL "$URL" -o "$ROOT/.tools/maven.tar.gz"
 curl -fsSL "$URL.sha512" -o "$ROOT/.tools/maven.sha512"
 EXPECTED=$(tr -d '\r\n ' < "$ROOT/.tools/maven.sha512")
 ACTUAL=$(openssl dgst -sha512 "$ROOT/.tools/maven.tar.gz" | awk '{print $NF}')
 [ "$EXPECTED" = "$ACTUAL" ] || { echo 'Maven checksum mismatch' >&2; exit 1; }
 tar -xzf "$ROOT/.tools/maven.tar.gz" -C "$ROOT/.tools"
fi
exec "$MAVEN/bin/mvn" "$@"
