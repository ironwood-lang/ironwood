# SPDX-License-Identifier: MIT OR Apache-2.0
ARG DEVELOPMENT_IMAGE
FROM ${DEVELOPMENT_IMAGE} AS collect
COPY collect.py /tmp/collect.py
RUN python /tmp/collect.py
FROM scratch
COPY --from=collect /minimal/ /
ENV JAVA_HOME=/opt/jdk
ENV PATH=/opt/jdk/bin
ENTRYPOINT ["/opt/jdk/bin/java"]
