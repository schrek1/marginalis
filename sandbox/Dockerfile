# The sandbox IDE's environment for UI scenarios on a virtual screen, the same on Windows, macOS, Linux and CI.
# Only tools live in the image; every start mounts the checkout at /src (read-only), see sandbox.py.
FROM eclipse-temurin:25-jdk

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      xvfb rsync socat procps fontconfig fonts-dejavu-core \
      libxext6 libxrender1 libxtst6 libxi6 libxrandr2 libfreetype6 \
 && rm -rf /var/lib/apt/lists/*

COPY docker-entrypoint.sh /usr/local/bin/marginalis-sandbox
# A Windows checkout may carry CRLF line endings, which bash refuses.
RUN sed -i 's/\r$//' /usr/local/bin/marginalis-sandbox

ENTRYPOINT ["bash", "/usr/local/bin/marginalis-sandbox"]
