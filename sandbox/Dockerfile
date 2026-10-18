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
# on CI runners). The checksum is the one GitHub lists for the release asset. Before the entrypoint, whose changes then
# keep this layer cached.
ARG SANDBOX_IDE=2026.2
ARG SANDBOX_IDE_SHA256=94a78ba2b45b022d2dd7c7ec4de7d698b47c20365b1fbbae72d25909f1eb97fd
RUN curl -fsSL -o /tmp/idea.tar.gz \
      "https://github.com/JetBrains/intellij-community/releases/download/idea/$SANDBOX_IDE/idea-$SANDBOX_IDE.tar.gz" \
 && echo "$SANDBOX_IDE_SHA256  /tmp/idea.tar.gz" | sha256sum -c - \
 && mkdir -p /opt/idea \
 && tar xzf /tmp/idea.tar.gz --strip-components=1 -C /opt/idea \
 && rm /tmp/idea.tar.gz

COPY docker-entrypoint.sh /usr/local/bin/marginalis-sandbox
# A Windows checkout may carry CRLF line endings, which bash refuses.
RUN sed -i 's/\r$//' /usr/local/bin/marginalis-sandbox

ENTRYPOINT ["bash", "/usr/local/bin/marginalis-sandbox"]
