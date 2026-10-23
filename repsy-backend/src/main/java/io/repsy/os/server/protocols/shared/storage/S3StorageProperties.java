/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.repsy.os.server.protocols.shared.storage;

import java.time.Duration;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The object store that holds the artifacts when {@code os.app.storage.backend} is {@code s3}
 * (ADR-001). Bound from {@code os.app.storage.s3} by {@link StorageStrategyBeanRegistrar}; every
 * protocol keeps its objects under its own prefix ({@code maven/}, {@code npm/}...) and its trash
 * under {@code trash/<type>/} of the one bucket.
 *
 * @param endpoint the store's URL; empty for AWS itself
 * @param pathStyle bucket in the path ({@code /bucket/key}), as MinIO and its forks need
 * @param socketTimeout how long a read may wait for the next byte before the request fails
 */
public record S3StorageProperties(
    @DefaultValue("") String endpoint,
    @DefaultValue("us-east-1") String region,
    @DefaultValue("repsy") String bucket,
    @DefaultValue("") String accessKey,
    @DefaultValue("") String secretKey,
    @DefaultValue("true") boolean pathStyle,
    @DefaultValue("PT5S") Duration connectTimeout,
    @DefaultValue("PT30S") Duration socketTimeout,
    @DefaultValue("64") int maxConnections) {}
