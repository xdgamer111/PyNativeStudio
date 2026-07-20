#!/usr/bin/env sh
set -eu
if command -v gradle >/dev/null 2>&1; then
  gradle assembleDebug
else
  echo "Gradle is not installed. Open this project in Android Studio and choose Build > Build APK(s)." >&2
  exit 1
fi
