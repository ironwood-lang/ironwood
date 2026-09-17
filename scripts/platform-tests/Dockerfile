# SPDX-License-Identifier: MIT OR Apache-2.0
FROM ubuntu:24.04
ARG TARGETARCH
RUN apt-get update && apt-get install -y --no-install-recommends \
    bash bzip2 ca-certificates curl git build-essential \
    && rm -rf /var/lib/apt/lists/*
RUN case "$TARGETARCH" in \
      arm64) IRONWOOD_SUBDIR=linux-aarch64 ;; \
      amd64) IRONWOOD_SUBDIR=linux-64 ;; \
      *) exit 1 ;; \
    esac \
    && curl --fail --location --retry 3 \
      "https://micro.mamba.pm/api/micromamba/$IRONWOOD_SUBDIR/latest" \
      --output /tmp/micromamba.tar.bz2 \
    && tar -xjf /tmp/micromamba.tar.bz2 -C /usr/local bin/micromamba \
    && rm /tmp/micromamba.tar.bz2
COPY packaging/idk-environment.yml /tmp/idk-environment.yml
RUN case "$TARGETARCH" in \
      arm64) IRONWOOD_SYSROOT=sysroot_linux-aarch64=2.17 ;; \
      amd64) IRONWOOD_SYSROOT=sysroot_linux-64=2.17 ;; \
      *) exit 1 ;; \
    esac \
    && micromamba create --yes --prefix /opt/ironwood-toolchain \
      --file /tmp/idk-environment.yml "$IRONWOOD_SYSROOT" \
    && micromamba clean --all --yes
ENV JAVA_HOME=/opt/ironwood-toolchain/lib/jvm
ENV PATH=/opt/ironwood-toolchain/lib/jvm/bin:/opt/ironwood-toolchain/bin:$PATH
ENV IRONWOOD_LLVM_HOME=/opt/ironwood-toolchain
COPY scripts/prepare-tls.py /tmp/ironwood-tls-build/scripts/prepare-tls.py
COPY packaging/tls-dependencies.properties /tmp/ironwood-tls-build/packaging/tls-dependencies.properties
COPY LICENSES/MPL-2.0.txt /tmp/ironwood-tls-build/LICENSES/MPL-2.0.txt
RUN python /tmp/ironwood-tls-build/scripts/prepare-tls.py \
      --llvm-home /opt/ironwood-toolchain --prefix /opt/ironwood-tls --cache /tmp/ironwood-tls-downloads \
    && rm -rf /tmp/ironwood-tls-build /tmp/ironwood-tls-downloads
ENV IRONWOOD_TLS_HOME=/opt/ironwood-tls
