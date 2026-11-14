#!/bin/sh
# Small checksum-pinned Maven launcher for macOS/Linux. Requires JDK 25, curl, tar and shasum.
set -eu
version=3.10.0
expected=908b1501bfb420bf7c8affb855534a9c407fd6099367bfb9f2f2dcb8e9799102bffb84518cde74c679bd76870247c6528683abdd620581bffa90f95d92d175aa
guava_version=33.7.2-jre
guava_expected=bd663dc02f2293a552d82829518435b8cfcd77b7c73eeb638aef93918ef7613ad2a869003b09d1afb4446fa74308041daa7a6aea85c1bedb17db1f5f0c2c5c0b
cache="${UACRF_BUILD_CACHE:-${TMPDIR:-/tmp}/uacrf-platform-maven}"
maven="$cache/apache-maven-$version/bin/mvn"
if [ ! -x "$maven" ]; then
 mkdir -p "$cache"
 stage=$(mktemp -d "$cache/download.XXXXXX")
 trap 'rm -rf "$stage"' EXIT HUP INT TERM
 curl --fail --location --proto '=https' --tlsv1.2 "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$version/apache-maven-$version-bin.tar.gz" -o "$stage/maven.tar.gz"
 actual=$(shasum -a 512 "$stage/maven.tar.gz" | cut -d ' ' -f 1)
 [ "$actual" = "$expected" ] || { echo 'Maven checksum mismatch' >&2; exit 1; }
 tar -xzf "$stage/maven.tar.gz" -C "$stage"
 curl --fail --location --proto '=https' --tlsv1.2 "https://repo.maven.apache.org/maven2/com/google/guava/guava/$guava_version/guava-$guava_version.jar" -o "$stage/guava.jar"
 actual_guava=$(shasum -a 512 "$stage/guava.jar" | cut -d ' ' -f 1)
 [ "$actual_guava" = "$guava_expected" ] || { echo 'Guava checksum mismatch' >&2; exit 1; }
 rm "$stage/apache-maven-$version/lib/guava-33.7.1-jre.jar"
 mv "$stage/guava.jar" "$stage/apache-maven-$version/lib/guava-$guava_version.jar"
 if ! mv "$stage/apache-maven-$version" "$cache/" 2>/dev/null; then
  attempts=0
  until [ -x "$maven" ] || [ "$attempts" -ge 30 ]; do
   sleep 1
   attempts=$((attempts + 1))
  done
  [ -x "$maven" ] || { echo 'Maven cache is being initialized by another process' >&2; exit 1; }
 fi
fi
exec "$maven" "$@"
