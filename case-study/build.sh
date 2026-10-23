#!/usr/bin/env sh
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

# Builds the backend jar the case-study image wraps (see docker/Dockerfile). Needs JDK 25 on
# JAVA_HOME or PATH. Tests and static checks are skipped: `mvn verify` is the place for those.
set -eu
cd "$(dirname "$0")/.."
mvn -q -B -T 1C package -pl repsy-backend -am -DskipTests \
  -Dfmt.skip -Dcheckstyle.skip -Drat.skip -Dspotbugs.skip -Djacoco.skip
ls -l repsy-backend/target/repsy-backend.jar
