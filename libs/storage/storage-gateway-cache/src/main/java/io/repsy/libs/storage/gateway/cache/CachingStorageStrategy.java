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

import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StaleFile;
import io.repsy.libs.storage.core.dtos.StorageItemInfo;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.dtos.TrashCleanupResult;
import io.repsy.libs.storage.core.exceptions.IsADirectoryException;
import io.repsy.libs.storage.core.services.StorageStrategy;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

/**
 * Caches the small metadata files a client reads on every install (the npm packument {@code
 * package.json}, Maven's {@code maven-metadata.xml}) in front of any {@link StorageStrategy}
 * (ADR-005, case study stage 8). Cache-aside: a read that misses loads the file from the storage
 * and caches it; every write, append, rename or delete through this strategy drops the keys it
 * touches after the storage call, whether that call succeeded or not.
 *
 * <p>Only files named in {@code cachedFileNames} and no larger than {@code maxBytes} are cached; a
 * missing file is not (a publish right after a 404 must be seen). A failing cache is a miss, never
 * a failed request (fail-open): the read goes to the storage and the failure is counted.
 *
 * <p>Consistency: a reader that loaded the old bytes before a concurrent write and caches them
 * after the write's eviction keeps them until the time to live expires. The time to live bounds how
 * stale a read can be; the case study accepts that for metadata (an npm or Maven client retries).
 */
@Slf4j
public class CachingStorageStrategy implements StorageStrategy {

  /** A cached value is the file's last-modified time (8 bytes) followed by its content. */
  private static final int TIME_BYTES = Long.BYTES;

  /** Cache failures between two log lines. */
  private static final int LOG_EVERY = 100;

  private final StorageStrategy delegate;
  private final StorageCache cache;
  private final String namespace;
  private final Set<String> cachedFileNames;
  private final long maxBytes;

  private final AtomicLong hits = new AtomicLong();
  private final AtomicLong misses = new AtomicLong();
  private final AtomicLong errors = new AtomicLong();

  public CachingStorageStrategy(
      final StorageStrategy delegate,
      final StorageCache cache,
      final String namespace,
      final Set<String> cachedFileNames,
      final long maxBytes) {
    this.delegate = delegate;
    this.cache = cache;
    this.namespace = namespace;
    this.cachedFileNames = Set.copyOf(cachedFileNames);
    this.maxBytes = maxBytes;
  }

  /** Reads answered from the cache. */
  public long hits() {
    return this.hits.get();
  }

  /** Reads of a cacheable file that went to the storage. */
  public long misses() {
    return this.misses.get();
  }

  /** Cache calls that failed and were answered by the storage instead. */
  public long errors() {
    return this.errors.get();
  }

  @Override
  @SneakyThrows
  public Optional<Resource> get(final StoragePath path, final String repoName)
      throws IsADirectoryException {
    final String fileName = path.getRelativePath().getFileName();

    if (!this.cachedFileNames.contains(fileName)) {
      return this.delegate.get(path, repoName);
    }

    final String key = this.key(path);
    final byte @Nullable [] cached = this.cacheGet(key);

    if (cached != null && cached.length >= TIME_BYTES) {
      this.hits.incrementAndGet();
      return Optional.of(toResource(cached, fileName));
    }

    this.misses.incrementAndGet();
    final Optional<Resource> stored = this.delegate.get(path, repoName);

    if (stored.isEmpty() || stored.get().contentLength() > this.maxBytes) {
      return stored;
    }

    final byte[] content;
    try (InputStream in = stored.get().getInputStream()) {
      content = in.readAllBytes();
    }

    final byte[] value =
        ByteBuffer.allocate(TIME_BYTES + content.length)
            .putLong(stored.get().lastModified())
            .put(content)
            .array();
    this.cachePut(key, value);
    return Optional.of(toResource(value, fileName));
  }

  @Override
  public BaseUsages write(
      final String repoName, final StoragePath storagePath, final InputStream inputStream) {
    return this.evictingAfter(
        storagePath, () -> this.delegate.write(repoName, storagePath, inputStream));
  }

  @Override
  public BaseUsages append(
      final String repoName, final StoragePath storagePath, final byte[] data) {
    return this.evictingAfter(storagePath, () -> this.delegate.append(repoName, storagePath, data));
  }

  @Override
  public BaseUsages appendStream(
      final String repoName, final StoragePath storagePath, final InputStream inputStream) {
    return this.evictingAfter(
        storagePath, () -> this.delegate.appendStream(repoName, storagePath, inputStream));
  }

  @Override
  public BaseUsages renameObject(final StoragePath storagePath, final String digest) {
    return this.evictingAfter(storagePath, () -> this.delegate.renameObject(storagePath, digest));
  }

  @Override
  public void delete(final StoragePath storagePath) {
    this.evictingAfter(
        storagePath,
        () -> {
          this.delegate.delete(storagePath);
          return BaseUsages.ofDisk(0);
        });
  }

  @Override
  public void createDirectory(final String name) throws IsADirectoryException {
    this.delegate.createDirectory(name);
  }

  @Override
  public CompletableFuture<TrashCleanupResult> clearTrash() {
    return this.delegate.clearTrash();
  }

  @Override
  public long calculatePathUsage(final StoragePath paths) {
    return this.delegate.calculatePathUsage(paths);
  }

  @Override
  public long getFileUsage(final StoragePath storagePath, final String repoName)
      throws IOException {
    return this.delegate.getFileUsage(storagePath, repoName);
  }

  @Override
  public List<StaleFile> listStaleFiles(
      final StoragePath directory, final Instant notModifiedSince) {
    return this.delegate.listStaleFiles(directory, notModifiedSince);
  }

  @Override
  public List<StorageItemInfo> listDirectoryContents(final StoragePath storagePath) {
    return this.delegate.listDirectoryContents(storagePath);
  }

  @Override
  public List<StorageItemInfo> listStorageItems(final StoragePath storagePath) {
    return this.delegate.listStorageItems(storagePath);
  }

  @Override
  public BaseUsages getUsages(
      final StoragePath storagePath, final String repoName, final long contentLength)
      throws IOException {
    return this.delegate.getUsages(storagePath, repoName, contentLength);
  }

  private BaseUsages evictingAfter(final StoragePath path, final Supplier<BaseUsages> call) {
    try {
      return call.get();
    } finally {
      this.evict(path);
    }
  }

  private String key(final StoragePath path) {
    return this.namespace + ":" + path.getPath();
  }

  private byte @Nullable [] cacheGet(final String key) {
    try {
      return this.cache.get(key);
    } catch (final RuntimeException e) {
      this.cacheFailed("read", key, e);
      return null;
    }
  }

  private void cachePut(final String key, final byte[] value) {
    try {
      this.cache.put(key, value);
    } catch (final RuntimeException e) {
      this.cacheFailed("fill", key, e);
    }
  }

  private void evict(final StoragePath path) {
    final String key = this.key(path);
    try {
      this.cache.evictTree(key);
    } catch (final RuntimeException e) {
      // The entry lives until its time to live expires: a read may be stale until then.
      this.cacheFailed("evict", key, e);
    }
  }

  private void cacheFailed(final String action, final String key, final RuntimeException e) {
    final long count = this.errors.incrementAndGet();
    // One line per hundred failures: a cache that is down must not flood the log.
    if (count % LOG_EVERY == 1) {
      log.warn(
          "Storage cache {} of {} failed ({} failures so far): {}",
          action,
          key,
          count,
          e.toString());
    }
  }

  private static Resource toResource(final byte[] value, final String fileName) {
    final long lastModified = ByteBuffer.wrap(value, 0, TIME_BYTES).getLong();
    final byte[] content = new byte[value.length - TIME_BYTES];
    System.arraycopy(value, TIME_BYTES, content, 0, content.length);
    return new ByteArrayResource(content, fileName) {
      @Override
      public String getFilename() {
        return fileName;
      }

      @Override
      public long lastModified() {
        return lastModified;
      }
    };
  }
}
