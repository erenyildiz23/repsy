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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DisplayName("RedisStorageCache")
class RedisStorageCacheIT {

  static final String IMAGE = RedisImage.PINNED;

  @Container
  static final GenericContainer<?> REDIS = new GenericContainer<>(IMAGE).withExposedPorts(6379);

  static RedisStorageCache cache;

  @BeforeAll
  static void connect() {
    cache =
        new RedisStorageCache(
            "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379),
            Duration.ofMillis(800),
            Duration.ofMillis(500));
  }

  @AfterAll
  static void close() {
    cache.close();
  }

  private static byte[] b(final String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("a value is read back until its time to live expires")
  void putGetTtl() throws InterruptedException {
    cache.put("npm:r/pkg/package.json", b("v1"));

    assertThat(cache.get("npm:r/pkg/package.json")).isEqualTo(b("v1"));
    Thread.sleep(1200);
    assertThat(cache.get("npm:r/pkg/package.json")).isNull();
  }

  @Test
  @DisplayName("evicting a tree drops the key and every key under it, and nothing beside it")
  void evictTree() {
    cache.put("npm:r/pkg", b("a"));
    cache.put("npm:r/pkg/package.json", b("b"));
    cache.put("npm:r/pkg/sub/x", b("c"));
    cache.put("npm:r/pkg2/package.json", b("d"));

    cache.evictTree("npm:r/pkg");

    assertThat(cache.get("npm:r/pkg")).isNull();
    assertThat(cache.get("npm:r/pkg/package.json")).isNull();
    assertThat(cache.get("npm:r/pkg/sub/x")).isNull();
    assertThat(cache.get("npm:r/pkg2/package.json")).isEqualTo(b("d"));
  }

  @Test
  @DisplayName("a Redis that is gone fails the call within the command timeout")
  void downFailsFast() {
    final var unreachable =
        new RedisStorageCache("redis://127.0.0.1:1", Duration.ofSeconds(1), Duration.ofMillis(300));

    final long start = System.nanoTime();
    assertThatThrownBy(() -> unreachable.get("k")).isInstanceOf(RuntimeException.class);
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(15));
    unreachable.close();
  }
}
