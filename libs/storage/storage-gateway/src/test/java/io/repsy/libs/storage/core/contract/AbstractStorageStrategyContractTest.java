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
package io.repsy.libs.storage.core.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.storage.core.dtos.StorageItemInfo;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.exceptions.InvalidStoragePathException;
import io.repsy.libs.storage.core.exceptions.IsADirectoryException;
import io.repsy.libs.storage.core.services.StorageStrategy;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The behaviour every {@link StorageStrategy} shares, as the callers in the protocol modules rely
 * on it (ADR-001). A strategy's test extends this class and supplies a fresh, empty strategy; the
 * same cases then run against the file system and the object store.
 */
public abstract class AbstractStorageStrategyContractTest {

  /**
   * A body larger than one multipart part of the S3 strategy (8 MiB), so both paths are covered.
   */
  protected static final int LARGE = 9 * 1024 * 1024;

  protected StorageStrategy strategy;
  protected UUID repo;

  /**
   * A strategy over this test's storage (empty when the test starts; a second call in the same test
   * sees what the first one wrote), whose trash keeps deleted items for {@code retention}.
   */
  protected abstract StorageStrategy createStrategy(Duration trashRetention);

  @BeforeEach
  void setUpStrategy() {
    this.strategy = this.createStrategy(Duration.ofDays(7));
    this.repo = UUID.randomUUID();
  }

  protected StoragePath path(final String relativePath) {
    return StoragePath.of(this.repo, relativePath);
  }

  protected byte[] read(final StoragePath path) throws IOException {
    try (InputStream in = this.strategy.get(path, "repo").orElseThrow().getInputStream()) {
      return in.readAllBytes();
    }
  }

  protected static byte[] bytes(final int size) {
    final var data = new byte[size];
    new Random(size).nextBytes(data);
    return data;
  }

  /** The failure of the client's stream, as thrown or as the root of what wraps it. */
  private static void isClientFailure(final Throwable thrown) {
    Throwable root = thrown;
    while (root.getCause() != null) {
      root = root.getCause();
    }
    assertThat(root).hasMessage("client went away");
  }

  private static InputStream failingAfter(final byte[] data, final int failAt) {
    return new FilterInputStream(new ByteArrayInputStream(data)) {
      private int read;

      @Override
      public int read() throws IOException {
        this.check(1);
        return super.read();
      }

      @Override
      public int read(final byte[] b, final int off, final int len) throws IOException {
        this.check(len);
        final int n = super.read(b, off, Math.min(len, failAt - this.read));
        this.read += Math.max(n, 0);
        return n;
      }

      private void check(final int len) throws IOException {
        if (this.read >= failAt) {
          throw new IOException("client went away");
        }
      }
    };
  }

  @Test
  @DisplayName("a written object reads back byte for byte and is charged its size")
  void writeThenGet() throws IOException {
    final var data = bytes(1024);

    final var usage =
        this.strategy.write("repo", this.path("g/a/1.0/a-1.0.jar"), new ByteArrayInputStream(data));

    assertThat(usage.getDiskUsage()).isEqualTo(1024);
    assertThat(this.read(this.path("g/a/1.0/a-1.0.jar"))).isEqualTo(data);
    assertThat(
            this.strategy.get(this.path("g/a/1.0/a-1.0.jar"), "repo").orElseThrow().contentLength())
        .isEqualTo(1024);
  }

  @Test
  @DisplayName("a body larger than one part reads back byte for byte")
  void writeLarge() throws IOException {
    final var data = bytes(LARGE);

    final var usage =
        this.strategy.write("repo", this.path("big.bin"), new ByteArrayInputStream(data));

    assertThat(usage.getDiskUsage()).isEqualTo(LARGE);
    assertThat(this.read(this.path("big.bin"))).isEqualTo(data);
  }

  @Test
  @DisplayName("an overwrite is charged the difference of the sizes")
  void overwriteChargesDifference() throws IOException {
    this.strategy.write("repo", this.path("f"), new ByteArrayInputStream(bytes(100)));

    final var usage =
        this.strategy.write("repo", this.path("f"), new ByteArrayInputStream(bytes(30)));

    assertThat(usage.getDiskUsage()).isEqualTo(-70);
    assertThat(this.read(this.path("f"))).isEqualTo(bytes(30));
  }

  @Test
  @DisplayName("a write whose stream fails part-way leaves the previous content untouched")
  void failedWriteKeepsPrevious() throws IOException {
    final var previous = "previous".getBytes(StandardCharsets.UTF_8);
    this.strategy.write("repo", this.path("f"), new ByteArrayInputStream(previous));

    for (final int size : new int[] {1000, LARGE}) {
      final var stream = failingAfter(bytes(size), size / 2);

      assertThatThrownBy(() -> this.strategy.write("repo", this.path("f"), stream))
          .satisfies(AbstractStorageStrategyContractTest::isClientFailure);
      assertThat(this.read(this.path("f"))).isEqualTo(previous);
    }
  }

  @Test
  @DisplayName("a write whose stream fails part-way leaves nothing behind when there was nothing")
  void failedWriteLeavesNothing() {
    final var stream = failingAfter(bytes(LARGE), LARGE / 2);

    assertThatThrownBy(() -> this.strategy.write("repo", this.path("dir/f"), stream))
        .satisfies(AbstractStorageStrategyContractTest::isClientFailure);

    assertThat(this.strategy.get(this.path("dir/f"), "repo")).isEmpty();
    assertThat(this.strategy.calculatePathUsage(this.path(""))).isZero();
  }

  @Test
  @DisplayName("a missing object is empty and a directory is refused")
  void getMissingAndDirectory() {
    this.strategy.write("repo", this.path("dir/f"), new ByteArrayInputStream(bytes(1)));

    assertThat(this.strategy.get(this.path("nope"), "repo")).isEmpty();
    assertThatThrownBy(() -> this.strategy.get(this.path("dir"), "repo"))
        .isInstanceOf(IsADirectoryException.class);
  }

  @Test
  @DisplayName("appended chunks add up to the object and to its usage")
  void appendChunks() throws IOException {
    final var first = bytes(10);
    final var second = bytes(LARGE);

    final var u1 = this.strategy.append("repo", this.path("up/uuid"), first);
    final var u2 =
        this.strategy.appendStream("repo", this.path("up/uuid"), new ByteArrayInputStream(second));

    assertThat(u1.getDiskUsage()).isEqualTo(10);
    assertThat(u2.getDiskUsage()).isEqualTo(LARGE);
    final var all = new byte[10 + LARGE];
    System.arraycopy(first, 0, all, 0, 10);
    System.arraycopy(second, 0, all, 10, LARGE);
    assertThat(this.read(this.path("up/uuid"))).isEqualTo(all);
  }

  @Test
  @DisplayName("an append whose stream fails part-way leaves the object as it was")
  void failedAppendKeepsObject() throws IOException {
    this.strategy.append("repo", this.path("up/uuid"), bytes(10));

    assertThatThrownBy(
            () ->
                this.strategy.appendStream(
                    "repo", this.path("up/uuid"), failingAfter(bytes(1000), 500)))
        .satisfies(AbstractStorageStrategyContractTest::isClientFailure);

    assertThat(this.read(this.path("up/uuid"))).isEqualTo(bytes(10));
  }

  @Test
  @DisplayName("a rename moves the object to its digest, and drops it when the digest exists")
  void renameObject() throws IOException {
    this.strategy.write("repo", this.path("blobs/u1"), new ByteArrayInputStream(bytes(7)));
    this.strategy.write("repo", this.path("blobs/u2"), new ByteArrayInputStream(bytes(7)));

    assertThat(this.strategy.renameObject(this.path("blobs/u1"), "sha256:abc").getDiskUsage())
        .isZero();
    assertThat(this.strategy.renameObject(this.path("blobs/u2"), "sha256:abc").getDiskUsage())
        .isEqualTo(-7);

    assertThat(this.read(this.path("blobs/sha256:abc"))).isEqualTo(bytes(7));
    assertThat(this.strategy.get(this.path("blobs/u1"), "repo")).isEmpty();
    assertThat(this.strategy.get(this.path("blobs/u2"), "repo")).isEmpty();
    assertThatThrownBy(() -> this.strategy.renameObject(this.path("blobs/sha256:abc"), "../x"))
        .isInstanceOf(InvalidStoragePathException.class);
  }

  @Test
  @DisplayName("a deleted object or tree is gone, and deleting it again does nothing")
  void deleteIsSoftAndIdempotent() {
    this.strategy.write("repo", this.path("a/1.0/a.jar"), new ByteArrayInputStream(bytes(5)));
    this.strategy.write("repo", this.path("a/1.0/a.pom"), new ByteArrayInputStream(bytes(6)));
    this.strategy.write("repo", this.path("b.txt"), new ByteArrayInputStream(bytes(7)));

    this.strategy.delete(this.path("a"));
    this.strategy.delete(this.path("b.txt"));
    this.strategy.delete(this.path("b.txt"));

    assertThat(this.strategy.get(this.path("a/1.0/a.jar"), "repo")).isEmpty();
    assertThat(this.strategy.get(this.path("b.txt"), "repo")).isEmpty();
    assertThat(this.strategy.calculatePathUsage(this.path(""))).isZero();
  }

  @Test
  @DisplayName("the trash keeps deleted items until the retention elapsed, then removes them")
  void clearTrash() throws Exception {
    this.strategy.write("repo", this.path("a/1.0/a.jar"), new ByteArrayInputStream(bytes(5)));
    this.strategy.delete(this.path("a"));

    assertThat(this.strategy.clearTrash().get().filesDeleted()).isZero();

    final var expiring = this.createStrategy(Duration.ZERO);
    final var result = expiring.clearTrash().get();

    assertThat(result.filesDeleted()).isEqualTo(1);
    assertThat(result.bytesFreed()).isEqualTo(5);
    assertThat(result.directoriesDeleted()).isPositive();
    assertThat(expiring.clearTrash().get().filesDeleted()).isZero();
  }

  @Test
  @DisplayName("a directory lists its subdirectories first, then its files with their sizes")
  void listDirectoryContents() {
    this.strategy.write("repo", this.path("g/a/1.0/a.jar"), new ByteArrayInputStream(bytes(3)));
    this.strategy.write(
        "repo", this.path("g/a/maven-metadata.xml"), new ByteArrayInputStream(bytes(4)));
    this.strategy.write("repo", this.path("g/a/0.9/a.jar"), new ByteArrayInputStream(bytes(2)));

    final var items = this.strategy.listDirectoryContents(this.path("g/a"));

    assertThat(items)
        .extracting(StorageItemInfo::getName)
        .containsExactly("0.9/", "1.0/", "maven-metadata.xml");
    assertThat(items).extracting(StorageItemInfo::isDirectory).containsExactly(true, true, false);
    assertThat(items.get(2).getSize()).isEqualTo(4);
    assertThatThrownBy(() -> this.strategy.listDirectoryContents(this.path("missing")))
        .isInstanceOf(ItemNotFoundException.class);
  }

  @Test
  @DisplayName("a created directory lists empty")
  void createdDirectoryListsEmpty() {
    this.strategy.createDirectory(this.repo + "/empty");

    assertThat(this.strategy.listDirectoryContents(this.path("empty"))).isEmpty();
  }

  @Test
  @DisplayName("the items of a tree in a repo carry paths that end with their storage path")
  void listStorageItems() {
    this.strategy.write("repo", this.path("g/a/1.0/a-1.0.jar"), new ByteArrayInputStream(bytes(3)));
    this.strategy.write("repo", this.path("g/a/1.0/a-1.0.pom"), new ByteArrayInputStream(bytes(3)));

    final var items = this.strategy.listStorageItems(this.path("g/a/1.0"));

    assertThat(items.stream().filter(i -> !i.isDirectory()).map(StorageItemInfo::getName))
        .containsExactlyInAnyOrder("a-1.0.jar", "a-1.0.pom");
    assertThat(items.stream().filter(i -> !i.isDirectory()))
        .allSatisfy(i -> assertThat(i.getPath()).endsWith("/g/a/1.0/" + i.getName()));
    assertThat(items).anyMatch(StorageItemInfo::isDirectory);
  }

  @Test
  @DisplayName("stale files are the files directly in a directory written before the instant")
  void listStaleFiles() {
    this.strategy.write("repo", this.path("uploads/u1"), new ByteArrayInputStream(bytes(3)));
    this.strategy.write("repo", this.path("uploads/sub/u2"), new ByteArrayInputStream(bytes(3)));

    assertThat(this.strategy.listStaleFiles(this.path("uploads"), Instant.now().plusSeconds(60)))
        .extracting(f -> f.name() + ":" + f.size())
        .containsExactly("u1:3");
    assertThat(this.strategy.listStaleFiles(this.path("uploads"), Instant.now().minusSeconds(3600)))
        .isEmpty();
    assertThat(this.strategy.listStaleFiles(this.path("missing"), Instant.now())).isEmpty();
  }

  @Test
  @DisplayName("the usage of a file or a tree is the sum of its file sizes")
  void usages() throws IOException {
    this.strategy.write("repo", this.path("t/a"), new ByteArrayInputStream(bytes(10)));
    this.strategy.write("repo", this.path("t/b/c"), new ByteArrayInputStream(bytes(20)));

    assertThat(this.strategy.calculatePathUsage(this.path("t"))).isEqualTo(30);
    assertThat(this.strategy.calculatePathUsage(this.path("t/a"))).isEqualTo(10);
    assertThat(this.strategy.calculatePathUsage(this.path("none"))).isZero();
    assertThat(this.strategy.getFileUsage(this.path("t/a"), "repo")).isEqualTo(10);
    assertThat(this.strategy.getFileUsage(this.path("none"), "repo")).isZero();
    assertThat(this.strategy.getUsages(this.path("t/a"), "repo", 25).getDiskUsage()).isEqualTo(15);
  }
}
