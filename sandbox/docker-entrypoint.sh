#!/usr/bin/env bash
# Container side of `sandbox.py` with MARGINALIS_SANDBOX_DOCKER=1. MARGINALIS_SANDBOX_MODE picks what it does:
#   run       (default) build the checkout and run the sandbox IDE through Gradle
#   build     build the checkout once and put its plugins into /prebuilt, then exit
#   prebuilt  run the sandbox IDE straight from /prebuilt's plugins: no Gradle, so many sandboxes start at once
set -euo pipefail
mode="${MARGINALIS_SANDBOX_MODE:-run}"

if [ ! -d /root/.gradle/caches ] && [ -d /gradle-seed/caches ]; then
  cp -a /gradle-seed/. /root/.gradle/
  find /root/.gradle -name '*.lock' -delete
fi

export MARGINALIS_SANDBOX_RUNTIME=/work/runtime MARGINALIS_ROBOT_PORT=8082
plugins=/work/runtime/ide/plugins_runIdeForUiTests
# A killed IDE leaves its instance lock and socket in the volume; the next IDE then hands its arguments to that
# dead instance and exits, or waits on its socket. No other IDE ever runs in this container.
rm -f /work/runtime/ide/config_runIdeForUiTests/.lock /work/runtime/ide/config_runIdeForUiTests/.port \
      /work/runtime/ide/system_runIdeForUiTests/.lock /work/runtime/ide/system_runIdeForUiTests/.port

# The volume outlives its container, and the next scenario that claims the port gets it: app settings a scenario
# changed and could not restore would carry over. A new container starts from the defaults; its own restarts keep them.
if [ -n "${MARGINALIS_SANDBOX_APP_CONFIG_PER_CONTAINER:-}" ]; then
  owner=/work/runtime/ide/app-config-owner
  if [ "$(cat "$owner" 2>/dev/null)" != "$HOSTNAME" ]; then
    rm -rf /work/runtime/ide/config_runIdeForUiTests/options
    mkdir -p /work/runtime/ide
    echo "$HOSTNAME" > "$owner"
  fi
fi

# The test project runs from the container's own disk: on the host folder (9p from Windows) file operations stalled
# the IDE and it got no file-change events. The host folder is the copy the harness prepares and reads back.
if [ -d /project-host ]; then
  mkdir -p /work/runtime/project
  rsync -a --delete /project-host/ /work/runtime/project/
fi

if [ "$mode" != prebuilt ]; then
  # Gradle builds off a copy: on a host bind mount it is slow, and the copy keeps its build output between starts.
  rsync -a --delete \
    --exclude /.git --exclude /build --exclude /core/build --exclude /.gradle --exclude /.kotlin \
    --exclude /.intellijPlatform --exclude /.idea --exclude /out --exclude __pycache__ \
    /src/ /work/src/
  find /work/src/gradlew /work/src/sandbox -maxdepth 1 \( -name gradlew -o -name '*.sh' \) -exec sed -i 's/\r$//' {} +
fi

if [ "$mode" = build ]; then
  cd /work/src
  ./gradlew --no-daemon -q --init-script sandbox/init.gradle prepareSandbox_runIdeForUiTests buildPlugin
  rm -rf /prebuilt/plugins && mkdir -p /prebuilt/plugins
  cp -a "$plugins"/. /prebuilt/plugins/
  # The testing sandbox adds the test dependencies to the plugin's lib; the sandbox runs what users get instead:
  # the distribution zip's content.
  version=$(sed -n 's/^version = "\([^"]*\)".*/\1/p' build.gradle.kts)
  distribution="/work/src/build/distributions/marginalis-$version.zip"
  [ -f "$distribution" ] || { echo "no distribution zip $distribution" >&2; exit 1; }
  rm -rf /prebuilt/plugins/marginalis
  (cd /prebuilt/plugins && jar xf "$distribution")
  # The IDE runIdeForUiTests downloaded (sandbox/init.gradle names its version), relative to the Gradle home.
  sandbox_ide=$(sed -n "s/^def sandboxIde = '\([^']*\)'.*/\1/p" sandbox/init.gradle)
  ide=$(grep -l "\"version\": \"$sandbox_ide\"" /root/.gradle/caches/*/transforms/*/transformed/idea*/product-info.json | head -1)
  [ -n "$ide" ] || { echo "no IntelliJ IDEA $sandbox_ide in the Gradle home" >&2; exit 1; }
  dirname "${ide#/root/.gradle/}" > /prebuilt/ide
  # Set by the harness on a Linux host, where files root writes into a bind mount stay root's.
  if [ -n "${MARGINALIS_HOST_OWNER:-}" ]; then
    chown -R "$MARGINALIS_HOST_OWNER" /prebuilt/plugins
  fi
  exit 0
fi

# The robot and REST servers listen on loopback only; published ports arrive on the container's own address.
address=$(hostname -i | awk '{print $1}')
for port in 8082 63342; do
  socat "TCP-LISTEN:$port,bind=$address,fork,reuseaddr" "TCP:127.0.0.1:$port" &
done

if [ "$mode" = run ]; then
  cd /work/src
  exec bash sandbox/run-ide-xvfb.sh
fi

# Straight from the build container's Gradle home: a sandbox's own copy of it dates from the sandbox's first start and
# may hold an older IDE only.
ide="/gradle-seed/$(cat /prebuilt/ide 2>/dev/null)"
[ -x "$ide/bin/idea.sh" ] || { echo "no sandbox IDE at $ide: run ./gradlew sandboxPrebuilt" >&2; exit 1; }
rm -rf "$plugins" && mkdir -p "$plugins" && cp -a /prebuilt/plugins/. "$plugins"/
ide_dir=/work/runtime/ide
cat > /tmp/idea.properties <<EOF
idea.config.path=$ide_dir/config_runIdeForUiTests
idea.system.path=$ide_dir/system_runIdeForUiTests
idea.plugins.path=$plugins
idea.log.path=$ide_dir/log_runIdeForUiTests
EOF
# The same properties sandbox/init.gradle gives the IDE in run mode.
{ cat "$ide/bin/idea64.vmoptions"; cat <<EOF
-Drobot-server.port=8082
-Didea.trust.all.projects=true
-Djb.privacy.policy.text=<!--999.999-->
-Djb.consents.confirmation.enabled=false
-Dide.show.tips.on.startup.default.value=false
-Didea.initially.ask.config=never
-Dide.mac.message.dialogs.as.sheets=false
EOF
} > /tmp/idea.vmoptions

display="${MARGINALIS_DISPLAY:-99}"
# A container started again keeps its /tmp; an Xvfb killed with it leaves its lock there, and the next one refuses
# the display.
rm -f "/tmp/.X$display-lock" "/tmp/.X11-unix/X$display"
Xvfb ":$display" -screen 0 2560x1440x24 -nolisten tcp >/dev/null 2>&1 &
export DISPLAY=":$display" IDEA_PROPERTIES=/tmp/idea.properties IDEA_VM_OPTIONS=/tmp/idea.vmoptions
exec "$ide/bin/idea.sh" nosplash /work/runtime/project
