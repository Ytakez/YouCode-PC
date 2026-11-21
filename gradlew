#!/bin/sh
# This source bundle intentionally uses the Gradle installation supplied by CI.
# A Gradle wrapper JAR is not distributed in this repository snapshot.
if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi
echo "Gradle is not installed. Install Gradle 8.10.2 and JDK 17, or run the GitHub Actions workflow." >&2
exit 1
