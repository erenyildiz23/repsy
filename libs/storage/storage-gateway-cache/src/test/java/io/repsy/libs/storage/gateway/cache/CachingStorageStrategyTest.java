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

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.gateway.filesystem.services.FileSystemStorageStrategy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("CachingStorageStrategy")
class CachingStorageStrategyTest {

  @TempDir Path tempDir;

  private final MapStorageCache cache = new MapStorageCache();
  private CachingStorageStrategy strategy;
  private final UUID repo = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    final var fs =
        new FileSystemStorageStrategy(
            this.tempDir.resolve("s").toString(),
            this.tempDir.resolve("t").toString(),
            Duration.ofDays(7));
    this.strategy = new CachingStorageStrategy(fs, this.cache, "npm", Set.of("package.json"), 100);
  }

  private StoragePath path(final String p) {
    return StoragePath.of(this.repo, p);
  }

  private void write(final String p, final String content) {
    this.strategy.write(
        "r", this.path(p), new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
  }

  private String read(final String p) throws IOException {
    try (InputStream in = this.strategy.get(this.path(p), "r").orElseThrow().getInputStream()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  @DisplayName("a second read of a metadata file is answered from the cache")
  void secondReadHits() throws IOException {
    this.write("pkg/package.json", "v1");

    assertThat(this.read("pkg/package.json")).isEqualTo("v1");
    assertThat(this.read("pkg/package.json")).isEqualTo("v1");

    assertThat(this.strategy.misses()).isEqualTo(1);
    assertThat(this.strategy.hits()).isEqualTo(1);
  }

  @Test
  @DisplayName("a write drops the cached file, so the next read sees the new content")
  void writeInvalidates() throws IOException {
    this.write("pkg/package.json", "v1");
    this.read("pkg/package.json");

    this.write("pkg/package.json", "v2");

    assertThat(this.read("pkg/package.json")).isEqualTo("v2");
  }

  @Test
  @DisplayName("deleting a package directory drops the cached files under it")
  void deleteTreeInvalidates() throws IOException {
    this.write("pkg/package.json", "v1");
    this.read("pkg/package.json");

    this.strategy.delete(this.path("pkg"));

    assertThat(this.strategy.get(this.path("pkg/package.json"), "r")).isEmpty();
  }

  @Test
  @DisplayName("other files, large files and missing files are not cached")
  void onlySmallMetadataIsCached() throws IOException {
    this.write("pkg/pkg-1.0.0.tgz", "tarball");
    this.write("big/package.json", "x".repeat(101));

    this.read("pkg/pkg-1.0.0.tgz");
    this.read("big/package.json");
    assertThat(this.strategy.get(this.path("none/package.json"), "r")).isEmpty();

    assertThat(this.cache.entries).isEmpty();
  }

  @Test
  @DisplayName("a cache that is down is a miss: reads and writes still work (fail-open)")
  void failOpen() throws IOException {
    this.write("pkg/package.json", "v1");
    this.cache.down = true;

    this.write("pkg/package.json", "v2");

    assertThat(this.read("pkg/package.json")).isEqualTo("v2");
    assertThat(this.strategy.errors()).isPositive();
  }
}
