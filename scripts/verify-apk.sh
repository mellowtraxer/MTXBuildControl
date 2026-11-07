#!/usr/bin/env bash
set -euo pipefail
apk=app/build/outputs/apk/debug/app-debug.apk
tools="${ANDROID_HOME:?}/build-tools/35.0.0"
expected="${BUILD_NUMBER:?}"
"$tools/apksigner" verify --verbose "$apk"
badging=$("$tools/aapt" dump badging "$apk")
identity=${badging%%$'\n'*}
[[ "$identity" == *"name='de.ricci.buildcontrol'"* ]]
[[ "$identity" == *"versionCode='$expected'"* ]]
[[ "$identity" == *"versionName='1.0.$expected'"* ]]
printf '%s\n' "Verified package de.ricci.buildcontrol, version 1.0.$expected, code $expected."
