# The sandbox IDE's environment for UI scenarios on a virtual screen, the same on Windows, macOS, Linux and CI.
# The image holds the tools and the sandbox IDE; the plugins come from the host (build/sandbox-prebuilt), or run mode
# builds the checkout mounted at /src (read-only), see sandbox.py.
FROM eclipse-temurin:25-jdk

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      xvfb rsync socat procps fontconfig fonts-dejavu-core curl ca-certificates \
      libxext6 libxrender1 libxtst6 libxi6 libxrandr2 libfreetype6 \
 && rm -rf /var/lib/apt/lists/*

# JetBrains' open-source IntelliJ IDEA build, which has no licensing (the unified distribution asks for a subscription
# on CI runners). Version and checksum come from `marginalis.latestIde` in gradle.properties, which the Plugin Verifier
# reads too; both harnesses pass them as build arguments. Before the entrypoint, whose changes then keep this layer
# cached.
ARG SANDBOX_IDE
ARG SANDBOX_IDE_SHA256
RUN test -n "$SANDBOX_IDE" && test -n "$SANDBOX_IDE_SHA256" \
      || { echo "SANDBOX_IDE and SANDBOX_IDE_SHA256 are build arguments, from marginalis.latestIde in gradle.properties"; exit 1; } \
 && curl -fsSL -o /tmp/idea.tar.gz \
      "https://github.com/JetBrains/intellij-community/releases/download/idea/$SANDBOX_IDE/idea-$SANDBOX_IDE.tar.gz" \
 && echo "$SANDBOX_IDE_SHA256  /tmp/idea.tar.gz" | sha256sum -c - \
 && mkdir -p /opt/idea \
 && tar xzf /tmp/idea.tar.gz --strip-components=1 -C /opt/idea \
 && rm /tmp/idea.tar.gz

COPY docker-entrypoint.sh /usr/local/bin/marginalis-sandbox
# A Windows checkout may carry CRLF line endings, which bash refuses.
RUN sed -i 's/\r$//' /usr/local/bin/marginalis-sandbox

ENTRYPOINT ["bash", "/usr/local/bin/marginalis-sandbox"]
