#!/usr/bin/env bash
# Rebuilds piyingxi.jar from Piyingxi.java. Run from the folder that holds
# Piyingxi.java, jlayer-1.0.1.4.jar and flatlaf-3.5.4.jar (or edit the names below).
set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")"

SRC="Piyingxi.java"
JAR="piyingxi.jar"
LIBS=("jlayer-1.0.1.4.jar" "flatlaf-3.5.4.jar")

[ -f "$SRC" ] || { echo "Missing $SRC in $(pwd)"; exit 1; }
for l in "${LIBS[@]}"; do
  [ -f "$l" ] || { echo "Missing library: $l"; exit 1; }
done
command -v javac >/dev/null || { echo "javac not found: install a JDK (not just a JRE)."; exit 1; }

CP=$(IFS=:; echo "${LIBS[*]}")

echo "Cleaning old class files..."
rm -f ./*.class

echo "Compiling..."
javac -encoding UTF-8 -cp "$CP" -d . "$SRC"

echo "Writing manifest..."
printf 'Main-Class: Piyingxi\nClass-Path: %s\n\n' "${LIBS[*]}" > manifest.txt

echo "Building $JAR..."
jar cfm "$JAR" manifest.txt ./*.class

echo "Done: $(pwd)/$JAR"
echo "Run it with: java -jar $JAR"
