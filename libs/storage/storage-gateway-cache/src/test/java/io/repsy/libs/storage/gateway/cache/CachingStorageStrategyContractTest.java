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
package io.repsy.libs.storage.gateway.cache;

import io.repsy.libs.storage.core.contract.AbstractStorageStrategyContractTest;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.libs.storage.gateway.filesystem.services.FileSystemStorageStrategy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

/** The cache must not change what the strategy it wraps does: the shared contract still holds. */
@DisplayName("CachingStorageStrategy around the file system keeps the StorageStrategy contract")
class CachingStorageStrategyContractTest extends AbstractStorageStrategyContractTest {

  @TempDir Path tempDir;

  private final MapStorageCache cache = new MapStorageCache();

  @Override
  protected StorageStrategy createStrategy(final Duration trashRetention) {
    final var fs =
        new FileSystemStorageStrategy(
            this.tempDir.resolve("storage").toString(),
            this.tempDir.resolve("trash").toString(),
            trashRetention);
    // Every name the contract writes is cached, so every read and write goes through the cache.
    return new CachingStorageStrategy(
        fs,
        this.cache,
        "t",
        Set.of("f", "a.jar", "a-1.0.jar", "big.bin", "uuid", "sha256:abc", "maven-metadata.xml"),
        1 << 30);
  }
}
