# Copyright 2026 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Case-study image: the runtime stage of the root Dockerfile, around a jar built on the host
# (`case-study/build.sh`). The root Dockerfile downloads Maven and npm dependencies inside the build,
# which this environment's egress proxy does not allow; the panel SPA is left out because the case
# study only drives the APIs.
FROM eclipse-temurin:25-jre-alpine@sha256:b7528962e65d43064d8a8b67ba175367896bc948363ea2d3fcd7f10d34e1f048
WORKDIR /app
RUN addgroup -S appgroup && adduser -S appuser -G appgroup && \
    mkdir -p /app/data/password-reset /app/data/storage /app/certs && \
    chown -R appuser:appgroup /app/data /app/certs
COPY entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh
USER appuser
COPY repsy-backend/target/repsy-backend.jar app.jar
ENV DB_URL="jdbc:h2:file:/app/data/repsy;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
ENV PASSWORD_RESET_MARKER_DIR="/app/data/password-reset"
ENV STORAGE_BASE_PATH="/app/data/storage"
VOLUME /app/data
EXPOSE 8080 9090
ENTRYPOINT ["/app/entrypoint.sh"]
