#!/bin/sh
# Builds and tests release $1 and packs its files into build/release:
#   fastmultiversion-<version>-maven-repository.zip  the Gradle plugin and its libraries as a Maven repository
#   fastmultiversion-intellij-plugin-<version>.zip   the IntelliJ plugin
# semantic-release runs it (.releaserc) after writing the version to gradle.properties.
set -eu
version="$1"

rm -rf build/repo build/release
./gradlew build :intellij-plugin:buildPlugin publishAllPublicationsToReleaseRepository -Prelease

mkdir -p build/release
(cd build/repo && zip -qr "../release/fastmultiversion-$version-maven-repository.zip" .)
cp "intellij-plugin/build/distributions/intellij-plugin-$version.zip" "build/release/fastmultiversion-intellij-plugin-$version.zip"
