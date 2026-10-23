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

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * {@link StorageCache} on Redis through one shared Lettuce connection, with a short command timeout
 * so a Redis that stops answering costs a request {@code commandTimeout}, not a hang. The
 * connection is opened lazily and reopened after a failure, so Repsy starts and serves without
 * Redis (fail-open, ADR-005).
 */
public final class RedisStorageCache implements StorageCache, AutoCloseable {

  private static final int SCAN_BATCH = 500;

  private final RedisClient client;
  private final Duration ttl;
  private volatile @Nullable StatefulRedisConnection<String, byte[]> connection;

  public RedisStorageCache(final String url, final Duration ttl, final Duration commandTimeout) {
    final RedisURI uri = RedisURI.create(url);
    uri.setTimeout(commandTimeout);
    this.client = RedisClient.create(uri);
    this.ttl = ttl;
  }

  @Override
  public byte @Nullable [] get(final String key) {
    return this.commands().get(key);
  }

  @Override
  public void put(final String key, final byte[] value) {
    this.commands().set(key, value, SetArgs.Builder.px(this.ttl.toMillis()));
  }

  @Override
  public void evictTree(final String key) {
    final RedisCommands<String, byte[]> commands = this.commands();
    commands.del(key);

    final ScanArgs match = ScanArgs.Builder.matches(escapeGlob(key) + "/*").limit(SCAN_BATCH);
    ScanCursor cursor = ScanCursor.INITIAL;
    do {
      final var page = commands.scan(cursor, match);
      final List<String> keys = page.getKeys();
      if (!keys.isEmpty()) {
        commands.del(keys.toArray(String[]::new));
      }
      cursor = page;
    } while (!cursor.isFinished());
  }

  @Override
  public void close() {
    final var current = this.connection;
    if (current != null) {
      current.close();
    }
    this.client.shutdown();
  }

  private RedisCommands<String, byte[]> commands() {
    var current = this.connection;
    if (current == null || !current.isOpen()) {
      synchronized (this) {
        current = this.connection;
        if (current == null || !current.isOpen()) {
          current = this.client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
          this.connection = current;
        }
      }
    }
    return current.sync();
  }

  private static String escapeGlob(final String value) {
    return value.replaceAll("([*?\\[\\]\\\\])", "\\\\$1");
  }
}
