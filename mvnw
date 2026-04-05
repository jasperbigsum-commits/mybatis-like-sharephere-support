#!/bin/sh
set -e

BASEDIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
WRAPPER_PROPS="$BASEDIR/.mvn/wrapper/maven-wrapper.properties"

if [ ! -f "$WRAPPER_PROPS" ]; then
  echo "Missing $WRAPPER_PROPS"
  exit 1
fi

DISTRIBUTION_URL=$(grep '^distributionUrl=' "$WRAPPER_PROPS" | cut -d= -f2-)
WRAPPER_DIR="$HOME/.m2/wrapper/dists/apache-maven-3.9.9-bin"

if [ ! -x "$WRAPPER_DIR/bin/mvn" ]; then
  mkdir -p "$HOME/.m2/wrapper/dists"
  ZIP_FILE="${TMPDIR:-/tmp}/apache-maven-3.9.9-bin.zip"
  curl -fsSL "$DISTRIBUTION_URL" -o "$ZIP_FILE"
  unzip -q -o "$ZIP_FILE" -d "$HOME/.m2/wrapper/dists"
  if [ -d "$HOME/.m2/wrapper/dists/apache-maven-3.9.9" ]; then
    mv "$HOME/.m2/wrapper/dists/apache-maven-3.9.9" "$WRAPPER_DIR"
  fi
fi

exec "$WRAPPER_DIR/bin/mvn" -f "$BASEDIR/pom.xml" "$@"
