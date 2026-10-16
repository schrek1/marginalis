#!/usr/bin/env bash
# Runs the sandbox IDE on a virtual 2560x1440 screen, so UI scenarios see the same window size on any machine:
# a Linux host or CI directly, any OS through the sandbox's Docker image (Dockerfile, docker-entrypoint.sh).
# Needs Xvfb and a JDK 25 (the build's toolchain); MARGINALIS_SANDBOX_RUNTIME and MARGINALIS_ROBOT_PORT pass through to sandbox/init.gradle.
set -euo pipefail

display="${MARGINALIS_DISPLAY:-99}"
pkill -f "Xvfb :$display " || true
Xvfb ":$display" -screen 0 2560x1440x24 -nolisten tcp >/dev/null 2>&1 &
xvfb=$!
trap 'kill "$xvfb" 2>/dev/null || true' EXIT

export DISPLAY=":$display"
cd "$(dirname "$0")/.."
./gradlew --no-daemon --init-script sandbox/init.gradle runIdeForUiTests "$@"
