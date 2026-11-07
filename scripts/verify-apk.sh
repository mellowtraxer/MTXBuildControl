#!/usr/bin/env bash
set -euo pipefail
apk=${1:-app/build/outputs/apk/debug/app-debug.apk}
tools="${ANDROID_HOME:?}/build-tools/35.0.0"
expected="${BUILD_NUMBER:?}"
signature=$("$tools/apksigner" verify --verbose --print-certs "$apk")
printf '%s\n' "$signature"
[[ "$signature" == *"Signer #1 certificate SHA-256 digest: ${MTX_EXPECTED_SIGNER_SHA256:?}"* ]]
badging=$("$tools/aapt" dump badging "$apk")
identity=${badging%%$'\n'*}
[[ "$identity" == *"name='de.ricci.buildcontrol'"* ]]
[[ "$identity" == *"versionCode='$expected'"* ]]
[[ "$identity" == *"versionName='1.0.$expected'"* ]]
printf '%s\n' "Verified package de.ricci.buildcontrol, version 1.0.$expected, code $expected."
