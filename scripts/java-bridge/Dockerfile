# SPDX-License-Identifier: MIT OR Apache-2.0
ARG BASE_IMAGE
FROM ${BASE_IMAGE}
ARG BRIDGE_TARGET
COPY packaging/java-bridge-jdks.json /opt/ironwood-bridge-preparation/packaging/java-bridge-jdks.json
COPY scripts/prepare-java-bridge.py /opt/ironwood-bridge-preparation/scripts/prepare-java-bridge.py
RUN python /opt/ironwood-bridge-preparation/scripts/prepare-java-bridge.py \
      --setup --target "$BRIDGE_TARGET" --prefix /opt/ironwood-bridge-jdk \
      --cache /tmp/ironwood-bridge-downloads --evidence /opt/ironwood-bridge-jdk/preflight.json \
    && rm -rf /tmp/ironwood-bridge-downloads
ENV JAVA_HOME=/opt/ironwood-bridge-jdk/jdk-21.0.12.1+1
ENV PATH=/opt/ironwood-bridge-jdk/jdk-21.0.12.1+1/bin:$PATH
