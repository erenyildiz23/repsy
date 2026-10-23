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

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.AbstractResource;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * An object of the store as a {@link org.springframework.core.io.Resource}. Its length and time are
 * the ones the lookup saw; every {@link #getInputStream()} is a fresh {@code GetObject} streamed
 * from the store, so a download is never held in memory.
 */
final class S3ObjectResource extends AbstractResource {

  private final S3Client s3;
  private final String bucket;
  private final String key;
  private final long contentLength;
  private final Instant lastModified;

  S3ObjectResource(
      final S3Client s3,
      final String bucket,
      final String key,
      final long contentLength,
      final Instant lastModified) {
    this.s3 = s3;
    this.bucket = bucket;
    this.key = key;
    this.contentLength = contentLength;
    this.lastModified = lastModified;
  }

  @Override
  public InputStream getInputStream() throws FileNotFoundException {
    try {
      return this.s3.getObject(r -> r.bucket(this.bucket).key(this.key));
    } catch (final NoSuchKeyException e) {
      final var notFound = new FileNotFoundException(this.getDescription());
      notFound.initCause(e);
      throw notFound;
    }
  }

  @Override
  public boolean exists() {
    return true;
  }

  @Override
  public long contentLength() {
    return this.contentLength;
  }

  @Override
  public long lastModified() {
    return this.lastModified.toEpochMilli();
  }

  @Override
  public String getFilename() {
    return this.key.substring(this.key.lastIndexOf('/') + 1);
  }

  @Override
  public String getDescription() {
    return "s3://" + this.bucket + "/" + this.key;
  }

  @Override
  public boolean equals(final @Nullable Object other) {
    return other instanceof final S3ObjectResource that
        && this.bucket.equals(that.bucket)
        && this.key.equals(that.key);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.bucket, this.key);
  }
}
