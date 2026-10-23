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
import java.util.Set;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * The metadata cache in front of the storage (ADR-005, case study stage 8), bound from {@code
 * os.app.storage.cache} by {@link StorageStrategyBeanRegistrar}.
 *
 * @param type {@code none} or {@code redis}
 * @param ttl how long a cached file is served without asking the storage; the bound on how stale
 *     a read can be after a write whose eviction was lost
 * @param commandTimeout how long one Redis call may take before the read goes to the storage
 * @param fileNames the file names that are cached: the npm packument and Maven's metadata
 * @param maxBytes the largest file that is cached
 */
public record StorageCacheProperties(
    @DefaultValue("none") String type,
    @DefaultValue("redis://localhost:6379") String redisUrl,
    @DefaultValue("PT5M") Duration ttl,
    @DefaultValue("PT0.2S") Duration commandTimeout,
    @DefaultValue({"package.json", "maven-metadata.xml"}) Set<String> fileNames,
    @DefaultValue("1MB") DataSize maxBytes) {}
