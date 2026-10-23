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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/** An in-memory {@link StorageCache} for tests, which can be made to fail like a Redis outage. */
class MapStorageCache implements StorageCache {

  final Map<String, byte[]> entries = new ConcurrentHashMap<>();
  volatile boolean down;

  @Override
  public byte @Nullable [] get(final String key) {
    this.checkUp();
    return this.entries.get(key);
  }

  @Override
  public void put(final String key, final byte[] value) {
    this.checkUp();
    this.entries.put(key, value);
  }

  @Override
  public void evictTree(final String key) {
    this.checkUp();
    this.entries.keySet().removeIf(k -> k.equals(key) || k.startsWith(key + "/"));
  }

  private void checkUp() {
    if (this.down) {
      throw new IllegalStateException("cache is down");
    }
  }
}
