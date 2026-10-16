#!/usr/bin/env bash
# Container side of `sandbox.py start` with MARGINALIS_SANDBOX_DOCKER=1.
set -euo pipefail

if [ ! -d /root/.gradle/caches ] && [ -d /gradle-seed/caches ]; then
  cp -a /gradle-seed/. /root/.gradle/
  find /root/.gradle -name '*.lock' -delete
fi

# Gradle builds off a copy: on a host bind mount it is slow, and the copy keeps its build output between starts.
rsync -a --delete \
  --exclude /.git --exclude /build --exclude /core/build --exclude /.gradle --exclude /.kotlin \
  --exclude /.intellijPlatform --exclude /.idea --exclude __pycache__ \
  /src/ /work/src/
find /work/src/gradlew /work/src/sandbox -maxdepth 1 \( -name gradlew -o -name '*.sh' \) -exec sed -i 's/\r$//' {} +

# The robot and REST servers listen on loopback only; published ports arrive on the container's own address.
address=$(hostname -i | awk '{print $1}')
for port in 8082 63342; do
  socat "TCP-LISTEN:$port,bind=$address,fork,reuseaddr" "TCP:127.0.0.1:$port" &
done

export MARGINALIS_SANDBOX_RUNTIME=/work/runtime MARGINALIS_ROBOT_PORT=8082
cd /work/src
exec bash sandbox/run-ide-xvfb.sh
