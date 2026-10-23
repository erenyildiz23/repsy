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
package io.repsy.libs.storage.gateway.s3;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StaleFile;
import io.repsy.libs.storage.core.dtos.StorageItemInfo;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.dtos.TrashCleanupResult;
import io.repsy.libs.storage.core.exceptions.InvalidStoragePathException;
import io.repsy.libs.storage.core.exceptions.IsADirectoryException;
import io.repsy.libs.storage.core.exceptions.StorageUnavailableException;
import io.repsy.libs.storage.core.services.StorageStrategy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.file.NoSuchFileException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import lombok.SneakyThrows;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Async;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.MultipartUpload;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * A {@link StorageStrategy} on an S3 compatible object store (ADR-001). It keeps the contract of
 * the file system strategy, which every caller was written against, on a store that has no
 * directories, no rename and no append:
 *
 * <ul>
 *   <li>An object's key is {@code <prefix>/<storage path>}; a "directory" is a key prefix. {@link
 *       #createDirectory} stores a zero-byte marker ({@code <key>/}) so an empty directory can be
 *       listed, as an empty directory on disk can. Markers are never answered as files.
 *   <li>{@link #write} is all or nothing: a body up to {@link #PART_SIZE} is one {@code PutObject},
 *       a larger one a multipart upload that is completed only after the stream was read to its end
 *       and aborted on any failure, so a reader never sees part of an object.
 *   <li>{@link #appendStream} rewrites the object as its old bytes followed by the new ones, with
 *       the same all-or-nothing upload. It costs the size of the object per chunk; Docker and Helm,
 *       its only callers, usually send a blob in one chunk.
 *   <li>{@link #renameObject} is a copy and a delete. A crash between the two leaves the source
 *       behind, which is an upload file the abandoned upload cleanup removes after its TTL.
 *   <li>{@link #delete} copies the objects under {@code <trash prefix>/<day>/<instant>/<path>} and
 *       then deletes them; {@link #clearTrash} drops the days older than the retention period and
 *       aborts multipart uploads a dead JVM left behind.
 * </ul>
 *
 * <p>A failure of the store during a write is answered with {@link StorageUnavailableException}, as
 * the file system strategy answers an {@link IOException} of the disk; a failure to read the
 * client's stream is rethrown as it is.
 */
public class S3StorageStrategy implements StorageStrategy {

  /** The size of a multipart part, and the largest body sent as a single {@code PutObject}. */
  static final int PART_SIZE = 8 * 1024 * 1024;

  /** A multipart upload this old was started by a JVM that died; S3 never shows its parts. */
  private static final Duration ORPHANED_UPLOAD_AGE = Duration.ofDays(1);

  private static final String DELIMITER = "/";
  private static final int DELETE_BATCH = 1000;
  private static final int HTTP_NOT_FOUND = 404;

  private final S3Client s3;
  private final String bucket;
  private final String prefix;
  private final String trashPrefix;
  private final Duration trashRetentionPeriod;
  private final Clock clock;

  /**
   * @param prefix the key prefix of this strategy's objects, for example {@code maven}
   * @param trashPrefix the key prefix of its trash, outside {@code prefix}, for example {@code
   *     trash/maven}
   */
  public S3StorageStrategy(
      final S3Client s3,
      final String bucket,
      final String prefix,
      final String trashPrefix,
      final Duration trashRetentionPeriod,
      final Clock clock) {
    this.s3 = s3;
    this.bucket = bucket;
    this.prefix = trimSlashes(prefix);
    this.trashPrefix = trimSlashes(trashPrefix);
    this.trashRetentionPeriod = trashRetentionPeriod;
    this.clock = clock;
  }

  @Override
  public Optional<Resource> get(final StoragePath storagePath, final String repoName)
      throws IsADirectoryException {
    final String key = this.toKey(storagePath);
    final Optional<HeadObjectResponse> head = this.head(key);

    if (head.isPresent()) {
      final var h = head.get();
      return Optional.of(
          new S3ObjectResource(this.s3, this.bucket, key, h.contentLength(), h.lastModified()));
    }

    if (this.hasChildren(key)) {
      throw new IsADirectoryException();
    }

    return Optional.empty();
  }

  @Override
  public List<StaleFile> listStaleFiles(
      final StoragePath directory, final Instant notModifiedSince) {
    final String dirPrefix = this.toKey(directory) + DELIMITER;
    final var staleFiles = new ArrayList<StaleFile>();

    this.list(
        dirPrefix,
        true,
        object -> {
          if (isFile(object, dirPrefix) && object.lastModified().isBefore(notModifiedSince)) {
            staleFiles.add(new StaleFile(lastSegment(object.key()), object.size()));
          }
        },
        commonPrefix -> {});

    return staleFiles;
  }

  @Override
  public List<StorageItemInfo> listDirectoryContents(final StoragePath storagePath) {
    final String dirPrefix = this.toKey(storagePath) + DELIMITER;
    final Set<StorageItemInfo> directories = new TreeSet<>();
    final Set<StorageItemInfo> files = new TreeSet<>();
    final boolean[] found = {false};

    this.list(
        dirPrefix,
        true,
        object -> {
          found[0] = true;
          if (isFile(object, dirPrefix)) {
            files.add(
                StorageItemInfo.builder()
                    .name(lastSegment(object.key()))
                    .size(object.size())
                    .createdAt(Date.from(object.lastModified()))
                    .directory(false)
                    .build());
          }
        },
        commonPrefix -> {
          found[0] = true;
          directories.add(
              StorageItemInfo.builder()
                  .name(lastSegment(commonPrefix.prefix()) + DELIMITER)
                  .createdAt(Date.from(Instant.EPOCH))
                  .directory(true)
                  .build());
        });

    if (!found[0]) {
      throw new ItemNotFoundException("resourceNotFound");
    }

    final List<StorageItemInfo> items = new ArrayList<>(directories);
    items.addAll(files);
    return items;
  }

  /**
   * Lists the objects under {@code storagePath}: its direct entries for a path outside a repo, the
   * whole tree (the directories every key implies, the path itself included) for a path inside one.
   * An item's {@code path} is {@code /<key>}, so it ends with the storage path as the file system
   * strategy's does. A tree with nothing in it fails as the file system walk of a missing directory
   * does.
   */
  @Override
  @SneakyThrows
  public List<StorageItemInfo> listStorageItems(final StoragePath storagePath) {
    final String key = this.toKey(storagePath);
    final String dirPrefix = key + DELIMITER;
    final List<StorageItemInfo> items = new ArrayList<>();
    final boolean recursive = storagePath.getStorageKey() != null;

    if (!recursive) {
      this.list(
          dirPrefix,
          true,
          object -> {
            if (isFile(object, dirPrefix)) {
              items.add(fileItem(object));
            }
          },
          commonPrefix -> items.add(directoryItem(trimSlashes(commonPrefix.prefix()))));
      return items;
    }

    final Set<String> directories = new TreeSet<>();
    final boolean[] found = {false};

    this.list(
        dirPrefix,
        false,
        object -> {
          found[0] = true;
          addParentDirectories(object.key(), key, directories);
          if (isFile(object, dirPrefix)) {
            items.add(fileItem(object));
          }
        },
        commonPrefix -> {});

    if (!found[0]) {
      throw new NoSuchFileException(key);
    }

    directories.add(key);
    directories.forEach(directory -> items.add(directoryItem(directory)));
    return items;
  }

  @Override
  @SneakyThrows
  public BaseUsages write(
      final String repoName, final StoragePath storagePath, final InputStream inputStream) {
    final String key = this.toKey(storagePath);

    if (key.endsWith(DELIMITER) || key.equals(this.prefix)) {
      throw new InvalidStoragePathException("invalidStoragePath");
    }

    final long existingLength = this.sizeOf(key);

    try (InputStream is = inputStream) {
      return BaseUsages.ofDisk(this.upload(key, is) - existingLength);
    }
  }

  @Override
  public BaseUsages append(
      final String repoName, final StoragePath storagePath, final byte[] data) {
    return this.appendStream(repoName, storagePath, new ByteArrayInputStream(data));
  }

  @Override
  @SneakyThrows
  public BaseUsages appendStream(
      final String repoName, final StoragePath storagePath, final InputStream inputStream) {
    final String key = this.toKey(storagePath);
    final long lengthBefore = this.sizeOf(key);

    try (InputStream is = inputStream) {
      if (lengthBefore == 0) {
        return BaseUsages.ofDisk(this.upload(key, is));
      }

      try (InputStream existing = this.storageRead(key);
          InputStream combined = new SequenceInputStream(existing, is)) {
        return BaseUsages.ofDisk(this.upload(key, combined) - lengthBefore);
      }
    }
  }

  @Override
  public void createDirectory(final String name) throws IsADirectoryException {
    final String key = this.prefix + DELIMITER + trimSlashes(name) + DELIMITER;
    this.storageWrite(
        () -> this.s3.putObject(r -> r.bucket(this.bucket).key(key), RequestBody.empty()));
  }

  @Override
  public long calculatePathUsage(final StoragePath paths) {
    final String key = this.toKey(paths);
    final Optional<HeadObjectResponse> head = this.head(key);

    if (head.isPresent()) {
      return head.get().contentLength();
    }

    final String dirPrefix = key + DELIMITER;
    final long[] total = {0};
    this.list(
        dirPrefix,
        false,
        object -> {
          if (isFile(object, dirPrefix)) {
            total[0] += object.size();
          }
        },
        commonPrefix -> {});
    return total[0];
  }

  /**
   * Soft-deletes the object or the tree at {@code storagePath}: its objects are copied under {@code
   * <trash prefix>/<today>/<instant>/<path>} and only then deleted, so a failure part-way leaves a
   * copy in the trash and the original in place, never neither. Idempotent: nothing at the path
   * counts as deleted.
   */
  @Override
  public void delete(final StoragePath storagePath) {
    final String key = this.toKey(storagePath);
    final Instant now = this.clock.instant();
    final String trashBase =
        String.join(
            DELIMITER,
            this.trashPrefix,
            LocalDate.ofInstant(now, this.clock.getZone()).toString(),
            now.toString(),
            trimSlashes(storagePath.getPath()));

    final List<String> keys = new ArrayList<>();

    if (this.head(key).isPresent()) {
      keys.add(key);
    }

    this.list(key + DELIMITER, false, object -> keys.add(object.key()), commonPrefix -> {});

    for (final String source : keys) {
      final String target = trashBase + source.substring(key.length());
      this.storageWrite(
          () ->
              this.s3.copyObject(
                  r ->
                      r.sourceBucket(this.bucket)
                          .sourceKey(source)
                          .destinationBucket(this.bucket)
                          .destinationKey(target)));
    }

    this.deleteKeys(keys);
  }

  /**
   * Permanently removes the trash days older than the retention period, read back from the {@code
   * <day>} segment {@link #delete} wrote, and aborts the multipart uploads of this strategy that a
   * dead JVM left open for a day. {@code directoriesDeleted} counts the day and every key prefix
   * under it, as the file system strategy counts the directories it removes.
   */
  @Async("maintenanceTaskExecutor")
  @Override
  public CompletableFuture<TrashCleanupResult> clearTrash() {
    this.abortOrphanedUploads();

    final Instant threshold = this.clock.instant().minus(this.trashRetentionPeriod);
    final List<String> days = new ArrayList<>();
    this.list(
        this.trashPrefix + DELIMITER,
        true,
        object -> {},
        commonPrefix -> days.add(trimSlashes(commonPrefix.prefix())));

    TrashCleanupResult result = TrashCleanupResult.EMPTY;

    for (final String day : days) {
      final Optional<Instant> dayStart = this.toDayStart(lastSegment(day));

      if (dayStart.isPresent() && dayStart.get().isBefore(threshold)) {
        result = result.plus(this.deleteTree(day));
      }
    }

    return CompletableFuture.completedFuture(result);
  }

  @Override
  public BaseUsages getUsages(
      final StoragePath storagePath, final String repoName, final long contentLength) {
    return BaseUsages.ofDisk(contentLength - this.sizeOf(this.toKey(storagePath)));
  }

  @Override
  public long getFileUsage(final StoragePath storagePath, final String repoName) {
    return this.sizeOf(this.toKey(storagePath));
  }

  /**
   * Renames the object to its digest, a sibling key. The target is content-addressed, so when it
   * exists it holds the same bytes: the redundant source is dropped and its size answered as freed.
   * The digest comes from the client and has to be a plain name.
   */
  @Override
  public BaseUsages renameObject(final StoragePath storagePath, final String digest) {
    checkDigest(digest);

    final String source = this.toKey(storagePath);
    final String target = source.substring(0, source.lastIndexOf(DELIMITER) + 1) + digest;
    final long sourceSize =
        this.head(source)
            .map(HeadObjectResponse::contentLength)
            .orElseThrow(
                () ->
                    new StorageUnavailableException(
                        "Cannot rename " + source, new NoSuchFileException(source)));

    if (this.head(target).isPresent()) {
      this.deleteKeys(List.of(source));
      return BaseUsages.ofDisk(-sourceSize);
    }

    this.storageWrite(
        () ->
            this.s3.copyObject(
                r ->
                    r.sourceBucket(this.bucket)
                        .sourceKey(source)
                        .destinationBucket(this.bucket)
                        .destinationKey(target)));
    this.deleteKeys(List.of(source));
    return BaseUsages.ofDisk(0);
  }

  /** The digest comes from the client, so it has to be a plain name in the source's directory. */
  private static void checkDigest(final String digest) {
    if (digest.isEmpty()
        || digest.equals(".")
        || digest.contains("..")
        || digest.contains(DELIMITER)
        || digest.contains("\\")) {
      throw new InvalidStoragePathException("invalidStoragePath");
    }
  }

  /**
   * Uploads the stream to {@code key} and answers its length. Nothing is visible at the key until
   * the whole stream was read: a short body is one {@code PutObject}, a longer one a multipart
   * upload that is aborted when reading the stream or uploading a part fails.
   */
  private long upload(final String key, final InputStream inputStream) throws IOException {
    final byte[] first = inputStream.readNBytes(PART_SIZE);

    if (first.length < PART_SIZE) {
      this.storageWrite(
          () ->
              this.s3.putObject(r -> r.bucket(this.bucket).key(key), RequestBody.fromBytes(first)));
      return first.length;
    }

    final String uploadId =
        this.storageWrite(
            () -> this.s3.createMultipartUpload(r -> r.bucket(this.bucket).key(key)).uploadId());
    final List<CompletedPart> parts = new ArrayList<>();
    long total = 0;

    try {
      byte[] part = first;
      int partNumber = 1;

      while (part.length > 0) {
        final byte[] body = part;
        final int number = partNumber;
        final String eTag =
            this.storageWrite(
                () ->
                    this.s3
                        .uploadPart(
                            r ->
                                r.bucket(this.bucket)
                                    .key(key)
                                    .uploadId(uploadId)
                                    .partNumber(number),
                            RequestBody.fromBytes(body))
                        .eTag());
        parts.add(CompletedPart.builder().partNumber(number).eTag(eTag).build());
        total += body.length;
        partNumber++;
        part = inputStream.readNBytes(PART_SIZE);
      }

      this.storageWrite(
          () ->
              this.s3.completeMultipartUpload(
                  r ->
                      r.bucket(this.bucket)
                          .key(key)
                          .uploadId(uploadId)
                          .multipartUpload(
                              CompletedMultipartUpload.builder().parts(parts).build())));
    } catch (final IOException | RuntimeException | Error e) {
      this.abort(key, uploadId, e);
      throw e;
    }

    return total;
  }

  private void abort(final String key, final String uploadId, final Throwable cause) {
    try {
      this.s3.abortMultipartUpload(r -> r.bucket(this.bucket).key(key).uploadId(uploadId));
    } catch (final SdkException e) {
      // The upload stays invisible; clearTrash aborts it once it is a day old.
      cause.addSuppressed(e);
    }
  }

  private void abortOrphanedUploads() {
    final Instant threshold = this.clock.instant().minus(ORPHANED_UPLOAD_AGE);
    final String ownPrefix = this.prefix + DELIMITER;

    try {
      for (final MultipartUpload upload :
          this.s3
              .listMultipartUploadsPaginator(r -> r.bucket(this.bucket).prefix(ownPrefix))
              .uploads()) {
        if (upload.initiated().isBefore(threshold)) {
          this.s3.abortMultipartUpload(
              r -> r.bucket(this.bucket).key(upload.key()).uploadId(upload.uploadId()));
        }
      }
    } catch (final SdkException ignored) {
      // Best effort, as the file system strategy's removal of orphaned temp files: the next pass.
    }
  }

  private TrashCleanupResult deleteTree(final String treePrefix) {
    final List<String> keys = new ArrayList<>();
    final Set<String> directories = new HashSet<>();
    final long[] bytes = {0};
    final int[] files = {0};

    this.list(
        treePrefix + DELIMITER,
        false,
        object -> {
          keys.add(object.key());
          addParentDirectories(object.key(), treePrefix, directories);
          if (!object.key().endsWith(DELIMITER)) {
            files[0]++;
            bytes[0] += object.size();
          }
        },
        commonPrefix -> {});

    this.deleteKeys(keys);
    directories.add(treePrefix);
    return new TrashCleanupResult(directories.size(), files[0], bytes[0]);
  }

  private void deleteKeys(final List<String> keys) {
    for (int from = 0; from < keys.size(); from += DELETE_BATCH) {
      final List<ObjectIdentifier> batch =
          keys.subList(from, Math.min(from + DELETE_BATCH, keys.size())).stream()
              .map(k -> ObjectIdentifier.builder().key(k).build())
              .toList();
      this.storageWrite(
          () ->
              this.s3.deleteObjects(
                  r -> r.bucket(this.bucket).delete(Delete.builder().objects(batch).build())));
    }
  }

  private Optional<Instant> toDayStart(final String day) {
    try {
      return Optional.of(LocalDate.parse(day).atStartOfDay(this.clock.getZone()).toInstant());
    } catch (final DateTimeParseException e) {
      return Optional.empty();
    }
  }

  /**
   * Walks the listing under {@code listPrefix}, page by page: with {@code shallow} only its direct
   * objects and the common prefixes one level down, otherwise every object below it.
   */
  private void list(
      final String listPrefix,
      final boolean shallow,
      final Consumer<S3Object> onObject,
      final Consumer<CommonPrefix> onPrefix) {
    final var request = ListObjectsV2Request.builder().bucket(this.bucket).prefix(listPrefix);

    if (shallow) {
      request.delimiter(DELIMITER);
    }

    for (final var page : this.s3.listObjectsV2Paginator(request.build())) {
      page.contents().forEach(onObject);
      page.commonPrefixes().forEach(onPrefix);
    }
  }

  private boolean hasChildren(final String key) {
    return this.s3
            .listObjectsV2(r -> r.bucket(this.bucket).prefix(key + DELIMITER).maxKeys(1))
            .keyCount()
        > 0;
  }

  private Optional<HeadObjectResponse> head(final String key) {
    try {
      return Optional.of(this.s3.headObject(r -> r.bucket(this.bucket).key(key)));
    } catch (final NoSuchKeyException e) {
      return Optional.empty();
    } catch (final S3Exception e) {
      if (e.statusCode() == HTTP_NOT_FOUND) {
        return Optional.empty();
      }
      throw e;
    }
  }

  private long sizeOf(final String key) {
    return this.head(key).map(HeadObjectResponse::contentLength).orElse(0L);
  }

  private InputStream storageRead(final String key) {
    return this.storageWrite(() -> this.s3.getObject(r -> r.bucket(this.bucket).key(key)));
  }

  /** Runs a call to the store, answering a failure of the store as a storage outage. */
  private <T extends @Nullable Object> T storageWrite(final StoreCall<T> call) {
    try {
      return call.run();
    } catch (final SdkException e) {
      throw new StorageUnavailableException(
          "The object store failed: " + e.getMessage(), new IOException(e));
    }
  }

  @FunctionalInterface
  private interface StoreCall<T extends @Nullable Object> {
    T run();
  }

  private String toKey(final StoragePath storagePath) {
    final String path = trimSlashes(storagePath.getPath());

    for (final String segment : path.split(DELIMITER, -1)) {
      if (segment.equals("..") || segment.equals(".")) {
        throw new InvalidStoragePathException("invalidStoragePath");
      }
    }

    return path.isEmpty() ? this.prefix : this.prefix + DELIMITER + path;
  }

  /** A real object directly or deeply under {@code listPrefix}, not a directory marker. */
  private static boolean isFile(final S3Object object, final String listPrefix) {
    return !object.key().endsWith(DELIMITER) && !object.key().equals(listPrefix);
  }

  /** Adds every key prefix between {@code root} (exclusive) and {@code key}'s parent. */
  private static void addParentDirectories(
      final String key, final String root, final Set<String> directories) {
    String parent = trimSlashes(key);
    int slash = parent.lastIndexOf(DELIMITER);

    while (slash > root.length()) {
      parent = parent.substring(0, slash);
      directories.add(parent);
      slash = parent.lastIndexOf(DELIMITER);
    }
  }

  private static StorageItemInfo fileItem(final S3Object object) {
    return StorageItemInfo.builder()
        .name(lastSegment(object.key()))
        .size(object.size())
        .createdAt(Date.from(object.lastModified()))
        .directory(false)
        .path(DELIMITER + object.key())
        .build();
  }

  private static StorageItemInfo directoryItem(final String key) {
    return StorageItemInfo.builder()
        .name(lastSegment(key))
        .size(0L)
        .createdAt(Date.from(Instant.EPOCH))
        .directory(true)
        .path(DELIMITER + key)
        .build();
  }

  private static String lastSegment(final String key) {
    final String trimmed = trimSlashes(key);
    return trimmed.substring(trimmed.lastIndexOf(DELIMITER) + 1);
  }

  private static String trimSlashes(final String value) {
    int start = 0;
    int end = value.length();

    while (start < end && value.charAt(start) == '/') {
      start++;
    }

    while (end > start && value.charAt(end - 1) == '/') {
      end--;
    }

    return value.substring(start, end);
  }
}
