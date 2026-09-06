#!/usr/bin/env sh
set -eu
gradle wrapper --gradle-version 9.6
echo "Gradle wrapper generated. Run ./gradlew :lane-engine:test"
